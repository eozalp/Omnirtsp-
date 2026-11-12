package com.example.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object AppLocaleManager {
    const val LANG_SYSTEM = "system"
    const val LANG_ENGLISH = "en"
    const val LANG_TURKISH = "tr"

    private const val PREFS_NAME = "omniview_locale_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    private val _selectedLanguageTag = MutableStateFlow(LANG_SYSTEM)
    val selectedLanguageTag: StateFlow<String> = _selectedLanguageTag.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            val saved = readSavedTag(context)
            _selectedLanguageTag.value = saved
            initialized = true
        }
    }

    fun getSelectedTag(context: Context): String {
        init(context)
        return _selectedLanguageTag.value
    }

    fun setLanguage(context: Context, tag: String) {
        val normalized = normalizeTag(tag)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .apply()
        _selectedLanguageTag.value = normalized
        initialized = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                lm?.applicationLocales = if (normalized == LANG_SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(normalized)
                }
            }
        }
    }

    fun wrapContext(base: Context): Context {
        val tag = readSavedTag(base)
        _selectedLanguageTag.value = tag
        initialized = true
        return applyLocaleToContext(base, tag)
    }

    fun getLocalizedContext(context: Context): Context {
        val tag = getSelectedTag(context)
        return applyLocaleToContext(context, tag)
    }

    fun applyLocaleToResources(context: Context, tag: String = getSelectedTag(context)): Configuration {
        val systemLang = readSystemLanguage(context)
        val effectiveLang = resolveEffectiveLanguage(tag, systemLang)
        val locale = Locale(effectiveLang)
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
        return Configuration(res.configuration)
    }

    fun getEffectiveLocale(context: Context): Locale {
        val tag = getSelectedTag(context)
        val systemLang = readSystemLanguage(context)
        val effective = resolveEffectiveLanguage(tag, systemLang)
        return Locale(effective)
    }

    fun isTurkish(context: Context): Boolean = getEffectiveLocale(context).language == LANG_TURKISH

    fun getString(context: Context, resId: Int, vararg formatArgs: Any): String {
        val localized = getLocalizedContext(context)
        return if (formatArgs.isEmpty()) {
            localized.getString(resId)
        } else {
            localized.getString(resId, *formatArgs)
        }
    }

    private fun readSystemLanguage(context: Context): String {
        val sysConfig = android.content.res.Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sysConfig.locales[0]?.language
                ?: context.applicationContext.resources.configuration.locales[0]?.language
                ?: Locale.getDefault().language
        } else {
            @Suppress("DEPRECATION")
            sysConfig.locale?.language
                ?: context.applicationContext.resources.configuration.locale?.language
                ?: Locale.getDefault().language
        }
    }

    private fun readSavedTag(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        if (stored == LANG_SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sysAppTag = runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                lm?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language
            }.getOrNull()
            if (sysAppTag == LANG_TURKISH || sysAppTag == LANG_ENGLISH) {
                return sysAppTag
            }
        }
        return normalizeTag(stored)
    }

    private fun applyLocaleToContext(base: Context, tag: String): Context {
        val systemLang = readSystemLanguage(base)
        val effectiveLang = resolveEffectiveLanguage(tag, systemLang)
        val locale = Locale(effectiveLang)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun normalizeTag(tag: String): String = when (tag.trim().lowercase()) {
        LANG_TURKISH, "tr-tr" -> LANG_TURKISH
        LANG_ENGLISH, "en-us", "en-gb" -> LANG_ENGLISH
        else -> LANG_SYSTEM
    }

    fun resolveEffectiveLanguage(selectedTag: String, systemLanguage: String): String {
        return when (normalizeTag(selectedTag)) {
            LANG_TURKISH -> LANG_TURKISH
            LANG_ENGLISH -> LANG_ENGLISH
            else -> if (systemLanguage.lowercase().startsWith(LANG_TURKISH)) LANG_TURKISH else LANG_ENGLISH
        }
    }
}
