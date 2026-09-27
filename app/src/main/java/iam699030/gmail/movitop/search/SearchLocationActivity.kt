package iam699030.gmail.movitop.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.SimpleTextWatcher
import iam699030.gmail.movitop.data.GeocodePlace
import iam699030.gmail.movitop.nav.LocationTracker
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SearchLocationActivity : AppCompatActivity() {

    private val viewModel: SearchLocationViewModel by viewModels()

    private lateinit var searchInput: EditText
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var searchProgress: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var currentLocationRow: View
    private lateinit var currentLocationProgress: ProgressBar
    private lateinit var recentPlacesHeader: TextView

    private val resultAdapter = GeocodeResultAdapter { place -> returnPlace(place) }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) locateCurrentPosition() else showLocationUnavailable() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_search_location)

        val toolbar = findViewById<MaterialToolbar>(R.id.searchToolbar)
        val fieldType = intent.getStringExtra(EXTRA_FIELD_TYPE) ?: FIELD_ORIGIN
        toolbar.title = if (fieldType == FIELD_DESTINATION) {
            getString(R.string.search_destination_title)
        } else {
            getString(R.string.search_origin_title)
        }
        toolbar.setNavigationOnClickListener { finish() }

        searchInput = findViewById(R.id.searchInput)
        resultsRecycler = findViewById(R.id.resultsRecycler)
        searchProgress = findViewById(R.id.searchProgress)
        emptyState = findViewById(R.id.emptyState)
        currentLocationRow = findViewById(R.id.currentLocationRow)
        currentLocationProgress = findViewById(R.id.currentLocationProgress)
        recentPlacesHeader = findViewById(R.id.recentPlacesHeader)

        currentLocationRow.setOnClickListener { onCurrentLocationSelected() }
        currentLocationRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onCurrentLocationSelected()
                true
            } else {
                false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.searchRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        resultsRecycler.layoutManager = LinearLayoutManager(this)
        resultsRecycler.adapter = resultAdapter

        searchInput.addTextChangedListener(SimpleTextWatcher { text ->
            viewModel.setQuery(text)
        })
        searchInput.nextFocusDownId = R.id.resultsRecycler

        setupKeyHandling()

        val initialQuery = intent.getStringExtra(EXTRA_INITIAL_QUERY).orEmpty()
        if (initialQuery.isNotEmpty()) {
            searchInput.setText(initialQuery)
            searchInput.setSelection(initialQuery.length)
            viewModel.setInitialQuery(initialQuery)
        }

        observeViewModel()
        searchInput.requestFocus()
    }

    private fun setupKeyHandling() {
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && resultAdapter.itemCount > 0) {
                focusFirstResult()
                true
            } else {
                false
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Before the user has typed anything, show recently-picked
                // places instead of an empty list — both flows feed the same
                // list, so either updating re-renders it.
                launch { viewModel.results.collect { renderList() } }
                launch { viewModel.recents.collect { renderList() } }
                launch {
                    viewModel.isSearching.collect { searching ->
                        searchProgress.visibility = if (searching) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun renderList() {
        val showingRecents = searchInput.text.length < 2
        val list = if (showingRecents) viewModel.recents.value else viewModel.results.value
        // DPAD_DOWN with results present is handled by setupKeyHandling()'s key
        // listener (jumps straight into the list), so nextFocusDownId only
        // matters when it's empty — left pointed at currentLocationRow (XML)
        // so arrow-down still lands somewhere useful then.
        resultAdapter.submitList(list)
        recentPlacesHeader.visibility = if (showingRecents && list.isNotEmpty()) View.VISIBLE else View.GONE
        emptyState.visibility = if (!showingRecents && list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onCurrentLocationSelected() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            locateCurrentPosition()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** GPS fix only (see [LocationTracker]) — the pick is returned as-is, with no reverse-geocoded name. */
    private fun locateCurrentPosition() {
        currentLocationProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val point = withTimeoutOrNull(10_000L) {
                LocationTracker(this@SearchLocationActivity).updates().firstOrNull()
            }
            currentLocationProgress.visibility = View.GONE
            if (point == null) {
                showLocationUnavailable()
                return@launch
            }
            returnPlace(
                GeocodePlace(
                    name = getString(R.string.search_use_current_location),
                    subtitle = null,
                    lat = point.lat,
                    lon = point.lon,
                    id = SearchLocationViewModel.CURRENT_LOCATION_ID
                )
            )
        }
    }

    private fun showLocationUnavailable() {
        Snackbar.make(currentLocationRow, R.string.map_location_unavailable, Snackbar.LENGTH_LONG).show()
    }

    private fun returnPlace(place: GeocodePlace) {
        viewModel.recordPick(place)
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_PLACE_NAME, place.name)
                putExtra(EXTRA_PLACE_SUBTITLE, place.subtitle)
                putExtra(EXTRA_PLACE_LAT, place.lat)
                putExtra(EXTRA_PLACE_LON, place.lon)
            }
        )
        finish()
    }

    private fun focusFirstResult() {
        resultsRecycler.post {
            val first = resultsRecycler.findViewHolderForAdapterPosition(0)?.itemView
                ?: resultsRecycler.layoutManager?.findViewByPosition(0)
            first?.requestFocus()
            first?.nextFocusUpId = R.id.searchInput
        }
    }

    companion object {
        const val EXTRA_FIELD_TYPE = "field_type"
        const val EXTRA_INITIAL_QUERY = "initial_query"
        const val EXTRA_PLACE_NAME = "place_name"
        const val EXTRA_PLACE_SUBTITLE = "place_subtitle"
        const val EXTRA_PLACE_LAT = "place_lat"
        const val EXTRA_PLACE_LON = "place_lon"

        const val FIELD_ORIGIN = "origin"
        const val FIELD_DESTINATION = "destination"
    }
}
