package app.agentring.android.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.agentring.android.R
import app.agentring.android.databinding.ActivityMainBinding
import app.agentring.android.databinding.ItemProviderColumnBinding
import app.agentring.android.model.ProviderData
import app.agentring.android.model.SyncPayload
import app.agentring.android.service.BluetoothServerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BluetoothServerManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothManager: BluetoothServerManager? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 需求：启动后，开启横屏模式
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // 4. 需求：程序运行后，屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 启用全屏沉浸体验
        enableImmersiveMode()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BluetoothServerManager(this, this)
        binding.deviceBtNameView.text = bluetoothManager?.deviceBluetoothName ?: "AgentRing"

        registerReceiver(mockDataReceiver, android.content.IntentFilter("app.agentring.android.MOCK_DATA"))

        checkAndRequestPermissions()
        handlePairIntent(intent)
    }

    private val mockDataReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val json = intent?.getStringExtra("payload") ?: return
            try {
                val payload = com.google.gson.Gson().fromJson(json, SyncPayload::class.java)
                if (payload != null) {
                    onDataReceived(payload)
                }
            } catch (e: Exception) {
                android.util.Log.e("AgentRingBT", "Mock data error", e)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    private fun handlePairIntent(intent: android.content.Intent?) {
        val targetMac = intent?.getStringExtra("target_mac")
        if (!targetMac.isNullOrBlank()) {
            Toast.makeText(this, "正在向电脑发起配对: $targetMac", Toast.LENGTH_SHORT).show()
            bluetoothManager?.pairWithAddress(targetMac)
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothManager?.stop()
        try {
            unregisterReceiver(mockDataReceiver)
        } catch (_: Exception) {}
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun checkAndRequestPermissions() {
        // Android 5.0.2 (API 21) 在安装时即授予权限；Android 12+ (API 31+) 需要动态申请蓝牙连接权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }

            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
                return
            }
        }

        bluetoothManager?.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bluetoothManager?.start()
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
                bluetoothManager?.start()
            }
        }
    }

    // MARK: - BluetoothServerManager.Listener

    override fun onStateChanged(state: BluetoothServerManager.ConnectionState, detail: String) {
        binding.connectionStatusText.text = detail
        binding.deviceBtNameView.text = bluetoothManager?.deviceBluetoothName ?: "AgentRing"

        when (state) {
            BluetoothServerManager.ConnectionState.CONNECTED -> {
                binding.connectionStatusDot.setBackgroundResource(R.drawable.bg_status_dot)
            }
            BluetoothServerManager.ConnectionState.LISTENING -> {
                // 等待连接状态黄色或蓝色
                binding.connectionStatusDot.setBackgroundColor(Color.parseColor("#3B82F6"))
            }
            BluetoothServerManager.ConnectionState.BLUETOOTH_OFF,
            BluetoothServerManager.ConnectionState.ERROR -> {
                binding.connectionStatusDot.setBackgroundColor(Color.parseColor("#EF4444"))
            }
        }
    }

    override fun onDiscoveredMac(device: android.bluetooth.BluetoothDevice) {
        val macName = device.name ?: "Mac"
        binding.btnPairMac.visibility = View.VISIBLE
        binding.btnPairMac.text = "点击配对：$macName"
        binding.btnPairMac.setOnClickListener {
            Toast.makeText(this, "正在向 $macName 发送配对请求，请在 Mac 上确认", Toast.LENGTH_SHORT).show()
            bluetoothManager?.pairWithDevice(device)
        }
    }

    private var cachedProviders: List<ProviderData> = emptyList()

    override fun onDataReceived(payload: SyncPayload) {
        val providers = payload.providers
        if (providers.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.columnsContainer.visibility = View.GONE
            return
        }

        binding.emptyStateLayout.visibility = View.GONE
        binding.columnsContainer.visibility = View.VISIBLE
        binding.lastUpdatedText.text = getString(R.string.last_updated, timeFormat.format(Date()))

        renderDashboard(providers)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cachedProviders.isNotEmpty()) {
            renderDashboard(cachedProviders)
        }
    }

    /**
     * 需求：不要卡片模式，直接绘图分割，不需要交互，全部横向列在界面上，一屏展示所有信息。
     * 根据项目多少决定环的大小。
     */
    private fun renderDashboard(providers: List<ProviderData>) {
        cachedProviders = providers
        val container = binding.columnsContainer
        container.removeAllViews()

        val count = providers.size
        // 根据项目多少决定环的高度/尺寸：
        // 1项 -> 大环 (~150dp)
        // 2项 -> 中大环 (~130dp)
        // 3项 -> 中环 (~110dp)
        // 4项及以上 -> 紧凑环 (~92dp)
        val ringHeightDp = when (count) {
            1 -> 150
            2 -> 130
            3 -> 110
            else -> 92
        }

        for (i in providers.indices) {
            val provider = providers[i]

            // 厂商列之间的直接细分割线（仿 macOS agentRing ProviderDivider）
            if (i > 0) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        val marginV = dpToPx(14)
                        topMargin = marginV
                        bottomMargin = marginV
                    }
                    setBackgroundColor(Color.parseColor("#26FFFFFF"))
                }
                container.addView(divider)
            }

            val columnBinding = ItemProviderColumnBinding.inflate(layoutInflater, container, false)
            bindProviderColumn(columnBinding, provider, count, ringHeightDp)
            container.addView(columnBinding.root)
        }
    }

    private fun bindProviderColumn(
        columnBinding: ItemProviderColumnBinding,
        provider: ProviderData,
        totalCount: Int,
        ringHeightDp: Int
    ) {
        val lp = columnBinding.root.layoutParams as LinearLayout.LayoutParams
        if (totalCount == 1) {
            lp.width = dpToPx(380)
            lp.weight = 0f
        } else {
            lp.width = 0
            lp.weight = 1f
        }
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        columnBinding.root.layoutParams = lp

        // 动态根据厂商数量设定环高度
        columnBinding.ringContainer.layoutParams.height = dpToPx(ringHeightDp)

        // 厂商名称与指示色
        columnBinding.providerName.text = provider.name
        val (primaryColor, secondaryColor) = getProviderColors(provider.id)
        columnBinding.providerColorIndicator.setBackgroundColor(primaryColor)

        // 剩余额度圆环数值与顺滑动画
        val primary = provider.primary
        val secondary = provider.secondary

        val primaryPercent = primary?.remainingPercent ?: 100.0
        val secondaryPercent = secondary?.remainingPercent

        columnBinding.activityRingView.setColors(primaryColor, secondaryColor)
        columnBinding.activityRingView.setValues(primaryPercent, secondaryPercent, animate = true)

        // 主窗口描述与详情
        val primaryDesc = primary?.label ?: "主窗口"
        val primaryDetails = primary?.remainingDetails
        if (!primaryDetails.isNullOrBlank()) {
            columnBinding.primaryLabel.text = "$primaryDesc 剩余 $primaryDetails"
        } else {
            columnBinding.primaryLabel.text = "$primaryDesc 剩余 ${primaryPercent.toInt()}%"
        }

        // 重置倒计时
        val resetStr = primary?.resetsAt
        if (!resetStr.isNullOrBlank()) {
            columnBinding.resetCountdown.visibility = View.VISIBLE
            columnBinding.resetCountdown.text = getString(R.string.resets_in, resetStr)
        } else {
            columnBinding.resetCountdown.visibility = View.GONE
        }

        // 次级窗口（7天周级 / API 等）
        if (secondary != null) {
            columnBinding.secondaryLayout.visibility = View.VISIBLE
            val secLabel = secondary.label ?: "周级窗口"
            columnBinding.secondaryLabel.text = "$secLabel 剩余"
            if (!secondary.remainingDetails.isNullOrBlank()) {
                columnBinding.secondaryPercent.text = secondary.remainingDetails
            } else {
                columnBinding.secondaryPercent.text = "${secondaryPercent?.toInt() ?: 0}%"
            }
        } else {
            columnBinding.secondaryLayout.visibility = View.GONE
        }

        // 额外信息（Credits 余额等）
        if (!provider.extraInfo.isNullOrBlank()) {
            columnBinding.extraInfo.visibility = View.VISIBLE
            columnBinding.extraInfo.text = provider.extraInfo
        } else {
            columnBinding.extraInfo.visibility = View.GONE
        }

        // 紧凑排版微调：当>=3项时适当微调字体，确保单屏绝不截断
        if (totalCount >= 3) {
            columnBinding.providerName.textSize = 15f
            columnBinding.primaryLabel.textSize = 11.5f
            columnBinding.resetCountdown.textSize = 10f
        }
    }

    private fun getProviderColors(id: String): Pair<Int, Int> {
        return when (id.lowercase()) {
            "codex" -> Pair(
                Color.parseColor("#10A37F"),
                Color.parseColor("#059669")
            )
            "cursor" -> Pair(
                Color.parseColor("#3B82F6"),
                Color.parseColor("#8B5CF6")
            )
            "antigravity" -> Pair(
                Color.parseColor("#F59E0B"),
                Color.parseColor("#EC4899")
            )
            else -> Pair(
                Color.parseColor("#3B82F6"),
                Color.parseColor("#10B981")
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
