package com.andhab.cubelens.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** The cube sizes the app supports, smallest first: 2×2 up to 7×7. */
val SupportedCubeSizes: IntRange = 2..7

/** The size picked when the user has never picked one: the classic 3×3. */
const val DEFAULT_CUBE_SIZE = 3

/**
 * Remembers the cube size the user picked on Home across launches. Lets tests substitute a fake.
 */
interface CubeSizeStore {
    /** The size picked last, or `null` if none was ever picked (or it is no longer supported). */
    fun load(): Int?

    /** Remembers [size] as the size picked last. */
    fun save(size: Int)
}

/** A [CubeSizeStore] that forgets everything when the process ends; for tests and previews. */
class InMemoryCubeSizeStore(private var size: Int? = null) : CubeSizeStore {
    override fun load(): Int? = size

    override fun save(size: Int) {
        this.size = size
    }
}

/** A [CubeSizeStore] in the app's private [SharedPreferences]. */
class PreferencesCubeSizeStore(private val preferences: SharedPreferences) : CubeSizeStore {

    /** Uses the app's own preferences file in [context]. */
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    override fun load(): Int? = preferences.getInt(KEY_SIZE, 0).takeIf { it in SupportedCubeSizes }

    override fun save(size: Int) = preferences.edit { putInt(KEY_SIZE, size) }

    private companion object {
        const val PREFERENCES_NAME = "cubelens"
        const val KEY_SIZE = "cube_size"
    }
}
