package app.agentring.android.ui

import android.Manifest
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
import app.agentring.android.databinding.ItemProviderCardBinding
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

        // 4. 需求：程序运行后，屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 启用全屏沉浸体验
        enableImmersiveMode()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BluetoothServerManager(this, this)
        binding.deviceBtNameView.text = bluetoothManager?.deviceBluetoothName ?: "AgentRing"

        checkAndRequestPermissions()
        handlePairIntent(intent)
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
            binding.cardsHorizontalScrollView.visibility = View.GONE
            binding.cardsVerticalScrollView.visibility = View.GONE
            return
        }

        binding.emptyStateLayout.visibility = View.GONE
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
     * 5. 需求：界面只显示agentRing dashboard 的那样，配置了几个，就显示几个，自适应布局。
     */
    private fun renderDashboard(providers: List<ProviderData>) {
        cachedProviders = providers
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val count = providers.size

        if (isLandscape) {
            binding.cardsVerticalScrollView.visibility = View.GONE
            binding.cardsHorizontalScrollView.visibility = View.VISIBLE
            val container = binding.cardsHorizontalContainer
            container.removeAllViews()

            for (provider in providers) {
                val cardBinding = ItemProviderCardBinding.inflate(layoutInflater, container, false)
                bindProviderCard(cardBinding, provider, count, isLandscape = true)
                container.addView(cardBinding.root)
            }
        } else {
            binding.cardsHorizontalScrollView.visibility = View.GONE
            binding.cardsVerticalScrollView.visibility = View.VISIBLE
            val container = binding.cardsVerticalContainer
            container.removeAllViews()

            for (provider in providers) {
                val cardBinding = ItemProviderCardBinding.inflate(layoutInflater, container, false)
                bindProviderCard(cardBinding, provider, count, isLandscape = false)
                container.addView(cardBinding.root)
            }
        }
    }

    private fun bindProviderCard(
        cardBinding: ItemProviderCardBinding,
        provider: ProviderData,
        totalCount: Int,
        isLandscape: Boolean
    ) {
        val lp = cardBinding.root.layoutParams as LinearLayout.LayoutParams

        if (isLandscape) {
            cardBinding.ringFrame.layoutParams.height = dpToPx(110)
            when (totalCount) {
                1 -> {
                    lp.width = dpToPx(380)
                    lp.weight = 0f
                }
                2 -> {
                    lp.width = dpToPx(320)
                    lp.weight = 0f
                }
                else -> {
                    lp.width = dpToPx(270)
                    lp.weight = 0f
                }
            }
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            // 竖屏
            cardBinding.ringFrame.layoutParams.height = dpToPx(150)
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
        }
        cardBinding.root.layoutParams = lp

        cardBinding.providerName.text = provider.name

        // 配色与图标指示条
        val (primaryColor, secondaryColor) = getProviderColors(provider.id)
        cardBinding.providerColorIndicator.setBackgroundColor(primaryColor)

        // 2. 需求：只用来显示剩余额度
        val primary = provider.primary
        val secondary = provider.secondary

        val primaryPercent = primary?.remainingPercent ?: 100.0
        val secondaryPercent = secondary?.remainingPercent

        cardBinding.activityRingView.setColors(primaryColor, secondaryColor)
        cardBinding.activityRingView.setValues(primaryPercent, secondaryPercent, animate = true)

        // 主窗口描述
        val primaryDesc = primary?.label ?: "主窗口"
        val primaryDetails = primary?.remainingDetails
        if (!primaryDetails.isNullOrBlank()) {
            cardBinding.primaryLabel.text = "$primaryDesc 剩余 $primaryDetails"
        } else {
            cardBinding.primaryLabel.text = "$primaryDesc 剩余 ${primaryPercent.toInt()}%"
        }

        // 重置倒计时
        val resetStr = primary?.resetsAt
        if (!resetStr.isNullOrBlank()) {
            cardBinding.resetCountdown.visibility = View.VISIBLE
            cardBinding.resetCountdown.text = getString(R.string.resets_in, resetStr)
        } else {
            cardBinding.resetCountdown.visibility = View.GONE
        }

        // 次级窗口（7天周级 / API 等）
        if (secondary != null) {
            cardBinding.secondaryLayout.visibility = View.VISIBLE
            val secLabel = secondary.label ?: "周级窗口"
            cardBinding.secondaryLabel.text = "$secLabel 剩余"
            if (!secondary.remainingDetails.isNullOrBlank()) {
                cardBinding.secondaryPercent.text = secondary.remainingDetails
            } else {
                cardBinding.secondaryPercent.text = "${secondaryPercent?.toInt() ?: 0}%"
            }
        } else {
            cardBinding.secondaryLayout.visibility = View.GONE
        }

        // 额外信息（Credits 余额等）
        if (!provider.extraInfo.isNullOrBlank()) {
            cardBinding.extraInfo.visibility = View.VISIBLE
            cardBinding.extraInfo.text = provider.extraInfo
        } else {
            cardBinding.extraInfo.visibility = View.GONE
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
