package com.maiconerick.xumbra.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ModelInfo(val source: String, val name: String, val context: Int)

/**
 * Wallet, quotas and model display names, cached for the whole process so
 * screens draw immediately with the last known numbers (no jumpy reloads).
 */
object Money {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val balances = MutableStateFlow<JSONObject?>(null)
    val quota = MutableStateFlow<JSONObject?>(null)
    val stats = HashMap<String, JSONObject>()           // usage stats per window
    val names = MutableStateFlow<Map<String, ModelInfo>>(emptyMap())

    /**
     * USD → BRL, from the Pi (refreshed there at 07:00 and when it moves).
     * Read inside Fmt.money, so every price on screen grows a "(R$ …)" as
     * soon as it arrives. 0 means "not known yet" and shows nothing.
     */
    var brlRate by mutableStateOf(0.0)
        private set
    var brlUpdated by mutableStateOf(0.0)
        private set

    private var loop: Job? = null
    private var pending: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { while (true) { refresh(); delay(180_000) } }
    }

    suspend fun refresh() {
        try { balances.value = Net.get("/api/v2/balances") } catch (_: Exception) { }
        try { quota.value = Net.get("/api/v2/quota") } catch (_: Exception) { }
        try {
            val fx = Net.get("/api/v2/fx")
            val r = fx.optDouble("usd_brl", 0.0)
            if (r > 0) { brlRate = r; brlUpdated = fx.optDouble("updated", 0.0) }
        } catch (_: Exception) { }
    }

    /** After a reply: refresh soon (debounced) so the wallet follows the spending. */
    fun soon() {
        pending?.cancel()
        pending = scope.launch { delay(4000); refresh() }
    }

    fun orRemaining(): Double? = balances.value?.optJSONObject("openrouter")?.takeIf { !it.has("error") }?.optDouble("remaining")

    fun spentToday(provider: String): Double = balances.value?.optJSONObject("spend")?.optJSONObject(provider)?.optDouble("today", 0.0) ?: 0.0

    /** Ask the Pi for display names of these '<provider>/<model>' keys (once each). */
    fun ensureNames(keys: Collection<String>) {
        val missing = keys.filter { it.isNotBlank() && it !in names.value }.distinct()
        if (missing.isEmpty()) return
        scope.launch {
            try {
                val o = Net.post("/api/v2/models/info", JSONObject().put("keys", JSONArray(missing))).optJSONObject("models") ?: return@launch
                val add = o.keys().asSequence().associateWith { k ->
                    val m = o.getJSONObject(k); ModelInfo(m.optString("source"), m.optString("name"), m.optInt("context"))
                }
                names.value = names.value + add
            } catch (_: Exception) { }
        }
    }

    fun sourceName(provider: String) = when (provider) {
        "openrouter" -> "OpenRouter"; "openai" -> "OpenAI"; "gemini" -> "Google Gemini"; "anthropic" -> "Anthropic"; "ollama" -> "Ollama"; "code" -> "Code art · Pi"
        else -> provider
    }
}
