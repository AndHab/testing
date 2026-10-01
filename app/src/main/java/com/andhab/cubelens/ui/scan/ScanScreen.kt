package com.andhab.cubelens.ui.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andhab.cubelens.core.vision.StickerSample

/**
 * Camera scanning flow: guides the user through all six faces and reports the raw samples.
 *
 * STUB: replaced by the real implementation; keep this signature.
 *
 * @param onScanned called once with six scans (capture order), each nine samples row-major as
 *   seen on screen; pass to [com.andhab.cubelens.core.vision.ScanResolver.resolve].
 * @param onManualEntry the user prefers typing colors (e.g. camera permission denied).
 */
@Composable
fun ScanScreen(
    onScanned: (List<List<StickerSample>>) -> Unit,
    onBack: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) { Text("Scan") }
}
