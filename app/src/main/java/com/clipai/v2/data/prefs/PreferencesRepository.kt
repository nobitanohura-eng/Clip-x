package com.clipai.v2.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clipai.v2.domain.ExportPreset
import com.clipai.v2.domain.FallbackLadder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clipai_v2_prefs"
)

data class UserSettings(
    val exportPreset: ExportPreset = ExportPreset.STANDARD_1080P,
    val minClipSec: Int = 30,
    val maxClipSec: Int = 60,
    val dedupGapSec: Int = 30,
    val maxClips: Int = 5,
    val wifiOnlyOver100Mb: Boolean = true,
    val requireChargingForHeavy: Boolean = true,
    val llmEnabled: Boolean = false,
    val selectedAsrModelId: String = FallbackLadder.MODEL_WHISPER_TINY,
    val selectedLlmModelId: String = FallbackLadder.MODEL_GEMMA_4_E2B
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val EXPORT_PRESET = stringPreferencesKey("export_preset")
        val MIN_CLIP_SEC = intPreferencesKey("min_clip_sec")
        val MAX_CLIP_SEC = intPreferencesKey("max_clip_sec")
        val DEDUP_GAP_SEC = intPreferencesKey("dedup_gap_sec")
        val MAX_CLIPS = intPreferencesKey("max_clips")
        val WIFI_ONLY_100MB = booleanPreferencesKey("wifi_only_100mb")
        val REQUIRE_CHARGING = booleanPreferencesKey("require_charging")
        val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        val SELECTED_ASR = stringPreferencesKey("selected_asr")
        val SELECTED_LLM = stringPreferencesKey("selected_llm")
    }

    val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val presetName = prefs[Keys.EXPORT_PRESET] ?: ExportPreset.STANDARD_1080P.name
        val preset = ExportPreset.entries.firstOrNull { it.name == presetName }
            ?: ExportPreset.STANDARD_1080P

        UserSettings(
            exportPreset = preset,
            minClipSec = prefs[Keys.MIN_CLIP_SEC] ?: 30,
            maxClipSec = prefs[Keys.MAX_CLIP_SEC] ?: 60,
            dedupGapSec = prefs[Keys.DEDUP_GAP_SEC] ?: 30,
            maxClips = prefs[Keys.MAX_CLIPS] ?: 5,
            wifiOnlyOver100Mb = prefs[Keys.WIFI_ONLY_100MB] ?: true,
            requireChargingForHeavy = prefs[Keys.REQUIRE_CHARGING] ?: true,
            llmEnabled = prefs[Keys.LLM_ENABLED] ?: false,
            selectedAsrModelId = prefs[Keys.SELECTED_ASR] ?: FallbackLadder.MODEL_WHISPER_TINY,
            selectedLlmModelId = prefs[Keys.SELECTED_LLM] ?: FallbackLadder.MODEL_GEMMA_4_E2B
        )
    }

    suspend fun updateSettings(transform: (UserSettings) -> UserSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = UserSettings(
                exportPreset = ExportPreset.entries.firstOrNull { it.name == prefs[Keys.EXPORT_PRESET] }
                    ?: ExportPreset.STANDARD_1080P,
                minClipSec = prefs[Keys.MIN_CLIP_SEC] ?: 30,
                maxClipSec = prefs[Keys.MAX_CLIP_SEC] ?: 60,
                dedupGapSec = prefs[Keys.DEDUP_GAP_SEC] ?: 30,
                maxClips = prefs[Keys.MAX_CLIPS] ?: 5,
                wifiOnlyOver100Mb = prefs[Keys.WIFI_ONLY_100MB] ?: true,
                requireChargingForHeavy = prefs[Keys.REQUIRE_CHARGING] ?: true,
                llmEnabled = prefs[Keys.LLM_ENABLED] ?: false,
                selectedAsrModelId = prefs[Keys.SELECTED_ASR] ?: FallbackLadder.MODEL_WHISPER_TINY,
                selectedLlmModelId = prefs[Keys.SELECTED_LLM] ?: FallbackLadder.MODEL_GEMMA_4_E2B
            )
            val updated = transform(current)
            prefs[Keys.EXPORT_PRESET] = updated.exportPreset.name
            prefs[Keys.MIN_CLIP_SEC] = updated.minClipSec.coerceIn(15, 50)
            prefs[Keys.MAX_CLIP_SEC] = updated.maxClipSec.coerceIn(35, 180)
            prefs[Keys.DEDUP_GAP_SEC] = updated.dedupGapSec.coerceIn(10, 90)
            prefs[Keys.MAX_CLIPS] = updated.maxClips.coerceIn(1, 10)
            prefs[Keys.WIFI_ONLY_100MB] = updated.wifiOnlyOver100Mb
            prefs[Keys.REQUIRE_CHARGING] = updated.requireChargingForHeavy
            prefs[Keys.LLM_ENABLED] = updated.llmEnabled
            prefs[Keys.SELECTED_ASR] = updated.selectedAsrModelId
            prefs[Keys.SELECTED_LLM] = updated.selectedLlmModelId
        }
    }

    /**
     * Stores the user's optional personal Hugging Face read token inside EncryptedSharedPreferences.
     * Never hardcoded in source, never logged.
     */
    private fun encryptedPrefs() = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "clipai_v2_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    fun saveUserHfToken(token: String) {
        encryptedPrefs()?.edit()?.putString("hf_read_token", token.trim())?.apply()
    }

    fun clearUserHfToken() {
        encryptedPrefs()?.edit()?.remove("hf_read_token")?.apply()
    }

    fun getUserHfToken(): String? {
        return encryptedPrefs()?.getString("hf_read_token", null)?.takeIf { it.isNotBlank() }
    }

    fun hasUserHfToken(): Boolean = !getUserHfToken().isNullOrBlank()
}
