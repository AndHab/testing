package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNError
import com.andhab.cubelens.core.nxn.NxNValidator

/*
 * TEMPORARY stand-ins for the N×N vision API, so the app screens can be built against it while the
 * real implementation is being written. They work properly for 3×3 (delegating to the reviewed
 * pipeline) and crudely for other sizes. This whole file is deleted when the real N×N vision lands
 * (which declares these as members/types with the same signatures).
 */

/** Result of turning six scanned N×N faces into a cube (see [ScanAnalysis] for the 3×3 meaning of each field). */
data class NxNScanAnalysis(
    val n: Int,
    val rawColors: List<CubeColor>,
    val colors: List<CubeColor>,
    val faceRotations: Map<Face, Int>,
    val isValid: Boolean,
    val uncertain: Set<Int>,
    val palette: CubePaletteEstimate,
    val placement: Placement,
    val problems: List<NxNError>,
)

/** Samples the n×n stickers inside [region], row-major as seen upright. (Stand-in.) */
fun GridSampler.sample(source: PixelSource, region: GridRegion, rotationDegrees: Int, n: Int): List<StickerSample> {
    if (n == 3) return sample(source, region, rotationDegrees)
    val raw = ArrayList<StickerSample>(n * n)
    val cell = region.size.toDouble() / n
    for (r in 0 until n) for (c in 0 until n) {
        var sr = 0L; var sg = 0L; var sb = 0L; var count = 0
        val y0 = region.top + ((r + 0.3) * cell).toInt()
        val y1 = region.top + ((r + 0.7) * cell).toInt()
        val x0 = region.left + ((c + 0.3) * cell).toInt()
        val x1 = region.left + ((c + 0.7) * cell).toInt()
        val step = maxOf(1, (x1 - x0) / 6)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                if (x in 0 until source.width && y in 0 until source.height) {
                    val p = source.argb(x, y)
                    sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; count++
                }
                x += step
            }
            y += step
        }
        val k = maxOf(1, count)
        raw += StickerSample.of((sr / k).toInt(), (sg / k).toInt(), (sb / k).toInt())
    }
    // Rotate the grid so it reads upright.
    val turns = ((rotationDegrees / 90) % 4 + 4) % 4
    var grid = raw.toList()
    repeat(turns) { grid = List(n * n) { i -> grid[(n - 1 - i % n) * n + i / n] } }
    return grid
}

/** Resolves six scans of an n×n cube. (Stand-in: per-sticker classification, no orientation fixing for n != 3.) */
fun ScanResolver.resolve(n: Int, scans: List<List<StickerSample>>, scanPositions: List<Face>? = null): NxNScanAnalysis {
    if (n == 3) {
        val a = resolve(scans, scanPositions)
        val problems = if (a.isValid) emptyList() else NxNValidator.validate(NxNCube.of(3, a.colors)).errors
        return NxNScanAnalysis(3, a.rawColors, a.colors, a.faceRotations, a.isValid, a.uncertain, a.palette, a.placement, problems)
    }
    val positions = scanPositions?.takeIf { it.toSet().size == 6 } ?: listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D)
    val per = n * n
    val colors = MutableList(6 * per) { CubeColor.WHITE }
    for ((i, scan) in scans.take(6).withIndex()) {
        val face = positions[i]
        for (k in 0 until minOf(per, scan.size)) colors[face.ordinal * per + k] = LiveClassifier.classify(scan[k])
    }
    val validation = NxNValidator.validate(NxNCube.of(n, colors))
    return NxNScanAnalysis(
        n, colors, colors, Face.entries.associateWith { 0 }, validation.isValid, emptySet(),
        CubePaletteEstimate.STANDARD, Placement.SCAN_ORDER, validation.errors,
    )
}

/**
 * Learns this cube's colors from the faces captured so far. Odd n: names the fixed centers (like
 * [AdaptiveLiveClassifier.learnCenters]) and returns them; even n: returns null. (Stand-in.)
 */
fun AdaptiveLiveClassifier.learnFaces(faces: List<List<StickerSample>>, n: Int): List<CubeColor>? {
    if (n % 2 == 0) return null
    val mid = (n * n) / 2
    return learnCenters(faces.map { it[mid] }, if (n == 3) faces else emptyList())
}
