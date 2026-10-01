package com.andhab.cubelens.ui.scan

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andhab.cubelens.camera.CubeCameraPreview
import com.andhab.cubelens.camera.CubeFrameAnalyzer
import com.andhab.cubelens.camera.GuideGeometry
import com.andhab.cubelens.core.vision.StickerSample
import kotlinx.coroutines.delay

/**
 * Camera scanning flow: guides the user through all six faces and reports the raw samples.
 *
 * Without camera permission it explains why the camera is needed and asks for it (or, once the
 * permission was denied for good, sends the user to the app's settings and checks again on
 * return; see [CameraPermissionMemory]). With it, it shows the live camera with the scan guide
 * and captures each face, by itself once the face is held steady or with the shutter. After the
 * sixth face it pauses on a short success state and reports the scans. A scan in progress
 * survives activity recreation and process death.
 *
 * @param onScanned called once with six scans, one per guided step in [ScanStep] order (the capture
 *   order, unless a face was retaken), each nine samples row-major as seen on screen; pass to
 *   [com.andhab.cubelens.core.vision.ScanResolver.resolve], which places faces by their centers.
 * @param onManualEntry the user prefers typing colors (e.g. camera permission denied).
 */
@Composable
fun ScanScreen(
    onScanned: (List<List<StickerSample>>) -> Unit,
    onBack: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 3,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val store = remember(context) { CameraPermissionStore(context) }
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var memory by remember { mutableStateOf(store.load()) }
    var showRationale by remember { mutableStateOf(activity?.shouldShowCameraRationale() == true) }
    val requestState = remember { PermissionRequest() }
    fun learn(updated: CameraPermissionMemory) {
        memory = updated
        store.save(updated)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        showRationale = activity?.shouldShowCameraRationale() == true
        learn(memory.afterRequest(ok, rationaleBefore = requestState.rationaleBefore, rationaleAfter = showRationale))
    }
    LifecycleResumeEffect(Unit) {
        // Back from Settings (or the permission was revoked meanwhile): check again.
        granted = context.hasCameraPermission()
        showRationale = activity?.shouldShowCameraRationale() == true
        learn(memory.observe(granted, showRationale))
        onPauseOrDispose { }
    }
    val deniedForGood = memory.deniedForGood(granted, showRationale)

    if (granted) {
        CameraScan(onScanned = onScanned, onBack = onBack, onManualEntry = onManualEntry, modifier = modifier)
    } else {
        CameraGateContent(
            gate = if (deniedForGood) CameraGate.Denied else CameraGate.Rationale,
            onPrimary = {
                if (deniedForGood) {
                    context.openAppSettings()
                } else {
                    requestState.rationaleBefore = activity?.shouldShowCameraRationale() == true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onManualEntry = onManualEntry,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/** Whether a rationale was due when the pending permission request was made; read in its callback. */
private class PermissionRequest(var rationaleBefore: Boolean = false)

/** How long the success state stays up before the scans are handed over. */
private const val CompletionPauseMillis = 1_300L

@Composable
private fun CameraScan(
    onScanned: (List<List<StickerSample>>) -> Unit,
    onBack: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saved, so a scan in progress survives rotation, theme or size changes and process death.
    val controller = rememberSaveable(saver = ScanController.saver()) { ScanController() }
    val state by controller.state.collectAsStateWithLifecycle()
    val analyzer = remember(controller) { CubeFrameAnalyzer(controller::onFrame) }
    var guide by remember { mutableStateOf<GuideGeometry?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var cameraAttempt by remember { mutableIntStateOf(0) }

    if (cameraFailed) {
        CameraGateContent(
            gate = CameraGate.Unavailable,
            onPrimary = {
                cameraFailed = false
                cameraAttempt++
            },
            onManualEntry = onManualEntry,
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    ScanContent(
        state = state,
        preview = {
            key(cameraAttempt) {
                CubeCameraPreview(
                    analyzer = analyzer,
                    torchOn = state.torchOn,
                    focusPoint = guide?.let { Offset(it.centerX, it.centerY) },
                    focusKey = state.currentStep,
                    onTorchAvailable = controller::setTorchAvailable,
                    onTorchChange = controller::setTorch,
                    onError = { cameraFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        onBack = onBack,
        onCapture = { controller.capture() },
        onAutoCaptureChange = controller::setAutoCapture,
        onTorchChange = controller::setTorch,
        onSelectStep = controller::selectStep,
        onManualEntry = onManualEntry,
        onGuideChange = {
            guide = it
            analyzer.updateGuide(it)
        },
        modifier = modifier,
    )

    if (state.isComplete) {
        val currentOnScanned by rememberUpdatedState(onScanned)
        LaunchedEffect(controller) {
            delay(CompletionPauseMillis)
            currentOnScanned(controller.scans())
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Activity.shouldShowCameraRationale(): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

/** Opens this app's page in the system settings, where the camera permission can be turned on. */
private fun Context.openAppSettings() {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    if (this !is Activity) details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(details)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).apply { if (this@openAppSettings !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
