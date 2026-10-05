package iam699030.gmail.movitop

import android.app.DatePickerDialog
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
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
import com.google.android.material.button.MaterialButton
import iam699030.gmail.movitop.data.GtfsTime
import iam699030.gmail.movitop.data.LineDepartureAdapter
import iam699030.gmail.movitop.data.LineSummary
import iam699030.gmail.movitop.data.LineSummaryAdapter
import iam699030.gmail.movitop.data.TripStopAdapter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Search a line, see the distinct lines found (both directions merged into
 * one card, see LineSummary), drill into one line's timetable for a chosen
 * day with a direction toggle, then drill into one trip's full stop-by-stop
 * arrival list. Pure local lookup against the SQLite DB built by
 * build_line_schedules.py — no MOTIS query, no network, works even if the
 * routing engine isn't running.
 *
 * Single activity, three in-place-swapped screens (see LineTimesScreen) —
 * same pattern as MainActivity's stop-tap bottom sheet drill-down
 * (stopLineAdapter/lineScheduleAdapter), just with one more level.
 */
class LineTimesActivity : AppCompatActivity() {

    private val viewModel: LineTimesViewModel by viewModels()
    private val lineSummaryAdapter = LineSummaryAdapter(
        onSelected = { viewModel.selectLine(it) },
        onToggleFavorite = { viewModel.toggleFavorite(it) }
    )
    private val lineDepartureAdapter = LineDepartureAdapter { viewModel.selectTrip(it) }
    private val tripStopAdapter = TripStopAdapter()

