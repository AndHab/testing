package com.andhab.cubelens.ui.scan

import android.content.Context
import androidx.core.content.edit

/**
 * What the app has learned about the camera permission, to tell whether asking again would show the
 * system dialog or only Settings can turn the camera on.
 *
 * Android only reveals this indirectly. After one refusal it asks apps to show a rationale; after a
 * refusal for good it stops doing so and refuses further requests without showing anything. But
 * before the first refusal, and after the dialog was merely dismissed, there is no rationale
 * either. So a missing rationale means "denied for good" only once the user is known to have
 * refused before, or after two requests in a row came back refused with no rationale at all.
 *
 * @property refusedBefore the user refused the permission at least once (kept across sessions).
 * @property silentRefusals requests in a row, this session, that were refused without a rationale
 *   before or after: dismissed dialogs, or dialogs the system no longer shows.
 */
internal data class CameraPermissionMemory(
    val refusedBefore: Boolean = false,
    val silentRefusals: Int = 0,
) {
    /** Whether the system will no longer show the permission dialog, given the current state. */
    fun deniedForGood(granted: Boolean, showRationale: Boolean): Boolean =
        !granted && !showRationale && (refusedBefore || silentRefusals >= SILENT_REFUSALS_FOR_GOOD)

    /** Updated after looking at the permission (e.g. on resume, back from Settings). */
    fun observe(granted: Boolean, showRationale: Boolean): CameraPermissionMemory = when {
        granted -> CameraPermissionMemory()
        showRationale -> CameraPermissionMemory(refusedBefore = true)
        else -> this
    }

    /**
     * Updated after a permission request came back.
     *
     * @param rationaleBefore whether a rationale was due right before the request.
     * @param rationaleAfter whether one is due now.
     */
    fun afterRequest(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): CameraPermissionMemory =
        when {
            granted -> CameraPermissionMemory()
            // First refusal, or a dismissed dialog after one.
            rationaleAfter -> CameraPermissionMemory(refusedBefore = true)
            // Refused again: the rationale went away because the system stopped asking.
            rationaleBefore -> copy(refusedBefore = true)
            else -> copy(silentRefusals = silentRefusals + 1)
        }

    private companion object {
        /** Two silent refusals in a row: the dialog is most likely not showing at all. */
        const val SILENT_REFUSALS_FOR_GOOD = 2
    }
}

/** Keeps [CameraPermissionMemory.refusedBefore] in the app's private preferences. */
internal class CameraPermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The memory as of the last session; silent refusals only count within one session. */
    fun load(): CameraPermissionMemory = CameraPermissionMemory(refusedBefore = preferences.getBoolean(KEY_REFUSED, false))

    /** Remembers [memory] for later sessions. */
    fun save(memory: CameraPermissionMemory) {
        if (preferences.getBoolean(KEY_REFUSED, false) != memory.refusedBefore) {
            preferences.edit { putBoolean(KEY_REFUSED, memory.refusedBefore) }
        }
    }

    private companion object {
        const val PREFERENCES = "cubelens.scan"
        const val KEY_REFUSED = "camera_refused_before"
    }
}
