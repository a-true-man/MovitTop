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
import iam699030.gmail.movitop.data.PinnedSlot
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
    private lateinit var homeRow: View
    private lateinit var workRow: View
    private lateinit var homeText: TextView
    private lateinit var workText: TextView

    private val resultAdapter = GeocodeResultAdapter { place -> returnPlace(place) }

    // Mirrors viewModel.pendingPinnedSlot for rendering (see refreshPinnedRowTexts) —
    // a plain var on the ViewModel isn't itself observable as a flow.
    private var pickingSlot: PinnedSlot? = null
    private var homePlaceName: String? = null
    private var workPlaceName: String? = null

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
        homeRow = findViewById(R.id.homeRow)
        workRow = findViewById(R.id.workRow)
        homeText = findViewById(R.id.homeText)
        workText = findViewById(R.id.workText)

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

        setupPinnedRow(homeRow, PinnedSlot.HOME) { viewModel.homePlace.value }
        setupPinnedRow(workRow, PinnedSlot.WORK) { viewModel.workPlace.value }

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
                launch {
                    viewModel.homePlace.collect { place ->
                        homePlaceName = place?.name
                        refreshPinnedRowTexts()
                    }
                }
                launch {
                    viewModel.workPlace.collect { place ->
                        workPlaceName = place?.name
                        refreshPinnedRowTexts()
                    }
                }
            }
        }
    }

    /**
     * Wires tap (use if set, else start picking one) and long-press (always
     * re-pick) for a pinned-place row — for touch that's [View.OnClickListener]
     * / [View.OnLongClickListener], but a plain [View.OnKeyListener] on
     * DPAD_CENTER/ENTER only ever sees discrete key events, not gesture
     * timing, so a D-pad hold needs its own long-press detection here. Two
     * signals, either one sufficient: a genuinely held hardware key resends
     * ACTION_DOWN with an increasing repeatCount (real D-pad hardware), while
     * some soft/virtual sources instead mark one redelivered ACTION_DOWN with
     * [KeyEvent.FLAG_LONG_PRESS] directly (notably `adb shell input keyevent
     * --longpress`, used to verify this without physical hardware). Either
     * way this suppresses the eventual ACTION_UP's short-press action.
     */
    private fun setupPinnedRow(row: View, slot: PinnedSlot, current: () -> GeocodePlace?) {
        val onSelect = {
            val place = current()
            if (place != null) returnPlace(place) else enterPendingPinMode(slot)
        }
        row.setOnClickListener { onSelect() }
        row.setOnLongClickListener { enterPendingPinMode(slot); true }

        var longPressTriggered = false
        row.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                return@setOnKeyListener false
            }
            val isLongPress = event.repeatCount >= LONG_PRESS_REPEAT_COUNT ||
                (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0 && !isLongPress) {
                        longPressTriggered = false
                    } else if (isLongPress && !longPressTriggered) {
                        longPressTriggered = true
                        enterPendingPinMode(slot)
                    }
                    true
                }
                KeyEvent.ACTION_UP -> {
                    if (!longPressTriggered) onSelect()
                    true
                }
                else -> false
            }
        }
    }

    private fun enterPendingPinMode(slot: PinnedSlot) {
        viewModel.pendingPinnedSlot = slot
        pickingSlot = slot
        refreshPinnedRowTexts()
        val prompt = when (slot) {
            PinnedSlot.HOME -> R.string.pinned_pick_prompt_home
            PinnedSlot.WORK -> R.string.pinned_pick_prompt_work
        }
        // The prompt alone (a Snackbar that disappears in a few seconds) left
        // no way to back out of "picking" mode short of aborting the whole
        // origin/destination flow with Back — the row text staying on
        // "Choosing…" (see refreshPinnedRowTexts) is the persistent signal,
        // and this action is the actual way out.
        Snackbar.make(searchInput, prompt, Snackbar.LENGTH_LONG)
            .setAction(R.string.pinned_pick_cancel) { cancelPendingPinMode() }
            .show()
        searchInput.requestFocus()
    }

    private fun cancelPendingPinMode() {
        viewModel.pendingPinnedSlot = null
        pickingSlot = null
        refreshPinnedRowTexts()
    }

    private fun refreshPinnedRowTexts() {
        homeText.text = when {
            pickingSlot == PinnedSlot.HOME -> getString(R.string.pinned_picking_active)
            homePlaceName != null -> homePlaceName
            else -> getString(R.string.pinned_set_home)
        }
        workText.text = when {
            pickingSlot == PinnedSlot.WORK -> getString(R.string.pinned_picking_active)
            workPlaceName != null -> workPlaceName
            else -> getString(R.string.pinned_set_work)
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
        viewModel.pendingPinnedSlot?.let { slot ->
            viewModel.pinPlace(slot, place)
            viewModel.pendingPinnedSlot = null
        }
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

        /** D-Pad-held-key repeats needed before a pinned row treats it as a long-press. */
        private const val LONG_PRESS_REPEAT_COUNT = 3
    }
}
