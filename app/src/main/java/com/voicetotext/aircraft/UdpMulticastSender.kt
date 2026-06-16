package com.voicetotext.aircraft

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

object UdpMulticastSender {

    private const val TAG = "UdpMulticast"

    fun send(context: Context, message: String, ip: String, port: Int) {
        Thread {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wm.createMulticastLock("voice_cmd_lock").apply { acquire() }
            try {
                val data = message.toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(ip)
                MulticastSocket().use { socket ->
                    socket.send(DatagramPacket(data, data.size, group, port))
                }
                Log.d(TAG, "Sent ${data.size}B to $ip:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.message}")
            } finally {
                lock.release()
            }
        }.start()
    }
}
