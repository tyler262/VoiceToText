package com.voicetotext.aircraft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var wifiMonitor: WifiMonitor
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var buttonVisible = false

    // Exposed so external apps can force the button regardless of WiFi
    companion object {
        const val ACTION_SHOW_BUTTON = "com.voicetotext.aircraft.ACTION_SHOW_BUTTON"
        const val ACTION_STOP = "com.voicetotext.aircraft.ACTION_STOP"
        private const val CHANNEL_ID = "voice_cmd_service"
        private const val NOTIF_ID = 1001
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startForeground(NOTIF_ID, buildNotification())
        setupSpeechRecognizer()
        startWifiMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUTTON -> showButton()   // External app: force-show
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wifiMonitor.stop()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (buttonVisible && ::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    // ── WiFi monitoring ───────────────────────────────────────────────────────

    private fun startWifiMonitoring() {
        val prefs = Prefs.get(this)
        val targetSsid = prefs.getString(Prefs.KEY_SSID, "")?.trim() ?: ""

        wifiMonitor = WifiMonitor(this)

        if (targetSsid.isEmpty()) {
            // No SSID configured — always show button
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
            x = 16
            y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button_layout, null)
        windowManager.addView(floatingView, params)
        buttonVisible = true
        attachTouchHandler(params)
    }

    private fun hideButton() {
        if (!buttonVisible || !::floatingView.isInitialized) return
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
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                    if (dragged) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleListening()
                    true
                }
                else -> false
            }
        }
    }

    // ── Speech recognition ────────────────────────────────────────────────────

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = setListening(true)
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onPartialResults(r: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                handleVoiceResult(text)
            }

            override fun onError(error: Int) {
                setListening(false)
                showStatus(errorLabel(error))
                autoHideStatus(3000)
            }
        })
    }

    private fun toggleListening() {
        if (!::speechRecognizer.isInitialized) return
        if (isListening) {
            speechRecognizer.stopListening()
            setListening(false)
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer.startListening(intent)
            showStatus("Listening…")
        }
    }

    private fun handleVoiceResult(text: String) {
        setListening(false)
        showStatus(text)

        val prefs = Prefs.get(this)
        val ip = prefs.getString(Prefs.KEY_MULTICAST_IP, Prefs.DEFAULT_IP) ?: Prefs.DEFAULT_IP
        val port = prefs.getInt(Prefs.KEY_MULTICAST_PORT, Prefs.DEFAULT_PORT)

        UdpMulticastSender.send(this, CommandFormatter.format(text), ip, port)
        autoHideStatus(4000)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setListening(listening: Boolean) {
        isListening = listening
        if (!buttonVisible || !::floatingView.isInitialized) return
        val btn = floatingView.findViewById<ImageButton>(R.id.btn_mic)
        btn.setBackgroundResource(
            if (listening) R.drawable.mic_button_active_bg else R.drawable.mic_button_bg
        )
    }

    private fun showStatus(text: String) {
        if (!buttonVisible || !::floatingView.isInitialized) return
        floatingView.findViewById<TextView>(R.id.tv_status).apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun autoHideStatus(delayMs: Long) {
        mainHandler.postDelayed({
            if (buttonVisible && ::floatingView.isInitialized) {
                floatingView.findViewById<TextView>(R.id.tv_status).visibility = View.GONE
            }
        }, delayMs)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice Command Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Command Active")
            .setContentText("Tap the mic button to issue a command")
            .setSmallIcon(R.drawable.ic_mic_notif)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun errorLabel(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout — try again"
        else -> "Error ($code)"
    }
}
