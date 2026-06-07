package iam699030.gmail.movitop

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import iam699030.gmail.movitop.MainViewModel.RoutingState
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.RouteAdapter
import iam699030.gmail.movitop.data.RouteStepAdapter
import iam699030.gmail.movitop.map.MapThemeHelper
import iam699030.gmail.movitop.search.SearchLocationActivity
import kotlinx.coroutines.launch
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import java.io.File

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var mapView: MapView
    private lateinit var originRow: LinearLayout
    private lateinit var destinationRow: LinearLayout
    private lateinit var originText: TextView
    private lateinit var destinationText: TextView
    private lateinit var searchButton: MaterialButton
    private lateinit var searchProgress: ProgressBar
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var detailsRecycler: RecyclerView
    private lateinit var summaryContainer: LinearLayout
    private lateinit var detailsContainer: LinearLayout
    private lateinit var detailsDuration: TextView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<NestedScrollView>

    private val routeAdapter = RouteAdapter { option -> viewModel.selectRoute(option) }
    private val stepAdapter = RouteStepAdapter()

    private var routeOverlay: Polyline? = null

    private val searchLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(SearchLocationActivity.EXTRA_PLACE_NAME).orEmpty()
        if (name.isEmpty()) return@registerForActivityResult

        when (pendingSearchField) {
            SearchLocationActivity.FIELD_ORIGIN -> {
                viewModel.setOrigin(name)
                originText.text = name
            }
            SearchLocationActivity.FIELD_DESTINATION -> {
                viewModel.setDestination(name)
                destinationText.text = name
            }
        }
    }

    private var pendingSearchField: String = SearchLocationActivity.FIELD_ORIGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contentRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapView = findViewById(R.id.mapView)
        originRow = findViewById(R.id.originRow)
        destinationRow = findViewById(R.id.destinationRow)
        originText = findViewById(R.id.originText)
        destinationText = findViewById(R.id.destinationText)
        searchButton = findViewById(R.id.searchButton)
        searchProgress = findViewById(R.id.searchProgress)
        resultsRecycler = findViewById(R.id.resultsRecycler)
        detailsRecycler = findViewById(R.id.detailsRecycler)
        summaryContainer = findViewById(R.id.summaryContainer)
        detailsContainer = findViewById(R.id.detailsContainer)
        detailsDuration = findViewById(R.id.detailsDuration)

        setupMap()
        setupResultsSheet()
        setupSearchForm()
        observeViewModel()

        MotisForegroundService.start(this)
    }

    private fun setupMap() {
        mapView.mapScaleBar.isVisible = false
        mapView.setBuiltInZoomControls(false)
        mapView.isClickable = true

        val tileCache = AndroidUtil.createTileCache(
            this,
            "movitop_tiles_v2",
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor
        )

        val mapFile = File(getExternalFilesDir(null), "motis_data/israel.map")
        if (mapFile.exists()) {
            val mapStore = MapFile(mapFile)
            val rendererLayer = TileRendererLayer(
                tileCache,
                mapStore,
                mapView.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE
            )
            rendererLayer.setXmlRenderTheme(MapThemeHelper.load(this))
            mapView.layerManager.layers.add(rendererLayer)
        } else {
            Log.w(TAG, getString(R.string.map_missing_log, mapFile.absolutePath))
        }

        mapView.model.mapViewPosition.center = LatLong(31.9, 35.0)
        mapView.model.mapViewPosition.zoomLevel = 8.toByte()
    }

    private fun drawPolyline(points: List<GeoPoint>) {
        clearPolyline()
        if (points.isEmpty()) return

        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.movitop_primary)
            strokeWidth = 18f
            setStyle(Style.STROKE)
        }
        val polyline = Polyline(paint, AndroidGraphicFactory.INSTANCE)
        points.forEach { polyline.latLongs.add(LatLong(it.lat, it.lon)) }
        mapView.layerManager.layers.add(polyline)
        routeOverlay = polyline

        mapView.model.mapViewPosition.center = LatLong(points.first().lat, points.first().lon)
        mapView.model.mapViewPosition.zoomLevel = 10.toByte()
    }

    private fun clearPolyline() {
        routeOverlay?.let { mapView.layerManager.layers.remove(it) }
        routeOverlay = null
    }

    private fun setupResultsSheet() {
        val sheet = findViewById<NestedScrollView>(R.id.resultsBottomSheet)
        resultsRecycler.layoutManager = LinearLayoutManager(this)
        resultsRecycler.adapter = routeAdapter
        detailsRecycler.layoutManager = LinearLayoutManager(this)
        detailsRecycler.adapter = stepAdapter

        bottomSheetBehavior = BottomSheetBehavior.from(sheet).apply {
            isHideable = true
            peekHeight = 0
            state = BottomSheetBehavior.STATE_HIDDEN
        }
        bottomSheetBehavior.addBottomSheetCallback(object :
            BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        val target = if (detailsContainer.visibility == View.VISIBLE) {
                            detailsRecycler
                        } else {
                            resultsRecycler
                        }
                        focusFirstChild(target)
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> updateFocusChain()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.uiState.value.routingState is RoutingState.ViewingRouteDetails ->
                        viewModel.backToSummary()
                    bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN ->
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun setupSearchForm() {
        originRow.setOnClickListener { openSearch(SearchLocationActivity.FIELD_ORIGIN) }
        destinationRow.setOnClickListener { openSearch(SearchLocationActivity.FIELD_DESTINATION) }

        originRow.setOnKeyListener { _, keyCode, event ->
            handleRowKey(originRow, keyCode, event) {
                openSearch(SearchLocationActivity.FIELD_ORIGIN)
            }
        }
        destinationRow.setOnKeyListener { _, keyCode, event ->
            handleRowKey(destinationRow, keyCode, event) {
                openSearch(SearchLocationActivity.FIELD_DESTINATION)
            }
        }

        searchButton.setOnClickListener { performSearch() }
        searchButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                performSearch()
                true
            } else {
                false
            }
        }

        originRow.requestFocus()
    }

    private fun handleRowKey(view: View, keyCode: Int, event: KeyEvent, onSelect: () -> Unit): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            onSelect()
            return true
        }
        return false
    }

    private fun openSearch(fieldType: String) {
        pendingSearchField = fieldType
        val initialQuery = when (fieldType) {
            SearchLocationActivity.FIELD_DESTINATION -> viewModel.uiState.value.destinationQuery
            else -> viewModel.uiState.value.originQuery
        }
        val intent = Intent(this, SearchLocationActivity::class.java).apply {
            putExtra(SearchLocationActivity.EXTRA_FIELD_TYPE, fieldType)
            putExtra(SearchLocationActivity.EXTRA_INITIAL_QUERY, initialQuery)
        }
        searchLocationLauncher.launch(intent)
    }

    private fun performSearch() {
        val origin = viewModel.uiState.value.originQuery.trim()
        val destination = viewModel.uiState.value.destinationQuery.trim()
        if (origin.isEmpty() || destination.isEmpty()) return
        viewModel.search()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        if (originText.text.toString() != state.originQuery) {
                            originText.text = state.originQuery
                        }
                        if (destinationText.text.toString() != state.destinationQuery) {
                            destinationText.text = state.destinationQuery
                        }
                        searchProgress.visibility =
                            if (state.routingState == RoutingState.Calculating) View.VISIBLE
                            else View.GONE
                        searchButton.isEnabled = state.routingState != RoutingState.Calculating
                        renderRoutingState(state.routingState)
                        state.errorMessage?.let { message ->
                            Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_LONG)
                                .show()
                            viewModel.clearError()
                        }
                    }
                }
                launch {
                    viewModel.routes.collect { routes ->
                        routeAdapter.submitList(routes.orEmpty()) {
                            if (routes != null && summaryContainer.visibility == View.VISIBLE) {
                                updateFocusChain()
                            }
                        }
                    }
                }
                launch {
                    viewModel.routeDetail.collect { detail ->
                        stepAdapter.submitList(detail?.steps.orEmpty()) {
                            if (detail != null && detailsContainer.visibility == View.VISIBLE) {
                                focusFirstChild(detailsRecycler)
                                updateFocusChain()
                            }
                        }
                        if (detail != null) drawPolyline(detail.polyline) else clearPolyline()
                    }
                }
            }
        }
    }

    private fun renderRoutingState(state: RoutingState) {
        when (state) {
            RoutingState.ResultsReady -> {
                showSummaryPane()
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                focusFirstChild(resultsRecycler)
                updateFocusChain()
            }
            is RoutingState.ViewingRouteDetails -> {
                showDetailsPane()
                detailsDuration.text = formatDuration(state.selectedRoute.durationMinutes)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            else -> {
                if (state !is RoutingState.Calculating) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
                updateFocusChain()
            }
        }
    }

    private fun showSummaryPane() {
        summaryContainer.visibility = View.VISIBLE
        detailsContainer.visibility = View.GONE
    }

    private fun showDetailsPane() {
        summaryContainer.visibility = View.GONE
        detailsContainer.visibility = View.VISIBLE
    }

    private fun updateFocusChain() {
        val sheetExpanded = bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED
        val activeRecycler = if (detailsContainer.visibility == View.VISIBLE) {
            detailsRecycler
        } else {
            resultsRecycler
        }

        if (sheetExpanded) {
            activeRecycler.post {
                val firstItem = activeRecycler.findViewHolderForAdapterPosition(0)?.itemView
                    ?: activeRecycler.layoutManager?.findViewByPosition(0)
                if (firstItem != null) {
                    searchButton.nextFocusDownId = firstItem.id
                    firstItem.nextFocusUpId = searchButton.id
                } else {
                    searchButton.nextFocusDownId = R.id.resultsBottomSheet
                    findViewById<View>(R.id.resultsBottomSheet).nextFocusUpId = searchButton.id
                }
            }
        } else {
            searchButton.nextFocusDownId = View.NO_ID
        }
    }

    private fun focusFirstChild(recycler: RecyclerView) {
        recycler.post {
            val firstItem = recycler.findViewHolderForAdapterPosition(0)?.itemView
                ?: recycler.layoutManager?.findViewByPosition(0)
            firstItem?.requestFocus()
            updateFocusChain()
        }
    }

    private fun formatDuration(minutes: Int): String =
        if (minutes >= 60) {
            val hours = minutes / 60.0
            val hoursText = if (hours % 1.0 == 0.0) hours.toInt().toString()
            else String.format("%.1f", hours)
            getString(R.string.duration_hours, hoursText)
        } else {
            getString(R.string.duration_minutes, minutes)
        }

    override fun onDestroy() {
        clearPolyline()
        mapView.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
