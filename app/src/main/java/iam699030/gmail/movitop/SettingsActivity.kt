package iam699030.gmail.movitop

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import iam699030.gmail.movitop.data.GeocodePlace
import iam699030.gmail.movitop.data.ManualLocationRepository
import iam699030.gmail.movitop.search.SearchLocationActivity

/**
 * In-app language switch (Hebrew/English), independent of the device's
 * system language. Uses AppCompat's per-app locale API, which persists the
 * choice and applies full RTL/LTR layout mirroring automatically — no
 * restart-and-hope-it-sticks logic needed here.
 *
 * Also hosts the manual-location fallback (see [ManualLocationRepository]):
 * satellite-only GPS (see [iam699030.gmail.movitop.nav.LocationTracker]) can
 * take a long time or fail entirely indoors, with no network/fused provider
 * to fall back on, so this lets the user set a standing address to use
 * instead whenever a live fix doesn't arrive in time.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var manualLocationRepository: ManualLocationRepository
    private lateinit var manualLocationStatusText: TextView
    private lateinit var manualLocationSetButton: MaterialButton
    private lateinit var manualLocationClearButton: MaterialButton

    private val pickManualLocation = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(SearchLocationActivity.EXTRA_PLACE_NAME).orEmpty()
        if (name.isEmpty()) return@registerForActivityResult

        // (0,0) is SearchLocationActivity's "not actually resolved" sentinel for
        // local-only name suggestions (see PlaceSuggestions) — unlike route search,
        // there's no later step here to resolve a bare name, so reject it outright
        // rather than silently saving "Null Island" as the fallback location.
        val lat = data.getDoubleExtra(SearchLocationActivity.EXTRA_PLACE_LAT, 0.0)
        val lon = data.getDoubleExtra(SearchLocationActivity.EXTRA_PLACE_LON, 0.0)
        if (lat == 0.0 && lon == 0.0) {
            Snackbar.make(manualLocationStatusText, R.string.settings_manual_location_not_precise, Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        manualLocationRepository.set(
            GeocodePlace(
                name = name,
                subtitle = data.getStringExtra(SearchLocationActivity.EXTRA_PLACE_SUBTITLE),
                lat = lat,
                lon = lon
            )
        )
        refreshManualLocationUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        manualLocationRepository = ManualLocationRepository(this)

        val root = findViewById<View>(R.id.settingsRoot)
        val initialPadding = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPadding.left + bars.left,
                initialPadding.top + bars.top,
                initialPadding.right + bars.right,
                initialPadding.bottom + bars.bottom
            )
            insets
        }

        val hebrewButton = findViewById<MaterialButton>(R.id.languageHebrewButton)
        val englishButton = findViewById<MaterialButton>(R.id.languageEnglishButton)

        hebrewButton.setOnClickListener { setLanguage("he") }
        englishButton.setOnClickListener { setLanguage("en") }

        highlightCurrentLanguage(hebrewButton, englishButton)

        manualLocationStatusText = findViewById(R.id.manualLocationStatusText)
        manualLocationSetButton = findViewById(R.id.manualLocationSetButton)
        manualLocationClearButton = findViewById(R.id.manualLocationClearButton)

        manualLocationSetButton.setOnClickListener {
            pickManualLocation.launch(
                Intent(this, SearchLocationActivity::class.java).apply {
                    putExtra(SearchLocationActivity.EXTRA_FIELD_TYPE, SearchLocationActivity.FIELD_MANUAL_LOCATION)
                }
            )
        }
        manualLocationClearButton.setOnClickListener {
            manualLocationRepository.clear()
            refreshManualLocationUi()
            Snackbar.make(root, R.string.settings_manual_location_cleared, Snackbar.LENGTH_SHORT).show()
        }

        refreshManualLocationUi()
    }

    private fun refreshManualLocationUi() {
        val place = manualLocationRepository.get()
        manualLocationStatusText.text = place?.name ?: getString(R.string.settings_manual_location_not_set)
        manualLocationClearButton.visibility = if (place != null) View.VISIBLE else View.GONE
        manualLocationSetButton.setText(
            if (place != null) R.string.settings_manual_location_change_button else R.string.settings_manual_location_set_button
        )
    }

    private fun setLanguage(languageTag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    private fun highlightCurrentLanguage(hebrewButton: MaterialButton, englishButton: MaterialButton) {
        val current = AppCompatDelegate.getApplicationLocales()
        // java.util.Locale canonicalizes "he" to the legacy ISO code "iw"
        // (same reason this project's Hebrew resources live under
        // values-iw/, not values-he/) — comparing against "he" alone never
        // matches, so this always fell through to "not Hebrew". When no
        // explicit per-app override has been set yet (current.isEmpty,
        // e.g. right after install), fall back to whatever locale Android
        // actually resolved this Activity's resources to, rather than
        // assuming English.
        val activeLanguage = if (!current.isEmpty) current[0]?.language else resources.configuration.locales[0]?.language
        val isHebrew = activeLanguage == "he" || activeLanguage == "iw"
        val selected = getColor(R.color.movitop_primary)
        val unselected = getColor(R.color.movitop_chip_bg)
        val selectedText = getColor(R.color.movitop_on_primary)
        val unselectedText = getColor(R.color.movitop_chip_text)
        hebrewButton.setBackgroundColor(if (isHebrew) selected else unselected)
        englishButton.setBackgroundColor(if (!isHebrew) selected else unselected)
        hebrewButton.setTextColor(if (isHebrew) selectedText else unselectedText)
        englishButton.setTextColor(if (!isHebrew) selectedText else unselectedText)
    }
}
