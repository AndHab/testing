package com.andhab.cubelens.core.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class TableCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val tables get() = SolverFixture.tables

    @Test
    fun roundTripGivesIdenticalTablesAndSolutions() {
        val file = File(temp.root, "tables.bin")
        TableCache.write(tables, file)
        assertEquals(TableCache.FILE_BYTES, file.length())
        assertTrue(TableCache.looksValid(file))

        val loaded = checkNotNull(TableCache.read(file))
        assertNotSame(tables, loaded)
        assertTrue(loaded.contentEquals(tables))

        // Without a timeout cutting it short, the search is deterministic for given tables.
        val random = Random(77)
        repeat(20) {
            val cube = Scrambler.randomState(random)
            assertEquals(
                TwoPhaseSolver.solveWith(tables, cube, 21, 60_000),
                TwoPhaseSolver.solveWith(loaded, cube, 21, 60_000),
            )
        }
        // No temporary files are left behind.
        assertEquals(listOf("tables.bin"), temp.root.list()!!.toList())
    }

    @Test
    fun validCacheIsLoadedInsteadOfBuilt() {
        val file = File(temp.root, "tables.bin")
        TableCache.write(tables, file)
        val loaded = TableCache.loadOrBuild(file) { throw AssertionError("A valid cache must not be rebuilt") }
        assertTrue(loaded.contentEquals(tables))
    }

    @Test
    fun missingCacheIsBuiltAndWritten() {
        val file = File(temp.root, "nested/dir/tables.bin")
        val result = TableCache.loadOrBuild(file) { tables }
        assertSame(tables, result)
        assertNotNull(TableCache.read(file))
    }

    @Test
    fun corruptedCachesAreIgnoredAndRebuilt() {
        val corruptions: Map<String, (File) -> Unit> = mapOf(
            "flipped payload byte" to { f -> flipByte(f, TableCache.FILE_BYTES / 2) },
            "flipped checksum byte" to { f -> flipByte(f, TableCache.FILE_BYTES - 1) },
            "wrong version" to { f -> flipByte(f, 7) },
            "wrong magic" to { f -> flipByte(f, 0) },
            "truncated" to { f -> RandomAccessFile(f, "rw").use { it.setLength(TableCache.FILE_BYTES - 1000) } },
            "too long" to { f -> f.appendBytes(ByteArray(10)) },
            "empty" to { f -> f.writeBytes(ByteArray(0)) },
            "random bytes" to { f -> f.writeBytes(Random(3).nextBytes(TableCache.FILE_BYTES.toInt())) },
            "a directory" to { f -> f.delete(); f.mkdirs() },
        )
        for ((name, corrupt) in corruptions) {
            val file = File(temp.root, "cache-${name.replace(' ', '-')}.bin")
            TableCache.write(tables, file)
            corrupt(file)
            assertNull(name, TableCache.read(file))

            var built = false
            val result = TableCache.loadOrBuild(file) {
                built = true
                tables
            }
            assertTrue(name, built)
            assertSame(name, tables, result)
            if (!file.isDirectory) {
                // The damaged file has been replaced with a good one.
                assertTrue(name, TableCache.read(file)?.contentEquals(tables) == true)
            }
        }
    }

    @Test
    fun shortCacheFileNamesWork() {
        val file = File(temp.root, "t")
        assertTrue(TableCache.tryWrite(tables, file))
        assertNotNull(TableCache.read(file))
    }

    @Test
    fun unwritableCacheLocationIsIgnored() {
        val blocker = temp.newFile("not-a-directory")
        val file = File(blocker, "tables.bin")
        assertFalse(TableCache.tryWrite(tables, file))
        assertSame(tables, TableCache.loadOrBuild(file) { tables })
    }

    @Test
    fun prepareWritesTheCacheFile() {
        val file = File(temp.root, "solver-cache.bin")
        TwoPhaseSolver.prepare(file)
        assertTrue(TwoPhaseSolver.isPrepared)
        assertTrue(checkNotNull(TableCache.read(file)).contentEquals(tables))

        // A damaged cache is replaced the next time it is offered.
        file.writeBytes(ByteArray(100))
        TwoPhaseSolver.prepare(file)
        assertTrue(TableCache.looksValid(file))
    }

    private fun flipByte(file: File, position: Long) {
        RandomAccessFile(file, "rw").use {
            it.seek(position)
            val b = it.read()
            it.seek(position)
            it.write(b xor 0x5A)
        }
    }
}
