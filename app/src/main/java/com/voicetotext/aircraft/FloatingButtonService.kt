package com.voicetotext.aircraft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingButtonService : Service() {

    companion object {
        const val ACTION_SHOW_BUTTON = "com.voicetotext.aircraft.ACTION_SHOW_BUTTON"
        const val ACTION_STOP = "com.voicetotext.aircraft.ACTION_STOP"
        private const val CHANNEL_ID = "voice_cmd_service"
        private const val NOTIF_ID = 1001
        private const val MAX_RECORD_MS = 45_000L   // safety cut-off
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var wifiMonitor: WifiMonitor
    private val whisperClient = WhisperClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isRecording = false
    private var buttonVisible = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startForeground(NOTIF_ID, buildNotification())
        startWifiMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUTTON -> showButton()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) whisperClient.cancel()
        if (::wifiMonitor.isInitialized) wifiMonitor.stop()
        if (buttonVisible && ::floatingView.isInitialized) windowManager.removeView(floatingView)
    }

    // ── WiFi monitoring ───────────────────────────────────────────────────────

    private fun startWifiMonitoring() {
        val targetSsid = Prefs.get(this).getString(Prefs.KEY_SSID, "")?.trim() ?: ""
        wifiMonitor = WifiMonitor(this)

        if (targetSsid.isEmpty()) {
            showButton()
            return
        }

        wifiMonitor.start(targetSsid, object : WifiMonitor.Listener {
            override fun onTargetNetworkConnected(ssid: String) = showButton()
            override fun onTargetNetworkDisconnected() = hideButton()
        })
    }

    // ── Floating window ───────────────────────────────────────────────────────

    private fun showButton() {
        if (buttonVisible) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button_layout, null)
        windowManager.addView(floatingView, params)
        buttonVisible = true
        attachTouchHandler(params)
    }

    private fun hideButton() {
        if (!buttonVisible || !::floatingView.isInitialized) return
        if (isRecording) { whisperClient.cancel(); isRecording = false }
        windowManager.removeView(floatingView)
        buttonVisible = false
    }

    private fun attachTouchHandler(params: WindowManager.LayoutParams) {
        val btn = floatingView.findViewById<ImageButton>(R.id.btn_mic)
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragged = false

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                    if (dragged) {
                        params.x = startX + dx; params.y = startY + dy
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) toggleRecording(); true }
                else -> false
            }
        }
    }

    // ── Recording / transcription ─────────────────────────────────────────────

    private fun toggleRecording() {
        if (isRecording) {
            commitRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        setButtonActive(true)
        showStatus("Recording… tap to send")
        whisperClient.startRecording()

        // Safety cut-off
        mainHandler.postDelayed({
            if (isRecording) commitRecording()
        }, MAX_RECORD_MS)
    }

    private fun commitRecording() {
        isRecording = false
        setButtonActive(false)
        showStatus("Transcribing…")

        val prefs = Prefs.get(this)
        val url = prefs.getString(Prefs.KEY_SERVER_URL, Prefs.DEFAULT_SERVER_URL) ?: Prefs.DEFAULT_SERVER_URL

        whisperClient.stopAndTranscribe(url, object : WhisperClient.Callback {
            override fun onResult(text: String) = mainHandler.post { handleResult(text) }
            override fun onError(msg: String) = mainHandler.post {
                showStatus("Error: $msg")
                autoHideStatus(5000)
            }
        })
    }

    private fun handleResult(text: String) {
        showStatus(text)
        val prefs = Prefs.get(this)
        val ip   = prefs.getString(Prefs.KEY_MULTICAST_IP, Prefs.DEFAULT_IP) ?: Prefs.DEFAULT_IP
        val port = prefs.getInt(Prefs.KEY_MULTICAST_PORT, Prefs.DEFAULT_PORT)
        UdpMulticastSender.send(this, CommandFormatter.format(text), ip, port)
        autoHideStatus(5000)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setButtonActive(active: Boolean) {
        if (!buttonVisible || !::floatingView.isInitialized) return
        floatingView.findViewById<ImageButton>(R.id.btn_mic).setBackgroundResource(
            if (active) R.drawable.mic_button_active_bg else R.drawable.mic_button_bg
        )
    }

    private fun showStatus(text: String) {
        if (!buttonVisible || !::floatingView.isInitialized) return
        floatingView.findViewById<TextView>(R.id.tv_status).apply {
            this.text = text; visibility = View.VISIBLE
        }
    }

    private fun autoHideStatus(delayMs: Long) {
        mainHandler.postDelayed({
            if (buttonVisible && ::floatingView.isInitialized)
                floatingView.findViewById<TextView>(R.id.tv_status).visibility = View.GONE
        }, delayMs)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice Command Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Command Active")
            .setContentText("Tap mic · speak · tap again to send")
            .setSmallIcon(R.drawable.ic_mic_notif)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }
}
