package app.agentring.android.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import app.agentring.android.model.SyncPayload
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 经典蓝牙 RFCOMM (SPP) + 广播 管理器
 * 1. 自动改名为 AgentRing-XX
 * 2. 经典蓝牙设为永久可被发现 (SCAN_MODE_CONNECTABLE_DISCOVERABLE)
 * 3. 启动 BLE 广播方便现代 Mac 蓝牙面板快速扫描发现
 * 4. 监听 SPP 串口连接 (UUID: 00001101-0000-1000-8000-00805F9B34FB)
 * 5. 支持主动向电脑发起配对绑定 (createBond)
 */
class BluetoothServerManager(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "AgentRingBT"
        private const val SERVICE_NAME = "AgentRingDisplay"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    enum class ConnectionState {
        BLUETOOTH_OFF,
        LISTENING,
        CONNECTED,
        ERROR
    }

    interface Listener {
        fun onStateChanged(state: ConnectionState, detail: String)
        fun onDataReceived(payload: SyncPayload)
        fun onDiscoveredMac(device: BluetoothDevice) {}
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    @Volatile
    private var isRunning = false
    private var acceptThread: Thread? = null
    private var readThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var leAdvertiser: BluetoothLeAdvertiser? = null

    private var lastDataReceivedTime: Long = 0
    private var aclReceiverRegistered = false

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Log.i(TAG, "ACL disconnected: ${device?.name} [${device?.address}]")
                disconnectConnectedSocket("蓝牙链路已断开，等待重新连接…")
            }
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isRunning && connectedSocket != null) {
                val now = System.currentTimeMillis()
                if (lastDataReceivedTime > 0 && now - lastDataReceivedTime > 60_000) {
                    Log.w(TAG, "Connection watchdog: no data for 60s, releasing stale socket")
                    disconnectConnectedSocket("连接超时无响应，等待重新连接…")
                }
            }
            if (isRunning) {
                mainHandler.postDelayed(this, 15_000)
            }
        }
    }

    var deviceBluetoothName: String = "AgentRing"
        private set

    fun start() {
        if (bluetoothAdapter == null) {
            listener.onStateChanged(ConnectionState.ERROR, "此设备不支持蓝牙")
            return
        }

        try {
            if (!bluetoothAdapter.isEnabled) {
                bluetoothAdapter.enable()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling bluetooth", e)
        }

        configureDeviceName()
        makeDiscoverable()
        startBleAdvertising()

        if (!aclReceiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            context.registerReceiver(aclReceiver, filter)
            aclReceiverRegistered = true
        }
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, 15_000)

        isRunning = true
        startListening()
    }

    /**
     * 将手机蓝牙名称设置为 AgentRing-XX
     */
    private fun configureDeviceName() {
        val adapter = bluetoothAdapter ?: return
        try {
            val phoneModel = Build.MODEL ?: "Phone"
            val sanitized = phoneModel.replace(Regex("[^a-zA-Z0-9_-]"), "").take(15)
            val desiredName = "AgentRing-$sanitized"

            deviceBluetoothName = desiredName
            adapter.name = desiredName
            Log.i(TAG, "Bluetooth name set to: $desiredName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set bluetooth name", e)
        }
    }

    /**
     * 将经典蓝牙设置为永久可被发现 (SCAN_MODE_CONNECTABLE_DISCOVERABLE)
     */
    private fun makeDiscoverable() {
        val adapter = bluetoothAdapter ?: return
        try {
            val method = adapter.javaClass.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            // 23 = SCAN_MODE_CONNECTABLE_DISCOVERABLE, 0 = 永久可见
            method.invoke(adapter, 23, 0)
            Log.i(TAG, "Classic bluetooth set to discoverable (scanMode=23)")
        } catch (e: Exception) {
            Log.w(TAG, "setScanMode via reflection failed: ${e.message}")
        }
    }

    /**
     * 开启 BLE 广播，让 macOS 蓝牙面板在附近设备中瞬间扫描到
     */
    private fun startBleAdvertising() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val adapter = bluetoothAdapter ?: return

        try {
            if (adapter.isMultipleAdvertisementSupported) {
                leAdvertiser = adapter.bluetoothLeAdvertiser
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()

                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build()

                leAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        Log.i(TAG, "BLE advertising started successfully as $deviceBluetoothName")
                    }

                    override fun onStartFailure(errorCode: Int) {
                        Log.w(TAG, "BLE advertising failed with code $errorCode")
                    }
                })
            } else {
                Log.d(TAG, "Multiple advertisement not supported on this chipset")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE advertise exception", e)
        }
    }

    /**
     * 主动向指定蓝牙设备（例如用户的 Mac）发起配对
     */
    fun pairWithDevice(device: BluetoothDevice) {
        try {
            Log.i(TAG, "Initiating bond with: ${device.name} [${device.address}]")
            val method = device.javaClass.getMethod("createBond")
            method.invoke(device)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bond with ${device.address}", e)
        }
    }

    fun pairWithAddress(address: String) {
        val adapter = bluetoothAdapter ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            pairWithDevice(device)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getRemoteDevice for $address", e)
        }
    }

    @Synchronized
    private fun startListening() {
        if (!isRunning) return

        acceptThread?.interrupt()
        closeQuietly(serverSocket)
        serverSocket = null

        acceptThread = Thread({
            try {
                val adapter = bluetoothAdapter ?: return@Thread
                if (!adapter.isEnabled) {
                    mainHandler.post {
                        listener.onStateChanged(ConnectionState.BLUETOOTH_OFF, "蓝牙未开启")
                    }
                    return@Thread
                }

                val server = try {
                    adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                } catch (e: Exception) {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                }
                serverSocket = server

                mainHandler.post {
                    listener.onStateChanged(ConnectionState.LISTENING, "等待连接… ($deviceBluetoothName)")
                }

                while (isRunning) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    } ?: break

                    Log.i(TAG, "Accepted incoming RFCOMM connection from: ${socket.remoteDevice?.name}")
                    closeQuietly(connectedSocket)
                    manageConnectedSocket(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Listen thread exception: ${e.message}")
                    mainHandler.postDelayed({ startListening() }, 3000)
                }
            }
        }, "BT-AcceptThread").apply { start() }
    }

    private fun disconnectConnectedSocket(reason: String = "连接已断开，等待重新连接…") {
        val s = connectedSocket
        connectedSocket = null
        closeQuietly(s)
        readThread?.interrupt()
        readThread = null
        if (isRunning) {
            mainHandler.post {
                listener.onStateChanged(ConnectionState.LISTENING, reason)
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        connectedSocket = socket
        lastDataReceivedTime = System.currentTimeMillis()
        val deviceName = try {
            socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Mac"
        } catch (e: Exception) {
            "Mac"
        }

        mainHandler.post {
            listener.onStateChanged(ConnectionState.CONNECTED, "已连接：$deviceName")
        }

        readThread?.interrupt()
        readThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
                while (isRunning && socket.isConnected) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    lastDataReceivedTime = System.currentTimeMillis()

                    try {
                        val payload = gson.fromJson(line, SyncPayload::class.java)
                        if (payload != null) {
                            mainHandler.post {
                                listener.onDataReceived(payload)
                            }
                        }
                    } catch (pe: Exception) {
                        Log.e(TAG, "JSON parse error for line: $line", pe)
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Socket closed: ${e.message}")
            } finally {
                closeQuietly(socket)
                if (connectedSocket == socket) {
                    connectedSocket = null
                }
                if (isRunning) {
                    mainHandler.post {
                        listener.onStateChanged(ConnectionState.LISTENING, "连接已断开，等待重新连接…")
                    }
                }
            }
        }, "BT-ReadThread").apply { start() }
    }

    fun stop() {
        isRunning = false
        if (aclReceiverRegistered) {
            try {
                context.unregisterReceiver(aclReceiver)
            } catch (_: Exception) {}
            aclReceiverRegistered = false
        }
        mainHandler.removeCallbacks(watchdogRunnable)

        closeQuietly(serverSocket)
        serverSocket = null
        closeQuietly(connectedSocket)
        connectedSocket = null

        acceptThread?.interrupt()
        acceptThread = null
        readThread?.interrupt()
        readThread = null

        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {}
    }
}
