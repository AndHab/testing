package com.andhab.cubelens.camera

import android.util.Size
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Live back-camera preview with frame analysis, for scanning cube faces.
 *
 * Binds a CameraX [Preview] and an RGBA [ImageAnalysis] (4:3, about 1280x960, keeping only the
 * latest frame) to the current lifecycle as one [UseCaseGroup] with the preview's [ViewPort], so
 * each analysis frame's crop rectangle is exactly what the user sees (see [GuideMapper]). The
 * preview fills its bounds (`FILL_CENTER`). Frames are analyzed on a dedicated background thread;
 * everything is unbound and the thread shut down when this leaves the composition.
 *
 * Autofocus runs continuously; once the preview is streaming, and again whenever [focusKey]
 * changes, focus and exposure are metered at [focusPoint] (the guide center).
 *
 * While the screen is visible, a [CameraWatchdog] checks that the camera actually works after
 * binding (another app may hold it, or it may be disabled) and reports it through [onError].
 *
 * @param analyzer receives every analyzed frame.
 * @param torchOn whether the flashlight should be on (ignored without a flash unit).
 * @param focusPoint point to meter focus and exposure at, in this view's pixels.
 * @param focusKey re-meters at [focusPoint] when it changes (e.g. the face being scanned).
 * @param onTorchAvailable called once bound, with whether the camera has a flash unit.
 * @param onTorchChange called with the flashlight's actual state whenever it changes or a request
 *   to change it failed, e.g. off once the app went to the background; keep [torchOn] in step.
 * @param onError called if the camera cannot be started or stops working (no back camera, camera
 *   in use or disabled, no frames...).
 */
@Composable
fun CubeCameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    torchOn: Boolean,
    focusPoint: Offset?,
    onTorchAvailable: (Boolean) -> Unit,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    focusKey: Any? = null,
    onTorchChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView keeps the preview well-behaved under Compose overlays and transitions.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Frames analyzed so far, for the watchdog; written only on the analysis thread.
    val frameCount = remember { MutableStateFlow(0L) }
    val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)
    val currentOnTorchChange by rememberUpdatedState(onTorchChange)
    val currentOnError by rememberUpdatedState(onError)

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(previewView, lifecycleOwner, analyzer) {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CubeLens-analysis") }
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        val useCases = mutableListOf<UseCase>()
        try {
            val cameraProvider = ProcessCameraProvider.awaitInstance(context)
            provider = cameraProvider
            val viewPort = previewView.awaitViewPort()
            val rotation = previewView.display?.rotation ?: viewPort.rotation

            val preview = Preview.Builder()
                .setResolutionSelector(PreviewResolution)
                .setTargetRotation(rotation)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(AnalysisResolution)
                .setTargetRotation(rotation)
                .build()
            imageAnalysis.setAnalyzer(executor) { image ->
                frameCount.value += 1
                analyzer.analyze(image)
            }
            analysis = imageAnalysis
            useCases += preview
            useCases += imageAnalysis

            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .build()
            cameraProvider.unbindAll()
            val bound = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            camera = bound
            currentOnTorchAvailable(bound.cameraInfo.hasFlashUnit())
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentOnError(e)
        } finally {
            camera = null
            analysis?.clearAnalyzer()
            if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
            executor.shutdown()
        }
    }

    LaunchedEffect(camera, lifecycleOwner) {
        val bound = camera ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Each time the screen comes back, the camera must deliver frames again.
            val seen = frameCount.value
            val failure = CameraWatchdog.awaitFailure(
                cameraStates = bound.cameraInfo.cameraState.asFlow(),
                frameArrived = frameCount.map { it > seen },
            )
            currentOnError(failure)
        }
    }

    LaunchedEffect(camera) {
        // The camera turns the flashlight off by itself, e.g. when the app goes to the background.
        val bound = camera ?: return@LaunchedEffect
        bound.cameraInfo.torchState.asFlow().collect { currentOnTorchChange(it == TorchState.ON) }
    }

    LaunchedEffect(camera, torchOn) {
        val bound = camera ?: return@LaunchedEffect
        if (!bound.cameraInfo.hasFlashUnit()) return@LaunchedEffect
        // Fails while the camera isn't open yet (or anymore): then report what the light really does.
        val applied = bound.cameraControl.enableTorch(torchOn).awaitSuccess()
        if (!applied) currentOnTorchChange(bound.cameraInfo.torchState.value == TorchState.ON)
    }

    LaunchedEffect(camera, focusPoint, focusKey) {
        val bound = camera ?: return@LaunchedEffect
        val point = focusPoint ?: return@LaunchedEffect
        // The metering point factory only knows the preview's transform once frames are flowing.
        previewView.awaitStreaming()
        val meteringPoint = previewView.meteringPointFactory.createPoint(point.x, point.y)
        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS).build()
        // Fire and forget: an unsupported or superseded request just leaves continuous autofocus on.
        bound.cameraControl.startFocusAndMetering(action)
    }
}

/** How long a metered focus holds before the camera returns to continuous autofocus. */
private const val FOCUS_HOLD_SECONDS = 4L

private val PreviewResolution: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

/** 4:3 near 1280x960: plenty of pixels for nine stickers, cheap to read. */
private val AnalysisResolution: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
    )
    .build()

/** The preview's viewport, waiting for the view to be laid out and attached if necessary. */
private suspend fun PreviewView.awaitViewPort(): ViewPort {
    viewPort?.let { return it }
    return suspendCancellableCoroutine { continuation ->
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                val port = viewPort ?: return
                removeOnLayoutChangeListener(this)
                if (continuation.isActive) continuation.resume(port)
            }
        }
        addOnLayoutChangeListener(listener)
        continuation.invokeOnCancellation {
            ContextCompat.getMainExecutor(context).execute { removeOnLayoutChangeListener(listener) }
        }
    }
}

/** Waits for this future to finish; true if it succeeded. A newer request cancels the wait. */
private suspend fun ListenableFuture<*>.awaitSuccess(): Boolean = suspendCancellableCoroutine { continuation ->
    addListener({ continuation.resume(runCatching { get() }.isSuccess) }, Runnable::run)
}

/** Suspends until the preview is showing camera frames. */
private suspend fun PreviewView.awaitStreaming() {
    if (previewStreamState.value == PreviewView.StreamState.STREAMING) return
    suspendCancellableCoroutine { continuation ->
        val states = previewStreamState
        val observer = object : Observer<PreviewView.StreamState> {
            override fun onChanged(value: PreviewView.StreamState) {
                if (value != PreviewView.StreamState.STREAMING) return
                states.removeObserver(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        states.observeForever(observer)
        continuation.invokeOnCancellation {
            ContextCompat.getMainExecutor(context).execute { states.removeObserver(observer) }
        }
    }
}
