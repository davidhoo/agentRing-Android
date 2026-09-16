package app.agentring.android.model

import com.google.gson.annotations.SerializedName

/**
 * 完整同步数据包
 */
data class SyncPayload(
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("providers") val providers: List<ProviderData> = emptyList()
)

/**
 * 单个 AI 助手提供商（Codex、Cursor、Antigravity 等）用量
 */
data class ProviderData(
    @SerializedName("id") val id: String, // "codex", "cursor", "antigravity"
    @SerializedName("name") val name: String,
    @SerializedName("primary") val primary: LimitItem? = null,
    @SerializedName("secondary") val secondary: LimitItem? = null,
    @SerializedName("tertiary") val tertiary: LimitItem? = null,
    @SerializedName("extraInfo") val extraInfo: String? = null,
    @SerializedName("statusMessage") val statusMessage: String? = null
)

/**
 * 额度项（只展示剩余）
 */
data class LimitItem(
    @SerializedName("label") val label: String? = null,
    @SerializedName("remainingPercent") val remainingPercent: Double = 100.0,
    @SerializedName("remainingFraction") val remainingFraction: Double? = null,
    @SerializedName("resetsAt") val resetsAt: String? = null,
    @SerializedName("remainingDetails") val remainingDetails: String? = null
)