    private lateinit var searchTitle: TextView
    private lateinit var detailHeader: View
    private lateinit var backButton: MaterialButton
    private lateinit var detailEyebrow: TextView
    private lateinit var detailBadge: TextView
    private lateinit var detailAgency: TextView
    private lateinit var detailEndpoints: TextView
    private lateinit var directionToggleButton: MaterialButton
    private lateinit var searchRow: View
    private lateinit var queryInput: EditText
    private lateinit var dateButton: MaterialButton
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var favoritesHeader: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_line_times)

        val root = findViewById<View>(R.id.lineTimesRoot)
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

        searchTitle = findViewById(R.id.lineTimesSearchTitle)
        detailHeader = findViewById(R.id.lineTimesDetailHeader)
        backButton = findViewById(R.id.lineTimesBackButton)
        detailEyebrow = findViewById(R.id.lineTimesDetailEyebrow)
        detailBadge = findViewById(R.id.lineTimesDetailBadge)
        detailAgency = findViewById(R.id.lineTimesDetailAgency)
        detailEndpoints = findViewById(R.id.lineTimesDetailEndpoints)
        directionToggleButton = findViewById(R.id.lineTimesDirectionToggleButton)
        searchRow = findViewById(R.id.lineTimesSearchRow)
        queryInput = findViewById(R.id.lineQueryInput)
        dateButton = findViewById(R.id.lineDateButton)
        recycler = findViewById(R.id.departuresRecycler)
        emptyState = findViewById(R.id.lineTimesEmptyState)
        emptyText = findViewById(R.id.lineTimesEmptyText)
        favoritesHeader = findViewById(R.id.lineTimesFavoritesHeader)

        recycler.layoutManager = LinearLayoutManager(this)

        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.search(s?.toString().orEmpty())
            }
        })
        // D-Pad-only devices: nextFocusDown into an empty/not-yet-laid-out
        // RecyclerView doesn't reliably land on its first item, so jump there
        // explicitly (same pattern as SearchLocationActivity/MainActivity).
        queryInput.setOnKeyListener { _, keyCode, event -> maybeFocusFirstItem(keyCode, event) }
        dateButton.setOnKeyListener { _, keyCode, event -> maybeFocusFirstItem(keyCode, event) }
        // backButton/directionToggleButton sit in this same chain (see
        // LineDetail/TripDetail in render()) and need the same explicit jump
        // — default focus search into the recycler is just as unreliable
        // from here as it is from queryInput/dateButton above.
        backButton.setOnKeyListener { _, keyCode, event -> maybeFocusFirstItem(keyCode, event) }
        directionToggleButton.setOnKeyListener { _, keyCode, event -> maybeFocusFirstItem(keyCode, event) }

        backButton.setOnClickListener { goBack() }
        directionToggleButton.setOnClickListener { viewModel.toggleDirection() }
        dateButton.setOnClickListener { pickDate() }

        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.goBack()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun goBack() {
        if (!viewModel.goBack()) finish()
    }

    private fun render(state: LineTimesViewModel.UiState) {
        dateButton.text = SimpleDateFormat("dd/MM", Locale.getDefault()).format(state.dateEpochMillis)

        when (val screen = state.screen) {
            is LineTimesScreen.Search -> {
                searchTitle.visibility = View.VISIBLE
                detailHeader.visibility = View.GONE
                searchRow.visibility = View.VISIBLE
                dateButton.visibility = View.GONE

                if (recycler.adapter !== lineSummaryAdapter) recycler.adapter = lineSummaryAdapter
                lineSummaryAdapter.submitList(state.lineSummaries)
                lineSummaryAdapter.setFavorites(state.favoriteLineIds)

                val showEmpty = !state.isAvailable || (state.searched && state.lineSummaries.isEmpty())
                emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                emptyText.text = when {
                    !state.isAvailable -> getString(R.string.line_times_unavailable)
                    state.showingFavorites -> getString(R.string.line_times_favorites_empty)
                    else -> getString(R.string.line_times_no_results)
                }
                recycler.visibility = if (state.lineSummaries.isEmpty()) View.GONE else View.VISIBLE
                favoritesHeader.visibility =
                    if (state.showingFavorites && state.lineSummaries.isNotEmpty()) View.VISIBLE else View.GONE
            }

            is LineTimesScreen.LineDetail -> {
                searchTitle.visibility = View.GONE
                detailHeader.visibility = View.VISIBLE
                searchRow.visibility = View.GONE
                dateButton.visibility = View.VISIBLE
                favoritesHeader.visibility = View.GONE

                detailEyebrow.text = getString(R.string.line_times_timetable_eyebrow)
                bindLineIdentity(screen.line)
                directionToggleButton.visibility =
                    if (screen.line.pairedRouteId != null) View.VISIBLE else View.GONE

                if (recycler.adapter !== lineDepartureAdapter) recycler.adapter = lineDepartureAdapter
                lineDepartureAdapter.submitList(state.lineDepartures)

                emptyState.visibility = if (state.lineDepartures.isEmpty()) View.VISIBLE else View.GONE
                emptyText.text = getString(R.string.line_times_no_departures)
                recycler.visibility = if (state.lineDepartures.isEmpty()) View.GONE else View.VISIBLE
            }

            is LineTimesScreen.TripDetail -> {
                searchTitle.visibility = View.GONE
                detailHeader.visibility = View.VISIBLE
                searchRow.visibility = View.GONE
                dateButton.visibility = View.GONE
                favoritesHeader.visibility = View.GONE

                detailEyebrow.text = getString(
                    R.string.line_times_trip_stops_eyebrow, GtfsTime.format(screen.departure.departureTime)
                )
                bindLineIdentity(screen.line)
                directionToggleButton.visibility = View.GONE

                if (recycler.adapter !== tripStopAdapter) recycler.adapter = tripStopAdapter
                tripStopAdapter.submitList(state.tripStops)

                emptyState.visibility = if (state.tripStops.isEmpty()) View.VISIBLE else View.GONE
                emptyText.text = getString(R.string.line_times_no_stops)
                recycler.visibility = if (state.tripStops.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun bindLineIdentity(line: LineSummary) {
        detailBadge.text = line.routeShortName
        detailAgency.text = line.agencyName.ifBlank { line.routeLongName }
        detailEndpoints.text = LineSummaryAdapter.endpointsLabel(
            line.firstStopName, line.lastStopName, ContextCompat.getColor(this, R.color.movitop_primary)
        )
    }

    private fun maybeFocusFirstItem(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            (recycler.adapter?.itemCount ?: 0) > 0
        ) {
            focusFirstItem()
            return true
        }
        return false
    }

    private fun focusFirstItem() {
        recycler.post {
            val first = recycler.findViewHolderForAdapterPosition(0)?.itemView
                ?: recycler.layoutManager?.findViewByPosition(0)
            first?.requestFocus()
        }
    }

    private fun pickDate() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = viewModel.uiState.value.dateEpochMillis
        }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(year, month, day, 0, 0, 0)
                // setDate() already re-runs the current line's schedule
                // against the new date (see LineTimesViewModel) — no need to
                // call anything else here too.
                viewModel.setDate(calendar.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
