package com.andhab.cubelens.ui.review

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.NxNValidator
import com.andhab.cubelens.core.vision.CubePaletteEstimate
import com.andhab.cubelens.core.vision.NxNScanAnalysis
import com.andhab.cubelens.core.vision.Placement
import kotlin.random.Random

/** A scan analysis of an N×N cube (N from the 6·N² [colors]), as the scanner would report it. */
internal fun scanAnalysis(
    colors: List<CubeColor>,
    faceRotations: Map<Face, Int> = Face.entries.associateWith { 0 },
    uncertain: Set<Int> = emptySet(),
    palette: CubePaletteEstimate = CubePaletteEstimate.STANDARD,
): NxNScanAnalysis {
    val n = cubeSizeOf(colors.size)
    val validation = NxNValidator.validate(NxNCube.of(n, colors))
    return NxNScanAnalysis(
        n = n,
        rawColors = colors,
        colors = colors,
        faceRotations = faceRotations,
        isValid = validation.isValid,
        uncertain = uncertain,
        palette = palette,
        placement = Placement.SCAN_ORDER,
        problems = validation.errors,
    )
}

/** The colors of a scrambled [n]×[n] cube, the same for the same [seed]. */
internal fun scrambledColors(n: Int, seed: Int = 7): List<CubeColor> =
    NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(seed))).toColors()

/**
 * Manual entry of an [n]×[n] cube with the first [stickers] editable stickers copied from [colors]
 * the way a user would: tapping colors and letting the selection hop on in entry order.
 */
internal fun manualEntry(n: Int, colors: List<CubeColor>, stickers: Int): ReviewState {
    var review = ReviewState.manual(n)
    repeat(stickers) {
        val next = checkNotNull(review.selected) { "Nothing left to fill" }
        review = review.tapColor(colors[next])
    }
    return review
}
