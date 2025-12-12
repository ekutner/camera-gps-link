package org.kutner.cameragpslink

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

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
        // 1. Save preference using AppSettingsManager
        AppSettingsManager.setSelectedLanguage(context, languageCode)

        // 2. Apply the locale change via AppCompatDelegate
        val localeList = if (languageCode.isNotEmpty()) {
            LocaleListCompat.forLanguageTags(languageCode)
        } else {
            // Explicitly reset to system default locale
            LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)

        // Activity restart is handled by the overall flow (we stop the service in MainActivity)
        // If the activity is already running, it should be recreated here to apply the UI change.
        (context as? Activity)?.recreate()
    }

    /**
     * Creates a new Context with the selected language applied.
     * This is ONLY used for non-Activity components (Services, BroadcastReceivers)
     * that need to access localized strings.
     * * NOTE: This function uses the modern (API 24+) Context.createConfigurationContext API
     * and avoids all deprecated locale configuration methods.
     */
    fun wrapContext(baseContext: Context): Context {
        val languageCode = getSelectedLanguage(baseContext)
        if (languageCode.isEmpty()) {
            return baseContext // Return system default if no specific language is set
        }

        val locale = Locale(languageCode)

        // 1. Create a configuration copy
        val config = Configuration(baseContext.resources.configuration)

        // 2. Apply the locale to the configuration (using non-deprecated APIs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            config.setLocales(localeList)
        } else {
            // Deprecated path for API < 24, but required for compatibility
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }

        // 3. Create a new localized context based on the configuration
        return baseContext.createConfigurationContext(config)
    }

    fun getCurrentLanguageDisplayName(context: Context): String {
        val currentCode = getSelectedLanguage(context)
        return supportedLanguages.find { it.code == currentCode }?.displayName
            ?: supportedLanguages.first { it.code == "" }.displayName
    }
}