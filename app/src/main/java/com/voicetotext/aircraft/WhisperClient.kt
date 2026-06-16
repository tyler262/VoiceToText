package com.voicetotext.aircraft

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Records PCM audio from the microphone and POSTs it as a WAV file to the
 * faster-whisper HTTP server, returning the transcribed text.
 *
 * Usage:
 *   client.startRecording()
 *   // ... user speaks ...
 *   client.stopAndTranscribe(serverUrl, callback)
 */
class WhisperClient {

    companion object {
        private const val TAG = "WhisperClient"
        private const val SAMPLE_RATE = 16_000
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private val capturedAudio = ByteArrayOutputStream()

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording() {
        capturedAudio.reset()
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        recorder = rec
        recording = true
        rec.startRecording()

        Thread(::drainLoop, "whisper-audio-capture").apply { isDaemon = true; start() }
    }

    private fun drainLoop() {
        val chunk = ByteArray(minBuf)
        while (recording) {
            val read = recorder?.read(chunk, 0, chunk.size) ?: break
            if (read > 0) capturedAudio.write(chunk, 0, read)
        }
    }

    fun stopAndTranscribe(serverUrl: String, callback: Callback) {
        recording = false
        recorder?.stop()
        recorder?.release()
        recorder = null

        val pcm = capturedAudio.toByteArray()
        if (pcm.isEmpty()) {
            callback.onError("No audio captured")
            return
        }

        Thread({
            try {
                val wav = buildWav(pcm, SAMPLE_RATE)
                val text = postWav("$serverUrl/transcribe", wav)
                callback.onResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                callback.onError(e.message ?: "Transcription failed")
            }
        }, "whisper-upload").apply { isDaemon = true; start() }
    }

    fun cancel() {
        recording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────────

    private fun postWav(url: String, wav: ByteArray): String {
        val boundary = "wb_${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        conn.outputStream.use { out ->
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n".toByteArray())
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
        }

        if (code !in 200..299) throw Exception("Server error $code: $body")

        return JSONObject(body).getString("text").trim()
    }

    // ── WAV builder ───────────────────────────────────────────────────────────

    private fun buildWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun i32(v: Int) { repeat(4) { out.write((v shr (it * 8)) and 0xFF) } }
        fun i16(v: Int) { repeat(2) { out.write((v shr (it * 8)) and 0xFF) } }

        out.write("RIFF".toByteArray())
        i32(36 + pcm.size)          // ChunkSize
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        i32(16)                     // Subchunk1Size (PCM)
        i16(1)                      // AudioFormat: PCM
        i16(1)                      // NumChannels: mono
        i32(sampleRate)
        i32(sampleRate * 2)         // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        i16(2)                      // BlockAlign
        i16(16)                     // BitsPerSample
        out.write("data".toByteArray())
        i32(pcm.size)
        out.write(pcm)

        return out.toByteArray()
    }
}
