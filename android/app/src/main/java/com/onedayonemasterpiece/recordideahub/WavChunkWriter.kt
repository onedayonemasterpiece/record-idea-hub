package com.onedayonemasterpiece.recordideahub

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.roundToLong

class WavChunkWriter(
    directory: File,
    val sessionId: String,
    val chunkIndex: Int,
    val startMs: Long,
) {
    private val partFile: File
    private val targetFile: File
    private val file: RandomAccessFile
    private var closed = false
    private var dataBytes = 0L

    init {
        directory.mkdirs()
        val stem = "${sessionId}__${chunkIndex}__${startMs}"
        partFile = File(directory, "$stem.wav.part")
        targetFile = File(directory, "$stem.wav")
        file = RandomAccessFile(partFile, "rw")
        file.setLength(0)
        writeHeader(file, 0)
    }

    val durationMs: Long
        get() = bytesToDuration(dataBytes)

    fun write(buffer: ByteArray, count: Int) {
        check(!closed) { "writer is closed" }
        require(count in 0..buffer.size)
        file.write(buffer, 0, count)
        dataBytes += count
    }

    fun close(): ClosedChunk? {
        if (closed) return null
        closed = true
        if (dataBytes <= 0) {
            file.close()
            partFile.delete()
            return null
        }
        file.seek(0)
        writeHeader(file, dataBytes)
        file.fd.sync()
        file.close()
        check(partFile.renameTo(targetFile)) { "failed to finalize WAV chunk" }
        return ClosedChunk(
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            startMs = startMs,
            endMs = startMs + bytesToDuration(dataBytes),
            file = targetFile,
            sha256 = sha256(targetFile),
        )
    }

    data class ClosedChunk(
        val sessionId: String,
        val chunkIndex: Int,
        val startMs: Long,
        val endMs: Long,
        val file: File,
        val sha256: String,
    )

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private const val HEADER_BYTES = 44L
        private val PART_PATTERN = Regex("^(.+)__(\\d+)__(\\d+)\\.wav\\.part$")

        fun recoverPartials(directory: File): List<ClosedChunk> {
            if (!directory.isDirectory) return emptyList()
            val recovered = mutableListOf<ClosedChunk>()
            directory.listFiles { file -> file.name.endsWith(".wav.part") }
                ?.sortedBy { it.name }
                ?.forEach { part ->
                    val match = PART_PATTERN.matchEntire(part.name)
                    if (match == null || part.length() <= HEADER_BYTES) {
                        part.delete()
                        return@forEach
                    }
                    val sessionId = match.groupValues[1]
                    val chunkIndex = match.groupValues[2].toInt()
                    val startMs = match.groupValues[3].toLong()
                    val dataBytes = part.length() - HEADER_BYTES
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(0)
                        writeHeader(raf, dataBytes)
                        raf.fd.sync()
                    }
                    val target = File(part.parentFile, part.name.removeSuffix(".part"))
                    if (part.renameTo(target)) {
                        recovered += ClosedChunk(
                            sessionId = sessionId,
                            chunkIndex = chunkIndex,
                            startMs = startMs,
                            endMs = startMs + bytesToDuration(dataBytes),
                            file = target,
                            sha256 = sha256(target),
                        )
                    }
                }
            return recovered
        }

        private fun bytesToDuration(bytes: Long): Long {
            val bytesPerSecond = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
            return (bytes.toDouble() * 1000.0 / bytesPerSecond).roundToLong()
        }

        private fun writeHeader(file: RandomAccessFile, dataBytes: Long) {
            val byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
            val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)
            file.writeBytes("RIFF")
            writeIntLE(file, (36L + dataBytes).toInt())
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            writeIntLE(file, 16)
            writeShortLE(file, 1)
            writeShortLE(file, CHANNELS)
            writeIntLE(file, SAMPLE_RATE)
            writeIntLE(file, byteRate)
            writeShortLE(file, blockAlign)
            writeShortLE(file, BITS_PER_SAMPLE)
            file.writeBytes("data")
            writeIntLE(file, dataBytes.toInt())
        }

        private fun writeIntLE(file: RandomAccessFile, value: Int) {
            file.write(value and 0xff)
            file.write((value ushr 8) and 0xff)
            file.write((value ushr 16) and 0xff)
            file.write((value ushr 24) and 0xff)
        }

        private fun writeShortLE(file: RandomAccessFile, value: Int) {
            file.write(value and 0xff)
            file.write((value ushr 8) and 0xff)
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
