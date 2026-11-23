package com.example.data.preferences

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val code: String) {
    SYSTEM("system"),
    UK("uk"),
    RU("ru"),
    EN("en");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

object AppLocaleManager {
    private const val PREFS_NAME = "localmind_locale_prefs"
    private const val KEY_LANGUAGE = "app_language"

    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, AppLanguage.SYSTEM.code))
    }

    fun saveLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    fun wrapContext(base: Context): Context {
        val language = getSavedLanguage(base)
        if (language == AppLanguage.SYSTEM) return base

        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
