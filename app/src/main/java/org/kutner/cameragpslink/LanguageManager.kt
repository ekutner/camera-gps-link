package org.kutner.cameragpslink

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * LanguageManager - Handles in-app language selection
 * Works with ComponentActivity without requiring AppCompat themes
 */
object LanguageManager {
    // List of supported languages
    data class Language(val code: String, val displayName: String)

    val supportedLanguages = listOf(
        Language("", "System Default"),
        Language("en", "English"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("de", "Deutsch"),
        Language("he", "עברית"),
        // Add more languages as you translate them
    )

    fun getSelectedLanguage(context: Context): String {
        return AppSettingsManager.getSelectedLanguage(context)
    }

    fun setLanguage(context: Context, languageCode: String) {
        AppSettingsManager.setSelectedLanguage(context, languageCode)
        val localeList = if (languageCode.isNotEmpty()) {
            LocaleListCompat.forLanguageTags(languageCode)
        } else {
            // Use getEmptyLocaleList() to reset to System Default
            LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getCurrentLanguageDisplayName(context: Context): String {
        val currentCode = getSelectedLanguage(context)
        return supportedLanguages.find { it.code == currentCode }?.displayName ?: "System Default"
    }

    fun wrapContext(baseContext: Context): Context {
        val languageCode = getSelectedLanguage(baseContext)
        if (languageCode.isEmpty()) {
            return baseContext // System Default
        }

        val locale = Locale(languageCode)
        val config = Configuration(baseContext.resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }

        // Create a new localized context
        return baseContext.createConfigurationContext(config)
    }
}