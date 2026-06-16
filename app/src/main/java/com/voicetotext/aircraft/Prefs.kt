package com.voicetotext.aircraft

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "voice_command_prefs"
    const val KEY_SSID = "target_ssid"
    const val KEY_MULTICAST_IP = "multicast_ip"
    const val KEY_MULTICAST_PORT = "multicast_port"
    const val KEY_AUTO_START = "auto_start"
    const val KEY_SERVER_URL = "whisper_server_url"

    const val DEFAULT_IP = "239.255.0.1"
    const val DEFAULT_PORT = 5005
    const val DEFAULT_SERVER_URL = "http://192.168.1.100:8765"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
