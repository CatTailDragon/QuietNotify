package io.github.lsp1.quietnotify.data

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.lsp1.quietnotify.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RulesState(
    val enabled: Boolean = true,
    val headsUpEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val rules: Map<String, Long> = emptyMap(),
)

class ConfigRepository {
    private val mutableState = MutableStateFlow(RulesState())
    val state = mutableState.asStateFlow()
    private var preferences: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        updateFrom(prefs)
    }

    fun connect(service: XposedService?) {
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = service?.getRemotePreferences(Config.GROUP)?.also {
            it.registerOnSharedPreferenceChangeListener(listener)
            updateFrom(it)
        }
        if (service == null) mutableState.value = RulesState()
    }

    fun setEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(Config.ENABLED, enabled)?.apply()
    }

    fun setHeadsUpEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(Config.HEADS_UP_ENABLED, enabled)?.apply()
    }

    fun setSoundEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(Config.SOUND_ENABLED, enabled)?.apply()
    }

    fun setRule(packageName: String, durationMs: Long?) {
        val editor = preferences?.edit() ?: return
        if (durationMs == null) editor.remove(Config.RULE_PREFIX + packageName)
        else editor.putLong(Config.RULE_PREFIX + packageName, durationMs)
        editor.apply()
    }

    private fun updateFrom(prefs: SharedPreferences) {
        mutableState.value = RulesState(
            enabled = prefs.getBoolean(Config.ENABLED, true),
            headsUpEnabled = prefs.getBoolean(Config.HEADS_UP_ENABLED, true),
            soundEnabled = prefs.getBoolean(Config.SOUND_ENABLED, true),
            rules = Config.readRules(prefs),
        )
    }
}
