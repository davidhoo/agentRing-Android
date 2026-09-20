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
    private var activeAdvertiseCallback: AdvertiseCallback? = null
    @Volatile
    private var isBleAdvertising = false

    private var lastDataReceivedTime: Long = 0
    private var aclReceiverRegistered = false

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    Log.i(TAG, "ACL disconnected: ${device?.name} [${device?.address}]")
                    disconnectConnectedSocket("蓝牙链路已断开，等待重新连接…")
                    makeDiscoverable()
                    startBleAdvertising()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.i(TAG, "Bluetooth turned ON, restarting listening and advertising")
                        makeDiscoverable()
                        startBleAdvertising()
                        startListening()
                    }
                }
            }
        }
    }

    private var periodicMaintenanceCounter = 0
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                // 1. 已连接状态：检测应用层心跳看门狗（半开连接 / Zombie Socket 防御）
                if (connectedSocket != null) {
                    val now = System.currentTimeMillis()
                    // 超过 60 秒未收到任何报文或心跳，判定远端异常断开
                    if (now - lastDataReceivedTime > 60_000L) {
                        Log.w(TAG, "No data/heartbeat for ${now - lastDataReceivedTime}ms, disconnecting zombie socket")
                        disconnectConnectedSocket("连接超时（60s 无心跳），等待重新连接…")
                        makeDiscoverable()
                        startBleAdvertising()
                    }
                }

                // 2. 确保监听线程存活，若因极端异常终止则自动重启
                if (acceptThread == null || !acceptThread!!.isAlive) {
                    Log.w(TAG, "AcceptThread is dead while isRunning, recovering...")
                    startListening()
                }

                // 3. 仅在未连接状态下周期性（每 2 分钟）刷新可发现模式与 BLE 广播，避免已连接时挤占射频带宽
                if (connectedSocket == null) {
                    periodicMaintenanceCounter++
                    if (periodicMaintenanceCounter >= 8) { // 8 * 15s = 120s
                        periodicMaintenanceCounter = 0
                        makeDiscoverable()
                        startBleAdvertising()
                    }
                } else {
                    periodicMaintenanceCounter = 0
                }

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
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
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
     * 仅在未连接状态下广播；连接后应及时停止，避免占用蓝牙基带射频时间片与泄漏广播实例
     */
    @Synchronized
    private fun startBleAdvertising() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        // 已有经典蓝牙连接时，不进行 BLE 广播，节省射频资源
        if (connectedSocket != null) {
            stopBleAdvertising()
            return
        }

        try {
            if (adapter.isMultipleAdvertisementSupported) {
                // 启动新广播前务必停止旧的，防止 ADVERTISE_FAILED_TOO_MANY_ADVERTISERS (code 2) 实例泄漏
                stopBleAdvertising()

                leAdvertiser = adapter.bluetoothLeAdvertiser
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()

                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build()

                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        Log.i(TAG, "BLE advertising started successfully as $deviceBluetoothName")
                        isBleAdvertising = true
                    }

                    override fun onStartFailure(errorCode: Int) {
                        Log.w(TAG, "BLE advertising failed with code $errorCode")
                        isBleAdvertising = false
                    }
                }
                activeAdvertiseCallback = callback
                leAdvertiser?.startAdvertising(settings, data, callback)
            } else {
                Log.d(TAG, "Multiple advertisement not supported on this chipset")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE advertise exception", e)
        }
    }

    @Synchronized
    private fun stopBleAdvertising() {
        val callback = activeAdvertiseCallback ?: return
        activeAdvertiseCallback = null
        isBleAdvertising = false
        try {
            leAdvertiser?.stopAdvertising(callback)
            Log.d(TAG, "BLE advertising stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BLE advertising", e)
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
            Log.i(TAG, "BT-AcceptThread started")
            while (isRunning) {
                val adapter = bluetoothAdapter ?: break
                if (!adapter.isEnabled) {
                    mainHandler.post {
                        listener.onStateChanged(ConnectionState.BLUETOOTH_OFF, "蓝牙未开启")
                    }
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                var server = serverSocket
                if (server == null) {
                    try {
                        server = try {
                            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                        } catch (e: Exception) {
                            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                        }
                        serverSocket = server
                        mainHandler.post {
                            listener.onStateChanged(ConnectionState.LISTENING, "等待连接… ($deviceBluetoothName)")
                        }
                        Log.i(TAG, "Server socket created successfully on $SERVICE_NAME")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to create server socket: ${e.message}, retrying in 3s")
                        try {
                            Thread.sleep(3000)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                }

                try {
                    val socket = server?.accept() ?: continue
                    Log.i(TAG, "Accepted incoming RFCOMM connection from: ${socket.remoteDevice?.name}")
                    closeQuietly(connectedSocket)
                    manageConnectedSocket(socket)
                } catch (e: IOException) {
                    if (isRunning) {
                        Log.w(TAG, "server.accept() IOException: ${e.message}. Recreating server socket in 1s...")
                        closeQuietly(serverSocket)
                        serverSocket = null
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Unexpected error in accept loop: ${e.message}", e)
                        closeQuietly(serverSocket)
                        serverSocket = null
                        try {
                            Thread.sleep(2000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
            Log.i(TAG, "BT-AcceptThread finished")
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
        stopBleAdvertising() // 连接成功后立即停用 BLE 广播，为经典蓝牙腾出无线带宽与芯片资源

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
                            // 若为心跳帧 ("ping")，仅刷新 lastDataReceivedTime 保活，不触发不必要的界面重新渲染
                            if (payload.type != "ping") {
                                mainHandler.post {
                                    listener.onDataReceived(payload)
                                }
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
                    makeDiscoverable()
                    startBleAdvertising()
                }
            }
        }, "BT-ReadThread").apply { start() }
    }

    fun stop() {
        isRunning = false
        stopBleAdvertising()
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
