package com.voicetotext.aircraft

/**
 * Formats a raw voice transcription into a UDP payload.
 * Currently emits a simple JSON envelope; command parsing logic to be added later.
 */
object CommandFormatter {

    fun format(voiceText: String): String {
        val ts = System.currentTimeMillis()
        val escaped = voiceText.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"type":"voice_command","text":"$escaped","timestamp":$ts}"""
    }
}
