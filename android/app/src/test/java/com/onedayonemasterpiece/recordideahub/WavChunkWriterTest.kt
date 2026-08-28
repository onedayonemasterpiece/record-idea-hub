package com.onedayonemasterpiece.recordideahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WavChunkWriterTest {
    @Test
    fun writesRecoverableMonoPcmWav() {
        val directory = Files.createTempDirectory("record-idea-hub-wav").toFile()
        val writer = WavChunkWriter(
            directory = directory,
            sessionId = "voice-20260827-test0001",
            chunkIndex = 0,
            startMs = 0,
        )
        writer.write(ByteArray(32_000), 32_000)
        val chunk = requireNotNull(writer.close())
        val bytes = chunk.file.readBytes()
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
        assertEquals(1_000L, chunk.endMs)
        assertEquals(64, chunk.sha256.length)
        assertTrue(chunk.file.name.endsWith(".wav"))
    }
}
