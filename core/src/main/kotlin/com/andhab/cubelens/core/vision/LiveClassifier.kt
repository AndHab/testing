package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor

/**
 * Fast single-sticker classification for live feedback while scanning.
 *
 * Nearest [reference] color in [ColorMath.deltaE], with two guard rules for the pairs that a single
 * sample can confuse under colored light:
 *  - white vs yellow: decided by the yellow-blue axis b* (relative to the white reference), since a
 *    warm-lit white gains b* but stays far below a yellow sticker's;
 *  - red vs orange: decided by hue angle, since shading changes their lightness a lot but their
 *    hue (about 35 vs 58 degrees for typical stickers) much less.
 */
object LiveClassifier {

    /**
     * Typical CIELAB values of standard sticker colors as phone cameras capture them in ordinary
     * indoor light (auto white balance), measured from real scans.
     */
    val reference: Map<CubeColor, Lab> = mapOf(
        CubeColor.WHITE to Lab(84f, -1f, -4f),
        CubeColor.YELLOW to Lab(83f, -13f, 70f),
        CubeColor.GREEN to Lab(60f, -48f, 33f),
        CubeColor.BLUE to Lab(34f, 12f, -50f),
        CubeColor.RED to Lab(39f, 58f, 40f),
        CubeColor.ORANGE to Lab(61f, 39f, 64f),
    )

    /** Hue angle (degrees) separating red from orange. */
    private const val RED_ORANGE_HUE = 46f

    /** b* (above the white reference) at which a light, weakly chromatic sample turns from white to yellow. */
    private const val WHITE_YELLOW_B = 30f

    private val colors = CubeColor.entries
    private val references = colors.map { reference.getValue(it) }

    /** The most likely color of [sample]. */
    fun classify(sample: StickerSample): CubeColor {
        val lab = sample.lab
        var best = colors[0]
        var bestDistance = Float.MAX_VALUE
        for (i in colors.indices) {
            val d = ColorMath.deltaE(lab, references[i])
            if (d < bestDistance) {
                bestDistance = d
                best = colors[i]
            }
        }
        return when (best) {
            CubeColor.WHITE, CubeColor.YELLOW ->
                if (lab.b - reference.getValue(CubeColor.WHITE).b < WHITE_YELLOW_B) CubeColor.WHITE else CubeColor.YELLOW
            CubeColor.RED, CubeColor.ORANGE -> {
                val h = lab.hue
                if (h < RED_ORANGE_HUE || h > 300f) CubeColor.RED else CubeColor.ORANGE
            }
            else -> best
        }
    }

    /** Distances from [lab] to every reference color, indexed by [CubeColor.ordinal]. */
    internal fun distances(lab: Lab): FloatArray = FloatArray(colors.size) { ColorMath.deltaE(lab, references[it]) }
}
