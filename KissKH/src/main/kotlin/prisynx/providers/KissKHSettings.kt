package prisynx.providers

import android.content.Context
import android.content.SharedPreferences

/**
 * Local settings for the KissKH extension.
 *
 * The DeepL key is optional and never shipped with the plugin: it is entered
 * by the user from the extension settings and stored in the app-private
 * SharedPreferences. Without a key only the native KissKH tracks are exposed.
 */
object KissKHSettings {
    const val DEFAULT_BASE = "https://kisskh.co"

    private const val PREFS = "kisskh_settings"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_DEEPL = "deepl_key"
    private const val KEY_TARGET = "target_lang"
    private const val KEY_PROXY = "proxy_template"
    private const val KEY_TRANSLATION = "translation_enabled"
    private const val KEY_ADULT = "adult_filter"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun attach(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences? = preferences

    var baseUrl: String
        get() = prefs()?.getString(KEY_BASE_URL, DEFAULT_BASE)
            .orEmpty()
            .trim()
            .ifBlank { DEFAULT_BASE }
            .trimEnd('/')
        set(value) {
            prefs()?.edit()
                ?.putString(KEY_BASE_URL, value.trim().ifBlank { DEFAULT_BASE }.trimEnd('/'))
                ?.apply()
        }

    var deeplKey: String
        get() = normalizeKey(prefs()?.getString(KEY_DEEPL, "").orEmpty())
        set(value) {
            prefs()?.edit()?.putString(KEY_DEEPL, normalizeKey(value))?.apply()
        }

    /** Accepts pasted values such as "DeepL-Auth-Key xxxx:fx" and trims them. */
    private fun normalizeKey(value: String): String =
        value.trim()
            .replace(Regex("^deepl-auth-key\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

    var targetLang: String
        get() = KissKHText.sanitizeTargetLang(prefs()?.getString(KEY_TARGET, "it")) ?: "it"
        set(value) {
            prefs()?.edit()
                ?.putString(KEY_TARGET, KissKHText.sanitizeTargetLang(value) ?: "it")
                ?.apply()
        }

    var proxyTemplate: String
        get() = prefs()?.getString(KEY_PROXY, "").orEmpty().trim()
        set(value) {
            prefs()?.edit()?.putString(KEY_PROXY, value.trim())?.apply()
        }

    var translationEnabled: Boolean
        get() = prefs()?.getBoolean(KEY_TRANSLATION, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_TRANSLATION, value)?.apply()
        }

    var adultFilter: Boolean
        get() = prefs()?.getBoolean(KEY_ADULT, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_ADULT, value)?.apply()
        }

    fun translationActive(): Boolean = translationEnabled && deeplKey.isNotBlank()
}
