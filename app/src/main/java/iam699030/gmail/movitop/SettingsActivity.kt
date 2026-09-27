package iam699030.gmail.movitop

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton

/**
 * In-app language switch (Hebrew/English), independent of the device's
 * system language. Uses AppCompat's per-app locale API, which persists the
 * choice and applies full RTL/LTR layout mirroring automatically — no
 * restart-and-hope-it-sticks logic needed here.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val hebrewButton = findViewById<MaterialButton>(R.id.languageHebrewButton)
        val englishButton = findViewById<MaterialButton>(R.id.languageEnglishButton)

        hebrewButton.setOnClickListener { setLanguage("he") }
        englishButton.setOnClickListener { setLanguage("en") }

        highlightCurrentLanguage(hebrewButton, englishButton)
    }

    private fun setLanguage(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    private fun highlightCurrentLanguage(hebrewButton: MaterialButton, englishButton: MaterialButton) {
        val current = AppCompatDelegate.getApplicationLocales()
        val isHebrew = !current.isEmpty && current[0]?.language == "he"
        val selected = getColor(R.color.movitop_primary)
        val unselected = getColor(R.color.movitop_chip_bg)
        hebrewButton.setBackgroundColor(if (isHebrew) selected else unselected)
        englishButton.setBackgroundColor(if (!isHebrew) selected else unselected)
    }
}
