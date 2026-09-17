package app.agentring.android.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
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

        // 1. 启动后，强制横屏模式
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // 2. 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 3. 全屏沉浸体验
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
            bluetoothManager?.start()
        }
    }

    // MARK: - BluetoothServerManager.Listener

    override fun onStateChanged(state: BluetoothServerManager.ConnectionState, detail: String) {
        binding.connectionStatusText.text = detail
        binding.deviceBtNameView.text = bluetoothManager?.deviceBluetoothName ?: "AgentRing"

        when (state) {
            BluetoothServerManager.ConnectionState.CONNECTED -> {
                setDotColor(R.color.status_green)
            }
            BluetoothServerManager.ConnectionState.LISTENING -> {
                setDotColor(R.color.status_blue)
            }
            BluetoothServerManager.ConnectionState.BLUETOOTH_OFF,
            BluetoothServerManager.ConnectionState.ERROR -> {
                setDotColor(R.color.status_red)
            }
        }
    }

    private fun setDotColor(colorRes: Int) {
        val drawable = binding.connectionStatusDot.background as? android.graphics.drawable.GradientDrawable
        if (drawable != null) {
            drawable.setColor(ContextCompat.getColor(this, colorRes))
        } else {
            binding.connectionStatusDot.setBackgroundColor(ContextCompat.getColor(this, colorRes))
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
    private val activeColumnBindings = mutableListOf<ItemProviderColumnBinding>()

    override fun onDataReceived(payload: SyncPayload) {
        val providers = payload.providers
        if (providers.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.columnsContainer.visibility = View.GONE
            cachedProviders = emptyList()
            activeColumnBindings.clear()
            return
        }

        binding.emptyStateLayout.visibility = View.GONE
        binding.columnsContainer.visibility = View.VISIBLE

        // 状态判断：副屏作为展示设备，只有跟上次数据产生实质变化时，才更新界面与动效
        if (cachedProviders.isNotEmpty() && providers == cachedProviders) {
            // 数据未发生任何变化，无需重刷界面，不重跑动画，直接跳过
            return
        }

        binding.lastUpdatedText.text = getString(R.string.last_updated, timeFormat.format(Date()))
        renderDashboard(providers)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cachedProviders.isNotEmpty()) {
            // 屏幕旋转时强制重新构建视图排版
            activeColumnBindings.clear()
            renderDashboard(cachedProviders)
        }
    }

    /**
     * 需求：不要卡片模式，直接绘图分割，不需要交互，全部横向列在界面上，一屏展示所有信息。
     * 根据项目多少决定环的大小，动态缩放以适应屏幕。
     * 支持列视图直接复用：避免全屏 removeAllViews 导致的重绘闪烁与圆环从 0 冲顶。
     */
    private fun renderDashboard(providers: List<ProviderData>) {
        val container = binding.columnsContainer
        val count = providers.size

        val ringHeightDp = when (count) {
            1 -> 140
            2 -> 120
            3 -> 105
            else -> 92
        }

        // 检查已有列结构是否可直接复用（列数量与厂商 ID 顺序一致）
        val canReuseViews = activeColumnBindings.size == count &&
            cachedProviders.map { it.id } == providers.map { it.id }

        if (!canReuseViews) {
            container.removeAllViews()
            activeColumnBindings.clear()

            for (i in providers.indices) {
                val provider = providers[i]

                // 厂商列之间的细垂直分割线（仿 macOS agentRing ProviderDivider）
                if (i > 0) {
                    val divider = View(this).apply {
                        val marginH = if (count >= 4) dpToPx(2) else dpToPx(4)
                        layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(190)).apply {
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            leftMargin = marginH
                            rightMargin = marginH
                        }
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider_line))
                    }
                    container.addView(divider)
                }

                val columnBinding = ItemProviderColumnBinding.inflate(layoutInflater, container, false)
                activeColumnBindings.add(columnBinding)
                bindProviderColumn(columnBinding, provider, count, ringHeightDp)
                container.addView(columnBinding.root)
            }
        } else {
            // 列视图结构未变，原地增量更新数值与动画，圆环平滑从当前角度弹簧过渡到新角度
            for (i in providers.indices) {
                bindProviderColumn(activeColumnBindings[i], providers[i], count, ringHeightDp)
            }
        }

        cachedProviders = providers
    }

    private fun bindProviderColumn(
        columnBinding: ItemProviderColumnBinding,
        provider: ProviderData,
        totalCount: Int,
        ringHeightDp: Int
    ) {
        val lp = columnBinding.root.layoutParams as LinearLayout.LayoutParams
        lp.width = 0
        lp.weight = 1f
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = android.view.Gravity.TOP
        columnBinding.root.layoutParams = lp

        // 动态根据厂商数量设定环高度
        columnBinding.ringContainer.layoutParams.height = dpToPx(ringHeightDp)

        // 厂商名称
        columnBinding.providerName.text = provider.name

        val primary = provider.primary
        val secondary = provider.secondary
        val primaryPercent = primary?.remainingPercent ?: 100.0
        val secondaryPercent = secondary?.remainingPercent
        val hasSecondary = (secondaryPercent != null)

        val (primaryColor, secondaryColor) = getProviderColors(provider.id, hasSecondary)

        // 剩余额度圆环数值与顺滑动画
        columnBinding.activityRingView.setColors(primaryColor, secondaryColor)
        columnBinding.activityRingView.setValues(primaryPercent, secondaryPercent, animate = true)

        // 明细行列表：完全由服务端下发的 rows 动态渲染（扁平列表、三列固定通道、行间极细分割线、额度告急分级变色）
        columnBinding.rowsContainer.removeAllViews()

        val rows = provider.rows
        if (!rows.isNullOrEmpty()) {
            for (index in rows.indices) {
                val rowItem = rows[index]

                // 行间 1dp 浅灰分割线（仿 macOS agentRing limitRows）
                if (index > 0) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(1)
                        ).apply {
                            val marginH = dpToPx(4)
                            leftMargin = marginH
                            rightMargin = marginH
                            topMargin = dpToPx(1)
                            bottomMargin = dpToPx(1)
                        }
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.row_divider))
                    }
                    columnBinding.rowsContainer.addView(divider)
                }

                val rowBinding = app.agentring.android.databinding.ItemLimitRowBinding.inflate(layoutInflater, columnBinding.rowsContainer, false)
                rowBinding.rowLabel.text = rowItem.label

                // 核心：副屏是剩余监视器，严格展示「剩余量」！
                // 行 0 对应外环 primary，行 1 对应内环 secondary
                // 以 primary/secondary 的剩余量（remainingPercent）为绝对基准，确保大圆环与明细行数值 100% 同源对齐
                val displayPercentText = when {
                    index == 0 && primary?.remainingPercent != null && rowItem.percent.contains("%") -> {
                        "${primary.remainingPercent.toInt()}%"
                    }
                    index == 1 && secondary?.remainingPercent != null && rowItem.percent.contains("%") -> {
                        "${secondary.remainingPercent.toInt()}%"
                    }
                    else -> rowItem.percent
                }

                rowBinding.rowPercent.text = displayPercentText

                // 百分比额度告急分级变色（≤5% 红色紧急，≤20% 橙色警告，其余常规）
                val urgencyColor = getUrgencyColor(displayPercentText)
                rowBinding.rowPercent.setTextColor(urgencyColor)

                // 重置时间/额度（空时保持占位，确保数值通道垂直严格对齐不漂移）
                val resetText = rowItem.reset
                if (!resetText.isNullOrBlank()) {
                    rowBinding.rowReset.visibility = View.VISIBLE
                    rowBinding.rowReset.text = resetText
                } else {
                    rowBinding.rowReset.visibility = View.INVISIBLE
                    rowBinding.rowReset.text = ""
                }

                // 自适应字号
                when {
                    totalCount >= 4 -> {
                        rowBinding.rowLabel.textSize = 9.5f
                        rowBinding.rowPercent.textSize = 10f
                        rowBinding.rowReset.textSize = 9.5f
                    }
                    else -> {
                        rowBinding.rowLabel.textSize = 11f
                        rowBinding.rowPercent.textSize = 11.5f
                        rowBinding.rowReset.textSize = 11f
                    }
                }
                columnBinding.rowsContainer.addView(rowBinding.root)
            }
        }

        // 厂商标题字号缩放
        when {
            totalCount >= 4 -> columnBinding.providerName.textSize = 13f
            else -> columnBinding.providerName.textSize = 14f
        }
    }

    /**
     * 根据剩余百分比计算告急分级颜色（与 macOS UsageRingDisplay.UrgencyLevel 一致）
     */
    private fun getUrgencyColor(percentStr: String): Int {
        val digits = percentStr.trim().removeSuffix("%").trim()
        val percentVal = digits.toDoubleOrNull()
        return if (percentVal != null) {
            when {
                percentVal <= 5.0 -> ContextCompat.getColor(this, R.color.urgency_critical)
                percentVal <= 20.0 -> ContextCompat.getColor(this, R.color.urgency_warning)
                else -> ContextCompat.getColor(this, R.color.urgency_normal)
            }
        } else {
            ContextCompat.getColor(this, R.color.urgency_normal)
        }
    }

    private fun getProviderColors(id: String, hasSecondary: Boolean): Pair<Int, Int> {
        return when (id.lowercase()) {
            "codex" -> {
                if (!hasSecondary) {
                    Pair(
                        ContextCompat.getColor(this, R.color.codex_secondary),
                        ContextCompat.getColor(this, R.color.codex_secondary)
                    )
                } else {
                    Pair(
                        ContextCompat.getColor(this, R.color.codex_primary),
                        ContextCompat.getColor(this, R.color.codex_secondary)
                    )
                }
            }
            "antigravity" -> Pair(
                ContextCompat.getColor(this, R.color.antigravity_primary),
                ContextCompat.getColor(this, R.color.antigravity_secondary)
            )
            "antigravity_third" -> Pair(
                ContextCompat.getColor(this, R.color.antigravity_third_primary),
                ContextCompat.getColor(this, R.color.antigravity_third_secondary)
            )
            "cursor" -> Pair(
                ContextCompat.getColor(this, R.color.cursor_primary),
                ContextCompat.getColor(this, R.color.cursor_secondary)
            )
            else -> Pair(
                ContextCompat.getColor(this, R.color.antigravity_primary),
                ContextCompat.getColor(this, R.color.antigravity_secondary)
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
