package com.andhab.cubelens.ui.cube

import androidx.compose.ui.graphics.Color
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.ui.theme.StickerPalette
import kotlin.random.Random

/** Deterministic cubes and palettes for the cube tests. */
internal object TestCubes {

    /**
     * A repeatable random scramble of an [n]×[n] cube mixing outer turns, wide turns and inner
     * slices, so every kind of piece ends up somewhere else.
     */
    fun scramble(n: Int, seed: Int = 1, length: Int = 10 * n): List<LayerMove> {
        val random = Random(seed * 31 + n)
        var lastFace: Face? = null
        return List(length) {
            var face: Face
            do face = Face.entries[random.nextInt(6)] while (face == lastFace)
            lastFace = face
            val turns = 1 + random.nextInt(3)
            if (n == 2 || random.nextInt(3) == 0) {
                LayerMove(face, 1, 1, turns)
            } else {
                val from = 1 + random.nextInt(n - 1)
                val to = (from + random.nextInt(2)).coerceAtMost(n - 1)
                LayerMove(face, from, to, turns)
            }
        }
    }

    /** The colors of a scrambled [n]×[n] cube. */
    fun scrambled(n: Int, seed: Int = 1): List<CubeColor?> = NxNCube.solved(n).apply(scramble(n, seed)).toColors()

    /** A knock-off cube with pastel stickers. */
    val Pastel = StickerPalette(
        mapOf(
            CubeColor.WHITE to Color(0xFFF7F5EE),
            CubeColor.YELLOW to Color(0xFFF3E58A),
            CubeColor.GREEN to Color(0xFF9EDDB0),
            CubeColor.BLUE to Color(0xFF93BFEA),
            CubeColor.RED to Color(0xFFF2A0B4),
            CubeColor.ORANGE to Color(0xFFF7BE92),
        ),
    )
}

/**
 * Bytes allocated on this thread while running [block], or 0 when the JVM cannot tell. Used to
 * check that per-frame code allocates nothing.
 */
internal inline fun allocatedBytes(block: () -> Unit): Long {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    if (bean == null || !bean.isThreadAllocatedMemorySupported) {
        block()
        return 0L
    }
    @Suppress("DEPRECATION") val id = Thread.currentThread().id
    val before = bean.getThreadAllocatedBytes(id)
    block()
    return bean.getThreadAllocatedBytes(id) - before
}
