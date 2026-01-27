package org.fossify.voicerecorder.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Helper class to manage Bluetooth SCO (Synchronous Connection-Oriented) audio
 * for recording from Bluetooth microphones.
 */
class BluetoothHelper(private val context: Context) {
    companion object {
        private const val SCO_TIMEOUT_MS = 5000L
        @Volatile
        private var cachedDeviceName: String? = null

        /**
         * Get the name of the connected Bluetooth audio device (headset/earbuds).
         * Returns null if no device is connected or permission is missing.
         */
        @SuppressLint("MissingPermission")
        fun getConnectedBluetoothDeviceName(context: Context): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return null
                }
            }

            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter ?: return null

            if (!bluetoothAdapter.isEnabled) return null

            // Try to get actively connected headset device via profile proxy
            bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        cachedDeviceName = connectedDevices.first().name
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)

            // Also check A2DP for connected audio devices
            bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty() && cachedDeviceName == null) {
                        cachedDeviceName = connectedDevices.first().name
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)

            // Return cached name if available
            return cachedDeviceName
        }

        /**
         * Refresh the cached device name (call this to update after connection changes)
         */
        fun refreshDeviceName(context: Context) {
            cachedDeviceName = null
            getConnectedBluetoothDeviceName(context)
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var scoReceiver: BroadcastReceiver? = null
    private var isBluetoothScoOn = false
    private var onScoConnected: (() -> Unit)? = null
    private var onScoFailed: (() -> Unit)? = null
    private var callbackInvoked = false
    private var timeoutRunnable: Runnable? = null

    /**
     * Check if Bluetooth is available and a headset is connected.
     */
    fun isBluetoothAvailable(): Boolean {
        if (!hasBluetoothPermission()) {
            return false
        }

        val bluetoothAdapter = getBluetoothAdapter() ?: return false
        if (!bluetoothAdapter.isEnabled) {
            return false
        }

        return audioManager.isBluetoothScoAvailableOffCall
    }

    /**
     * Get the connected Bluetooth device name.
     */
    fun getConnectedDeviceName(): String? {
        return getConnectedBluetoothDeviceName(context)
    }

    /**
     * Start Bluetooth SCO audio connection.
     * @param onConnected Callback when SCO is successfully connected
     * @param onFailed Callback when SCO connection fails
     */
    fun startBluetoothSco(onConnected: () -> Unit, onFailed: () -> Unit) {
        if (!isBluetoothAvailable()) {
            onFailed()
            return
        }

        this.onScoConnected = onConnected
        this.onScoFailed = onFailed
        this.callbackInvoked = false

        registerScoReceiver()

        // Set timeout for SCO connection
        timeoutRunnable = Runnable {
            if (!callbackInvoked) {
                callbackInvoked = true
                // Timeout - but SCO might still connect, so proceed anyway
                onScoConnected?.invoke()
            }
        }
        handler.postDelayed(timeoutRunnable!!, SCO_TIMEOUT_MS)

        try {
            audioManager.startBluetoothSco()
        } catch (e: Exception) {
            cancelTimeout()
            unregisterScoReceiver()
            if (!callbackInvoked) {
                callbackInvoked = true
                onFailed()
            }
        }
    }

    /**
     * Stop Bluetooth SCO audio connection.
     */
    fun stopBluetoothSco() {
        cancelTimeout()
        if (isBluetoothScoOn) {
            try {
                audioManager.stopBluetoothSco()
            } catch (_: Exception) {
            }
            isBluetoothScoOn = false
        }
        unregisterScoReceiver()
        onScoConnected = null
        onScoFailed = null
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun registerScoReceiver() {
        if (scoReceiver != null) return

        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    handleScoStateChange(state)
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scoReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(scoReceiver, filter)
        }
    }

    private fun unregisterScoReceiver() {
        scoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            scoReceiver = null
        }
    }

    private fun handleScoStateChange(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                isBluetoothScoOn = true
                cancelTimeout()
                if (!callbackInvoked) {
                    callbackInvoked = true
                    onScoConnected?.invoke()
                }
            }
            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                cancelTimeout()
                if (!callbackInvoked) {
                    callbackInvoked = true
                    onScoFailed?.invoke()
                }
                isBluetoothScoOn = false
            }
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                // Don't treat disconnect as failure during initial connection
                // SCO sometimes sends disconnect before connect
                isBluetoothScoOn = false
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        return (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
}
