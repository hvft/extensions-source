package eu.kanade.tachiyomi.extension.all.nhapi

import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val settingsScope = CoroutineScope(Dispatchers.IO)

/**
 * A single text field: paste a key generated on the website, get it validated
 * right away. Clearing the field returns the source to the anonymous tier.
 */
internal fun NHentai.setupApiKeyPreferences(screen: PreferenceScreen) {
    val prefs = preference
    val mainHandler = Handler(Looper.getMainLooper())

    EditTextPreference(screen.context).apply {
        // No preference key: the value lives in this source's own
        // SharedPreferences rather than the app-wide default file.
        key = null
        title = "API Key"
        dialogTitle = "API Key"
        dialogMessage = "在 nhentai.net 网页登录后，从账户设置里生成 API Key，粘贴到这里。留空 = 匿名模式。"
        summary = apiKeySummary(prefs.getString(API_KEY_PREF, "").orEmpty())
        setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as String).trim()
            prefs.edit().putString(API_KEY_PREF, value).apply()

            if (value.isEmpty()) {
                summary = apiKeySummary("")
            } else {
                summary = "验证中…"
                settingsScope.launch {
                    val result = probeApiKey()
                    mainHandler.post { summary = result }
                }
            }
            true
        }
    }.also(screen::addPreference)
}

private fun apiKeySummary(value: String): String =
    if (value.isBlank()) {
        "未配置 · 匿名（搜索 10 次/分）"
    } else {
        "已配置 · 搜索 20 次/分"
    }
