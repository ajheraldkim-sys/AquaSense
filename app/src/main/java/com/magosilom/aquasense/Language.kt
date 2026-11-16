package com.magosilom.aquasense

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** In-app language choice. [SYSTEM] follows the phone if it is Filipino or Cebuano, else English. */
enum class AppLanguage(val tag: String) { SYSTEM(""), EN("en"), FIL("fil"), CEB("ceb") }

/** Returns the resource language tag to use: "en", "fil" or "ceb". */
fun resolveLanguage(choice: AppLanguage, systemTag: String): String {
    if (choice != AppLanguage.SYSTEM) return choice.tag
    return when (systemTag.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
        "fil", "tl" -> "fil" // older Android reports Filipino as "tl"
        "ceb" -> "ceb"
        else -> "en"
    }
}

private const val PREFS = "aquasense"
private const val KEY_LANGUAGE = "language"

/** Persists the language choice in SharedPreferences "aquasense" / "language". */
class LanguagePrefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _language = MutableStateFlow(read(p.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<AppLanguage> = _language

    fun set(lang: AppLanguage) {
        p.edit().putString(KEY_LANGUAGE, lang.name).apply()
        _language.value = lang
    }

    companion object {
        fun read(s: String?): AppLanguage = AppLanguage.entries.firstOrNull { it.name == s } ?: AppLanguage.SYSTEM
    }
}

/** The phone's own language (ignores any in-app override). */
fun systemLanguageTag(): String = Resources.getSystem().configuration.locales[0]?.toLanguageTag() ?: "en"

/** The resolved tag for the saved choice; usable outside Compose (e.g. notifications). */
fun savedLanguageTag(ctx: Context): String {
    val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return resolveLanguage(LanguagePrefs.read(p.getString(KEY_LANGUAGE, null)), systemLanguageTag())
}

private fun localizedConfig(base: Configuration, lang: String): Configuration =
    Configuration(base).apply { setLocales(LocaleList(Locale.forLanguageTag(lang))) }

/** A context whose resources use the saved in-app language. */
fun localizedContext(ctx: Context): Context =
    ctx.createConfigurationContext(localizedConfig(ctx.resources.configuration, savedLanguageTag(ctx)))

/**
 * Provides [lang] resources to [content] without restarting the Activity. The provided context
 * still wraps the Activity (so lookups that need it keep working) but answers with localized resources.
 */
@Composable
fun LocalizedApp(lang: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val (ctx, config) = remember(base, baseConfig, lang) {
        val cfg = localizedConfig(baseConfig, lang)
        val res = base.createConfigurationContext(cfg).resources
        val wrapped = object : ContextWrapper(base) {
            override fun getResources(): Resources = res
        }
        wrapped to cfg
    }
    CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides config) { content() }
}
