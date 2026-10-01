package com.andhab.cubelens.ui.solve

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Move

/**
 * Step-by-step animated solution playback.
 *
 * STUB: replaced by the real implementation; keep this signature.
 *
 * @param startColors the scrambled cube (54 colors, facelet order, standard orientation).
 * @param moves the solution; applying them to [startColors] solves the cube.
 * @param onDone leave playback (e.g. "Scan another cube").
 */
@Composable
fun SolveScreen(
    startColors: List<CubeColor>,
    moves: List<Move>,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) { Text("Solve") }
}
