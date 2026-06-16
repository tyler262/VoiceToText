package com.voicetotext.aircraft

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voicetotext.aircraft.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiMonitor: WifiMonitor

    private val runtimePermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionStatus() }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionStatus() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupListeners()
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        startWifiMonitoring()
    }

    override fun onPause() {
        super.onPause()
        if (::wifiMonitor.isInitialized) wifiMonitor.stop()
    }

    // ── Settings I/O ──────────────────────────────────────────────────────────

    private fun loadSettings() {
        val prefs = Prefs.get(this)
        binding.etSsid.setText(prefs.getString(Prefs.KEY_SSID, ""))
        binding.etMulticastIp.setText(prefs.getString(Prefs.KEY_MULTICAST_IP, Prefs.DEFAULT_IP))
        binding.etPort.setText(prefs.getInt(Prefs.KEY_MULTICAST_PORT, Prefs.DEFAULT_PORT).toString())
        binding.switchAutoStart.isChecked = prefs.getBoolean(Prefs.KEY_AUTO_START, false)
    }

    private fun saveSettings() {
        val ssid = binding.etSsid.text?.toString()?.trim() ?: ""
        val ip = binding.etMulticastIp.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: Prefs.DEFAULT_IP
        val port = binding.etPort.text?.toString()?.trim()?.toIntOrNull() ?: Prefs.DEFAULT_PORT

        Prefs.get(this).edit()
            .putString(Prefs.KEY_SSID, ssid)
            .putString(Prefs.KEY_MULTICAST_IP, ip)
            .putInt(Prefs.KEY_MULTICAST_PORT, port)
            .putBoolean(Prefs.KEY_AUTO_START, binding.switchAutoStart.isChecked)
            .apply()

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        // Restart WiFi monitoring with new SSID
        if (::wifiMonitor.isInitialized) wifiMonitor.stop()
        startWifiMonitoring()
    }

    // ── WiFi monitoring ───────────────────────────────────────────────────────

    private fun startWifiMonitoring() {
        val targetSsid = Prefs.get(this).getString(Prefs.KEY_SSID, "")?.trim() ?: ""
        wifiMonitor = WifiMonitor(this)

        val currentSsid = wifiMonitor.getCurrentSsid() ?: "Not connected"
        binding.tvWifiStatus.text = "WiFi: $currentSsid"

        if (targetSsid.isBlank()) {
            binding.tvNetworkMatch.text = "No target network configured"
            return
        }

        wifiMonitor.start(targetSsid, object : WifiMonitor.Listener {
            override fun onTargetNetworkConnected(ssid: String) {
                binding.tvWifiStatus.text = "WiFi: $ssid ✓"
                binding.tvNetworkMatch.text = "On target network — button active"
                if (binding.switchAutoStart.isChecked) startFloatingService()
            }
            override fun onTargetNetworkDisconnected() {
                binding.tvWifiStatus.text = "WiFi: disconnected"
                binding.tvNetworkMatch.text = "Not on target network"
            }
        })
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnGrantPermissions.setOnClickListener { requestAllPermissions() }
        binding.btnToggleService.setOnClickListener { toggleService() }
    }

    private fun toggleService() {
        if (isServiceRunning()) {
            stopService(Intent(this, FloatingButtonService::class.java))
            binding.btnToggleService.text = "Start Floating Button"
        } else {
            if (!allPermissionsGranted()) {
                Toast.makeText(this, "Grant all permissions first", Toast.LENGTH_SHORT).show()
                return
            }
            startFloatingService()
            binding.btnToggleService.text = "Stop Floating Button"
        }
    }

    private fun startFloatingService() {
        if (isServiceRunning()) return
        val intent = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.btnToggleService.text = "Stop Floating Button"
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestAllPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
        val missing = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshPermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val locationOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        binding.tvOverlayPermission.text = "• Overlay (float on other apps): ${status(overlayOk)}"
        binding.tvMicPermission.text = "• Microphone: ${status(micOk)}"
        binding.tvLocationPermission.text = "• Location (read WiFi SSID): ${status(locationOk)}"

        val allOk = overlayOk && micOk && locationOk
        binding.btnGrantPermissions.isEnabled = !allOk
        binding.btnGrantPermissions.text = if (allOk) "All permissions granted" else "Grant Permissions"
        binding.btnToggleService.isEnabled = allOk
    }

    private fun allPermissionsGranted(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        return runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isServiceRunning() = FloatingButtonService::class.java.let { cls ->
        (getSystemService(android.app.ActivityManager::class.java))
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == cls.name }
    }

    private fun status(ok: Boolean) = if (ok) "✓ Granted" else "✗ Denied"
}
