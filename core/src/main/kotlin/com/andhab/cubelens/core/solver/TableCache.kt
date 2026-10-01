package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.solver.SolverTables.Companion.BYTE_TABLE_SIZES
import com.andhab.cubelens.core.solver.SolverTables.Companion.CHAR_TABLE_SIZES
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * Stores [SolverTables] in a file so that later app starts can skip building them.
 *
 * Layout (big-endian): magic `"CLTP"`, format [VERSION], payload length (long), the payload (all
 * char tables as 16-bit values, then all byte tables, in [SolverTables] serialization order) and a
 * CRC-32 of the payload (long). A file that does not match in every respect is treated as absent,
 * so a stale, truncated or corrupted cache simply gets rebuilt. Files are written to a temporary
 * file next to the target and renamed into place, so readers never see a half-written cache.
 */
internal object TableCache {
    private const val MAGIC = 0x434C5450 // "CLTP"

    /** Bump whenever the coordinates, table layout or table contents change. */
    const val VERSION = 1

    private const val HEADER_BYTES = 16L
    private const val TRAILER_BYTES = 8L

    /** Payload size in bytes. */
    val PAYLOAD_BYTES: Long = CHAR_TABLE_SIZES.sumOf { it * 2L } + BYTE_TABLE_SIZES.sumOf { it.toLong() }

    /** Total size of a valid cache file in bytes. */
    val FILE_BYTES: Long = HEADER_BYTES + PAYLOAD_BYTES + TRAILER_BYTES

    /**
     * Loads tables from [cacheFile] if it holds a valid cache; otherwise builds them with [build]
     * and, when a [cacheFile] is given, tries to store them there. Never fails because of the file:
     * unreadable or unwritable caches are ignored.
     */
    fun loadOrBuild(cacheFile: File?, build: () -> SolverTables = SolverTables::build): SolverTables {
        cacheFile?.let(::read)?.let { return it }
        val tables = build()
        if (cacheFile != null) tryWrite(tables, cacheFile)
        return tables
    }

    /** Reads the tables, or returns null if the file is missing, stale or damaged in any way. */
    fun read(file: File): SolverTables? {
        if (!file.isFile || file.length() != FILE_BYTES) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                if (input.readLong() != PAYLOAD_BYTES) return null
                val crc = CRC32()
                val chars = CHAR_TABLE_SIZES.map { size ->
                    val raw = ByteArray(size * 2)
                    input.readFully(raw)
                    crc.update(raw)
                    CharArray(size).also { ByteBuffer.wrap(raw).asCharBuffer().get(it) }
                }
                val bytes = BYTE_TABLE_SIZES.map { size ->
                    ByteArray(size).also {
                        input.readFully(it)
                        crc.update(it)
                    }
                }
                if (input.readLong() != crc.value) return null
                if (!inRange(chars, bytes)) return null
                SolverTables(
                    twistMove = chars[0],
                    flipMove = chars[1],
                    sliceSortedMove = chars[2],
                    cornerPermMove = chars[3],
                    udEdgePermMove = chars[4],
                    slicePermMove = chars[5],
                    sliceTwistPrune = bytes[0],
                    sliceFlipPrune = bytes[1],
                    twistFlipPrune = bytes[2],
                    cornerSlicePrune = bytes[3],
                    edgeSlicePrune = bytes[4],
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Cheap check (header and size only) whether [file] looks like a current cache. A damaged
     * payload is still caught by [read].
     */
    fun looksValid(file: File): Boolean {
        if (!file.isFile || file.length() != FILE_BYTES) return false
        return try {
            DataInputStream(FileInputStream(file)).use { input ->
                input.readInt() == MAGIC && input.readInt() == VERSION && input.readLong() == PAYLOAD_BYTES
            }
        } catch (_: IOException) {
            false
        }
    }

    /** Atomically writes [tables] to [file], replacing any existing file. */
    fun write(tables: SolverTables, file: File) {
        val target = file.absoluteFile
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val temp = File.createTempFile(".${target.name}-", ".tmp", dir)
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream, 1 shl 16))
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(PAYLOAD_BYTES)
                val crc = CRC32()
                for (table in tables.charTables) {
                    val raw = ByteArray(table.size * 2)
                    ByteBuffer.wrap(raw).asCharBuffer().put(table)
                    crc.update(raw)
                    out.write(raw)
                }
                for (table in tables.byteTables) {
                    crc.update(table)
                    out.write(table)
                }
                out.writeLong(crc.value)
                out.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /** Like [write], but a failure only means there is no cache next time. */
    fun tryWrite(tables: SolverTables, file: File): Boolean = try {
        write(tables, file)
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Guards the search against out-of-range indices even if a checksum collision slipped through. */
    private fun inRange(chars: List<CharArray>, bytes: List<ByteArray>): Boolean {
        val coordinateSizes = intArrayOf(
            Coordinates.N_TWIST,
            Coordinates.N_FLIP,
            Coordinates.N_SLICE_SORTED,
            Coordinates.N_PERM8,
            Coordinates.N_PERM8,
            Coordinates.N_SLICE_PERM,
        )
        for ((table, size) in chars.zip(coordinateSizes.toList())) {
            for (c in table) if (c.code >= size) return false
        }
        for (table in bytes) {
            if (table[0] != 0.toByte()) return false
            for (b in table) if (b < 0 || b > MAX_PRUNING_DEPTH) return false
        }
        return true
    }

    private const val MAX_PRUNING_DEPTH = 20
}
