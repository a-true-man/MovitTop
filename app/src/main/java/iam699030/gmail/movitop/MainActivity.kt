package iam699030.gmail.movitop

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import iam699030.gmail.movitop.MainViewModel.RoutingState
import iam699030.gmail.movitop.data.CallableStation
import iam699030.gmail.movitop.data.DirectModeAdapter
import iam699030.gmail.movitop.data.DriverStations
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.MapLeg
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.NearbyDeparture
import iam699030.gmail.movitop.data.RavKavStationsRepository
import iam699030.gmail.movitop.data.RealMotisRepository
import iam699030.gmail.movitop.data.RouteAdapter
import iam699030.gmail.movitop.data.RouteOption
import iam699030.gmail.movitop.data.RouteSwitchAdapter
import iam699030.gmail.movitop.data.LineScheduleAdapter
import iam699030.gmail.movitop.data.StopLineAdapter
import iam699030.gmail.movitop.data.groupByLine
import iam699030.gmail.movitop.data.TaxiStations
import iam699030.gmail.movitop.data.TransitStopPlace
import iam699030.gmail.movitop.data.TransportMode
import iam699030.gmail.movitop.data.RouteStepAdapter
import iam699030.gmail.movitop.data.TripTime
import iam699030.gmail.movitop.map.MapThemeHelper
import iam699030.gmail.movitop.map.StationMarkersController
import iam699030.gmail.movitop.nav.LocationTracker
import iam699030.gmail.movitop.util.tickEvery
import iam699030.gmail.movitop.nav.PendingNavigation
import iam699030.gmail.movitop.nearby.NearbyActivity
import iam699030.gmail.movitop.search.SearchLocationActivity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
    private lateinit var tripTimeRow: TextView
    private lateinit var searchProgress: ProgressBar
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var directModesRecycler: RecyclerView
    private lateinit var directModesTitle: TextView
    private lateinit var transitOptionsTitle: TextView
    private lateinit var detailsRecycler: RecyclerView
    private lateinit var routeSwitchTitle: TextView
    private lateinit var routeSwitchRecycler: RecyclerView
    private lateinit var summaryContainer: LinearLayout
    private lateinit var detailsContainer: LinearLayout
    private lateinit var detailsDuration: TextView
    private lateinit var startNavigationButton: MaterialButton
    private lateinit var stopDeparturesContainer: LinearLayout
    private lateinit var stopDeparturesTitle: TextView
    private lateinit var stopBackButton: View
    private lateinit var stopDeparturesRecycler: RecyclerView
    private lateinit var stopDeparturesEmpty: TextView
    private lateinit var settingsButton: MaterialButton
    private lateinit var nearbyButton: MaterialButton
    private lateinit var lineTimesButton: MaterialButton
    private lateinit var zoomInButton: MaterialButton
    private lateinit var zoomOutButton: MaterialButton
    private lateinit var myLocationButton: MaterialButton
    private lateinit var dataImportButton: MaterialButton

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) centerOnMyLocation() }

    // Android 13+ hides a foreground service's notification (the on-device
    // routing engine's only visible sign of life) until this is granted —
    // requested once up front so the service doesn't start invisibly.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<NestedScrollView>

    private val routeAdapter = RouteAdapter { option -> viewModel.selectRoute(option) }
    private val directModeAdapter = DirectModeAdapter(
        onRouteSelected = { option -> handleRouteOptionTap(option) },
        onDownPressed = { focusFirstTransitOptionIfAny() }
    )
    private val stepAdapter = RouteStepAdapter()
    private val routeSwitchAdapter = RouteSwitchAdapter(
        onRouteSelected = { option -> handleRouteOptionTap(option) },
        onDownPressed = { focusFirstDetailStepIfAny() }
    )

    // Every option from the last search, kept for the detail pane's
    // quick-switch row (see routeSwitchAdapter) — the ViewModel's own
    // `routes` flow isn't re-collected there, so MainActivity caches it.
    private var allRoutes: List<RouteOption> = emptyList()

    private val routeOverlays = mutableListOf<Polyline>()

    private val motisRepository: MotisRepository by lazy { RealMotisRepository(this) }
    private val ravKavRepository: RavKavStationsRepository by lazy { RavKavStationsRepository(this) }
    private lateinit var stationMarkersController: StationMarkersController

    // Top-level pane: one card per line (see GroupedLineDeparture). Drilled-in
    // pane: that one line's full schedule at this stop, times only (see
    // LineScheduleAdapter's kdoc for why it doesn't repeat the line badge).
    // Both are bound to the same stopDeparturesRecycler, swapped in as its
    // .adapter depending on which pane is showing.
    private val stopLineAdapter = StopLineAdapter { grouped -> showLineScheduleAtStop(grouped.routeShortName) }
    private val lineScheduleAdapter = LineScheduleAdapter()

    // The stop a map tap last opened the results sheet for, and its last
    // fetched departures — kept so the "back" arrow from a line's full
    // schedule (see showLineScheduleAtStop) can restore the mixed list
    // without a second network round trip.
    private var currentStopContext: TransitStopPlace? = null
    private var currentStopDepartures: List<NearbyDeparture> = emptyList()

    private val searchLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(SearchLocationActivity.EXTRA_PLACE_NAME).orEmpty()
        if (name.isEmpty()) return@registerForActivityResult

        // (0,0) is SearchLocationActivity's "not actually resolved" sentinel
        // for local-only name suggestions (see PlaceSuggestions) — only carry
        // a coordinate through when it's a real pick, so getRoutes() still
        // falls back to geocoding the name by text in that case.
        val lat = data.getDoubleExtra(SearchLocationActivity.EXTRA_PLACE_LAT, 0.0)
        val lon = data.getDoubleExtra(SearchLocationActivity.EXTRA_PLACE_LON, 0.0)
        val coord = if (lat != 0.0 || lon != 0.0) GeoPoint(lat, lon) else null

        when (pendingSearchField) {
            SearchLocationActivity.FIELD_ORIGIN -> {
                viewModel.setOrigin(name, coord)
                originText.text = name
            }
            SearchLocationActivity.FIELD_DESTINATION -> {
                viewModel.setDestination(name, coord)
                destinationText.text = name
            }
        }
    }

    // NearbyActivity returns a stop the exact same way SearchLocationActivity
    // returns a place — picking a nearby departure means "I'll board here",
    // so it always lands in the origin field.
    private val nearbyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(SearchLocationActivity.EXTRA_PLACE_NAME).orEmpty()
        if (name.isEmpty()) return@registerForActivityResult
        val lat = data.getDoubleExtra(SearchLocationActivity.EXTRA_PLACE_LAT, 0.0)
        val lon = data.getDoubleExtra(SearchLocationActivity.EXTRA_PLACE_LON, 0.0)
        viewModel.setOrigin(name, GeoPoint(lat, lon))
        originText.text = name
    }

    private var pendingSearchField: String = SearchLocationActivity.FIELD_ORIGIN
    private var noOfflineDataSnackbar: Snackbar? = null

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
        tripTimeRow = findViewById(R.id.tripTimeRow)
        tripTimeRow.setOnClickListener { showTripTimeDialog() }
        searchProgress = findViewById(R.id.searchProgress)
        resultsRecycler = findViewById(R.id.resultsRecycler)
        directModesRecycler = findViewById(R.id.directModesRecycler)
        directModesTitle = findViewById(R.id.directModesTitle)
        transitOptionsTitle = findViewById(R.id.transitOptionsTitle)
        detailsRecycler = findViewById(R.id.detailsRecycler)
        routeSwitchTitle = findViewById(R.id.routeSwitchTitle)
        routeSwitchRecycler = findViewById(R.id.routeSwitchRecycler)
        summaryContainer = findViewById(R.id.summaryContainer)
        detailsContainer = findViewById(R.id.detailsContainer)
        detailsDuration = findViewById(R.id.detailsDuration)
        startNavigationButton = findViewById(R.id.startNavigationButton)
        startNavigationButton.setOnClickListener { startLiveNavigation() }
        stopDeparturesContainer = findViewById(R.id.stopDeparturesContainer)
        stopDeparturesTitle = findViewById(R.id.stopDeparturesTitle)
        stopBackButton = findViewById(R.id.stopBackButton)
        stopBackButton.setOnClickListener { returnToStopDepartures() }
        stopDeparturesRecycler = findViewById(R.id.stopDeparturesRecycler)
        stopDeparturesEmpty = findViewById(R.id.stopDeparturesEmpty)
        settingsButton = findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        nearbyButton = findViewById(R.id.nearbyButton)
        nearbyButton.setOnClickListener { nearbyLauncher.launch(Intent(this, NearbyActivity::class.java)) }
        lineTimesButton = findViewById(R.id.lineTimesButton)
        lineTimesButton.setOnClickListener { startActivity(Intent(this, LineTimesActivity::class.java)) }
        zoomInButton = findViewById(R.id.zoomInButton)
        zoomOutButton = findViewById(R.id.zoomOutButton)
        myLocationButton = findViewById(R.id.myLocationButton)
        dataImportButton = findViewById(R.id.dataImportButton)
        zoomInButton.setOnClickListener { zoomBy(1) }
        zoomOutButton.setOnClickListener { zoomBy(-1) }
        myLocationButton.setOnClickListener { requestMyLocation() }
        dataImportButton.setOnClickListener {
            startActivity(Intent(this, DataImportActivity::class.java))
        }

        setupMap()
        setupResultsSheet()
        setupSearchForm()
        observeViewModel()

        requestNotificationPermissionIfNeeded()
        MotisForegroundService.start(this)

        // Re-render already-fetched "leaves in X" labels on a timer so they
        // stay accurate as time passes, without a fresh engine query — see
        // RelativeTime. Covers the route-search summary cards and whichever
        // of the two stop-tap adapters is currently attached.
        tickEvery(TICK_INTERVAL_MILLIS) {
            routeAdapter.notifyDataSetChanged()
            stopDeparturesRecycler.adapter?.notifyDataSetChanged()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-checked here (not just onCreate) so the banner clears itself as
        // soon as the user comes back from DataImportActivity having picked
        // a package — no need to relaunch the app to see it disappear.
        checkOfflineDataAvailability()
    }

    /**
     * A fresh install (or one whose graph was never pushed) has no routable
     * data at all — every search then silently fails with the same generic
     * network-error text as a real outage, and the map just renders empty.
     * Surface that state explicitly instead, with a direct way to fix it.
     */
    private fun checkOfflineDataAvailability() {
        if (MotisForegroundService.hasOfflineData(this)) {
            noOfflineDataSnackbar?.dismiss()
            noOfflineDataSnackbar = null
            return
        }
        if (noOfflineDataSnackbar?.isShown == true) return
        noOfflineDataSnackbar = Snackbar.make(
            findViewById(R.id.main), R.string.no_offline_data_banner, Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.no_offline_data_action) {
            startActivity(Intent(this, DataImportActivity::class.java))
        }.also { it.show() }
    }

    private fun setupMap() {
        mapView.mapScaleBar.isVisible = false
        mapView.setBuiltInZoomControls(false)
        mapView.isClickable = true

        // Pinch/drag only works on a touchscreen — keypad-only devices need
        // an explicit way in, so D-Pad arrows pan the map once it has focus
        // (entered via dataImportButton's chain), and center returns focus
        // to the button stack rather than trapping it inside the map.
        mapView.isFocusable = true
        mapView.isFocusableInTouchMode = true
        mapView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val panFraction = 0.25
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { panBy(0.0, -panFraction); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { panBy(0.0, panFraction); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { panBy(-panFraction, 0.0); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { panBy(panFraction, 0.0); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    zoomInButton.requestFocus(); true
                }
                else -> false
            }
        }

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

        // Transit-stop + Rav-Kav charging-station markers (see StationMarkersController):
        // only appear once zoomed in, recomputed for the viewport on every pan/zoom.
        stationMarkersController = StationMarkersController(
            context = this,
            mapView = mapView,
            scope = lifecycleScope,
            motisRepository = motisRepository,
            ravKavRepository = ravKavRepository,
            onStopSelected = { stop -> showStopDepartures(stop) }
        )
        stationMarkersController.start()
    }

    private fun zoomBy(delta: Int) {
        val position = mapView.model.mapViewPosition
        val newZoom = (position.zoomLevel + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        position.zoomLevel = newZoom.toByte()
    }

    /** Shifts the map center by a fraction of one map tile's span at the current zoom. */
    private fun panBy(fractionLon: Double, fractionLat: Double) {
        val position = mapView.model.mapViewPosition
        val tilesAcross = 1 shl position.zoomLevel.toInt()
        val degreesPerTileLon = 360.0 / tilesAcross
        val degreesPerTileLat = 170.0 / tilesAcross // rough equirectangular approximation
        val center = position.center
        position.center = LatLong(
            (center.latitude + fractionLat * degreesPerTileLat).coerceIn(-85.0, 85.0),
            center.longitude + fractionLon * degreesPerTileLon
        )
    }

    /** Requests one GPS fix (satellite-only, see [LocationTracker]) and recenters the map on it. */
    private fun requestMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            centerOnMyLocation()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun centerOnMyLocation() {
        lifecycleScope.launch {
            val point = withTimeoutOrNull(10_000L) {
                LocationTracker(this@MainActivity).updates().firstOrNull()
            }
            if (point == null) {
                Snackbar.make(findViewById(R.id.main), R.string.map_location_unavailable, Snackbar.LENGTH_LONG)
                    .show()
                return@launch
            }
            mapView.model.mapViewPosition.center = LatLong(point.lat, point.lon)
            mapView.model.mapViewPosition.zoomLevel = 15.toByte()
        }
    }

    /** Draws each leg as its own colored segment (see [LegColors]) — a line change or a walk is then visible at a glance. */
    private fun drawRouteLegs(legs: List<MapLeg>) {
        clearPolyline()
        val allPoints = legs.flatMap { it.points }
        if (allPoints.isEmpty()) return

        legs.forEach { leg ->
            if (leg.points.size < 2) return@forEach
            val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = if (leg.colorArgb != 0) {
                    leg.colorArgb
                } else {
                    ContextCompat.getColor(this@MainActivity, R.color.movitop_primary)
                }
                strokeWidth = 18f
                setStyle(Style.STROKE)
            }
            val polyline = Polyline(paint, AndroidGraphicFactory.INSTANCE)
            leg.points.forEach { polyline.latLongs.add(LatLong(it.lat, it.lon)) }
            mapView.layerManager.layers.add(polyline)
            routeOverlays += polyline
        }

        mapView.model.mapViewPosition.center = LatLong(allPoints.first().lat, allPoints.first().lon)
        mapView.model.mapViewPosition.zoomLevel = 10.toByte()
    }

    private fun clearPolyline() {
        routeOverlays.forEach { mapView.layerManager.layers.remove(it) }
        routeOverlays.clear()
    }

    private fun setupResultsSheet() {
        val sheet = findViewById<NestedScrollView>(R.id.resultsBottomSheet)
        resultsRecycler.layoutManager = LinearLayoutManager(this)
        resultsRecycler.adapter = routeAdapter
        directModesRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        directModesRecycler.adapter = directModeAdapter
        detailsRecycler.layoutManager = LinearLayoutManager(this)
        detailsRecycler.adapter = stepAdapter
        routeSwitchRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        routeSwitchRecycler.adapter = routeSwitchAdapter
        stopDeparturesRecycler.layoutManager = LinearLayoutManager(this)
        stopDeparturesRecycler.adapter = stopLineAdapter

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
                        val target = when {
                            stopDeparturesContainer.visibility == View.VISIBLE -> stopDeparturesRecycler
                            detailsContainer.visibility == View.VISIBLE -> detailsRecycler
                            else -> resultsRecycler
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
                    stopBackButton.visibility == View.VISIBLE -> returnToStopDepartures()
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

    private fun startLiveNavigation() {
        val steps = viewModel.routeDetail.value?.navigationSteps
        if (steps.isNullOrEmpty()) {
            Snackbar.make(findViewById(R.id.main), R.string.nav_unavailable, Snackbar.LENGTH_SHORT).show()
            return
        }
        PendingNavigation.steps = steps
        startActivity(Intent(this, LiveNavigationActivity::class.java))
    }

    private fun performSearch() {
        val origin = viewModel.uiState.value.originQuery.trim()
        val destination = viewModel.uiState.value.destinationQuery.trim()
        if (origin.isEmpty() || destination.isEmpty()) return
        viewModel.search()
    }

    private fun formatTripTime(tripTime: TripTime): String {
        val epoch = tripTime.epochMillis ?: return getString(R.string.trip_time_now)
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(epoch))
        return if (tripTime.arriveBy) {
            getString(R.string.trip_time_arrive_by, timeText)
        } else {
            getString(R.string.trip_time_depart_at, timeText)
        }
    }

    /**
     * DRIVER/TAXI cards don't lead to the map/step detail pane like every
     * other mode — there's nothing useful to navigate ("drive to X" isn't a
     * turn-by-turn route the app owns) — instead they open a station picker
     * the user calls to actually book the ride.
     */
    private fun handleRouteOptionTap(option: RouteOption) {
        when (option.mode) {
            TransportMode.DRIVER -> showDriverStationsDialog()
            TransportMode.TAXI -> showTaxiStationsDialog()
            else -> viewModel.selectRoute(option)
        }
    }

    private fun showDriverStationsDialog() {
        showCarServiceStationsDialog(
            title = getString(R.string.driver_stations_dialog_title),
            subtitle = getString(R.string.driver_stations_dialog_subtitle),
            stations = DriverStations.nationwide
        )
    }

    private fun showTaxiStationsDialog() {
        lifecycleScope.launch {
            val cityStations = TaxiStations.nearest(resolveOriginPoint())
            showCarServiceStationsDialog(
                title = getString(R.string.taxi_stations_dialog_title, cityStations.city),
                subtitle = getString(R.string.taxi_stations_dialog_subtitle),
                stations = cityStations.stations
            )
        }
    }

    /**
     * The exact point the user picked (search result / GPS fix) when there is
     * one — otherwise best-effort geocode of the free-text origin, so the
     * taxi station list still matches the right city instead of silently
     * defaulting to whichever city happens to be first in [TaxiStations].
     */
    private suspend fun resolveOriginPoint(): GeoPoint? {
        viewModel.uiState.value.originCoord?.let { return it }
        val query = viewModel.uiState.value.originQuery.trim()
        if (query.isEmpty()) return null
        return try {
            motisRepository.geocodePlaces(query).firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't geocode \"$query\" for taxi-station lookup", e)
            null
        }
    }

    private fun showCarServiceStationsDialog(title: String, subtitle: String, stations: List<CallableStation>) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_car_service_stations, null)
        view.findViewById<TextView>(R.id.carServiceStationsTitle).text = title
        view.findViewById<TextView>(R.id.carServiceStationsSubtitle).text = subtitle

        val list = view.findViewById<LinearLayout>(R.id.carServiceStationsList)
        stations.forEach { station ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_car_service_station, list, false)
            row.findViewById<TextView>(R.id.stationName).text = station.name
            row.findViewById<TextView>(R.id.stationSubtitle).text =
                listOfNotNull(formatPhoneForDisplay(station.phone), station.subtitle).joinToString(" · ")
            row.setOnClickListener { dialPhone(station.phone) }
            list.addView(row)
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton(R.string.dialog_close_button, null)
            .show()
    }

    private fun dialPhone(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No dialer app available for tel:$phone", e)
        }
    }

    private fun formatPhoneForDisplay(phone: String): String = when (phone.length) {
        9 -> "${phone.substring(0, 2)}-${phone.substring(2)}"
        10 -> "${phone.substring(0, 3)}-${phone.substring(3, 6)}-${phone.substring(6)}"
        else -> phone
    }

    private fun showTripTimeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_trip_time, null)
        val modeDepart = view.findViewById<MaterialButton>(R.id.modeDepartButton)
        val modeArrive = view.findViewById<MaterialButton>(R.id.modeArriveButton)
        val chipNow = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipNow)
        val chip15 = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip15)
        val chip30 = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip30)
        val chip60 = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip60)
        val pickCustom = view.findViewById<MaterialButton>(R.id.pickCustomButton)

        var arriveBy = viewModel.uiState.value.tripTime.arriveBy
        fun refreshModeButtons() {
            val selectedColor = ContextCompat.getColor(this, R.color.movitop_primary)
            val unselectedColor = ContextCompat.getColor(this, R.color.movitop_chip_bg)
            modeDepart.setBackgroundColor(if (!arriveBy) selectedColor else unselectedColor)
            modeArrive.setBackgroundColor(if (arriveBy) selectedColor else unselectedColor)
        }
        refreshModeButtons()
        modeDepart.setOnClickListener { arriveBy = false; refreshModeButtons() }
        modeArrive.setOnClickListener { arriveBy = true; refreshModeButtons() }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        fun applyAndDismiss(epochMillis: Long?) {
            viewModel.setTripTime(TripTime(epochMillis, arriveBy))
            dialog.dismiss()
        }

        chipNow.setOnClickListener { applyAndDismiss(null) }
        chip15.setOnClickListener { applyAndDismiss(System.currentTimeMillis() + 15 * 60_000L) }
        chip30.setOnClickListener { applyAndDismiss(System.currentTimeMillis() + 30 * 60_000L) }
        chip60.setOnClickListener { applyAndDismiss(System.currentTimeMillis() + 60 * 60_000L) }
        pickCustom.setOnClickListener {
            dialog.dismiss()
            pickCustomDateTime { epochMillis -> viewModel.setTripTime(TripTime(epochMillis, arriveBy)) }
        }

        dialog.show()
    }

    private fun pickCustomDateTime(onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        onPicked(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
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
                        tripTimeRow.text = formatTripTime(state.tripTime)
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
                        // Direct modes (walk/bike/driver/taxi) render as a
                        // horizontal row above the transit list, matching how
                        // Moovit separates them instead of one mixed list.
                        val all = routes.orEmpty()
                        allRoutes = all
                        routeSwitchAdapter.submitList(all)
                        val direct = all.filter { it.mode != TransportMode.TRANSIT }
                        val transit = all.filter { it.mode == TransportMode.TRANSIT }

                        directModesTitle.visibility = if (direct.isNotEmpty()) View.VISIBLE else View.GONE
                        directModesRecycler.visibility = if (direct.isNotEmpty()) View.VISIBLE else View.GONE
                        transitOptionsTitle.visibility = if (transit.isNotEmpty()) View.VISIBLE else View.GONE

                        // ListAdapter.submitList diffs on a background thread —
                        // expanding the sheet right after calling it (as this
                        // used to) raced the RecyclerView actually being
                        // populated, so BottomSheetBehavior's wrap_content
                        // measurement could compute against zero items and the
                        // sheet would render as an empty sliver. Only expand
                        // once both commit callbacks confirm their list landed.
                        directModeAdapter.submitList(direct) {
                            routeAdapter.submitList(transit) {
                                if (routes != null) {
                                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                                    focusFirstChild(if (transit.isNotEmpty()) resultsRecycler else directModesRecycler)
                                    updateFocusChain()
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.routeDetail.collect { detail ->
                        stepAdapter.submitList(detail?.steps.orEmpty()) {
                            if (detail != null) {
                                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                                focusFirstChild(detailsRecycler)
                                updateFocusChain()
                            }
                        }
                        if (detail != null) drawRouteLegs(detail.legs) else clearPolyline()
                    }
                }
            }
        }
    }

    private fun renderRoutingState(state: RoutingState) {
        when (state) {
            RoutingState.ResultsReady -> {
                // Sheet expansion happens in the routes-collector's submitList
                // commit callback instead (see observeViewModel) — doing it
                // here would race the RecyclerView's async diff.
                showSummaryPane()
            }
            is RoutingState.ViewingRouteDetails -> {
                showDetailsPane()
                detailsDuration.text = formatDuration(state.selectedRoute.durationMinutes)
                routeSwitchAdapter.setSelectedId(state.selectedRoute.id)
                val showSwitcher = allRoutes.size > 1
                routeSwitchTitle.visibility = if (showSwitcher) View.VISIBLE else View.GONE
                routeSwitchRecycler.visibility = if (showSwitcher) View.VISIBLE else View.GONE
            }
            else -> {
                // Every uiState emission re-renders here, including ones
                // unrelated to routing (e.g. a keystroke in the search
                // fields) — don't let an unchanged Idle state clobber a
                // stop-departures sheet the user has open from a map tap.
                if (state !is RoutingState.Calculating && stopDeparturesContainer.visibility != View.VISIBLE) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
                updateFocusChain()
            }
        }
    }

    private fun showSummaryPane() {
        summaryContainer.visibility = View.VISIBLE
        detailsContainer.visibility = View.GONE
        stopDeparturesContainer.visibility = View.GONE
    }

    private fun showDetailsPane() {
        summaryContainer.visibility = View.GONE
        detailsContainer.visibility = View.VISIBLE
        stopDeparturesContainer.visibility = View.GONE
    }

    private fun showStopPane() {
        summaryContainer.visibility = View.GONE
        detailsContainer.visibility = View.GONE
        stopDeparturesContainer.visibility = View.VISIBLE
    }

    /**
     * Tapping a transit-stop map marker (see StationMarkersController) lands
     * here instead of a popup — it drives the same results bottom sheet a
     * route search uses, showing that stop's upcoming departures (mixed
     * lines, soonest first) so the whole app has one place for "what's
     * coming and when".
     */
    private fun showStopDepartures(stop: TransitStopPlace) {
        currentStopContext = stop
        currentStopDepartures = emptyList()
        stopBackButton.visibility = View.GONE
        stopDeparturesTitle.text = stop.name
        stopDeparturesEmpty.visibility = View.GONE
        stopDeparturesRecycler.adapter = stopLineAdapter
        showStopPane()

        lifecycleScope.launch {
            val departures = motisRepository.departuresAtStop(stop.stopId, stop.point)
            if (currentStopContext?.stopId != stop.stopId) return@launch // user moved to a different stop meanwhile
            currentStopDepartures = departures
            stopDeparturesEmpty.visibility = if (departures.isEmpty()) View.VISIBLE else View.GONE
            // Expand only once the list has actually landed (its diff runs on
            // a background thread) — expanding first would size the sheet
            // against zero items, same trap the route-results sheet avoids
            // (see the routes.collect commit-callback comment in observeViewModel).
            stopLineAdapter.submitList(departures.groupByLine()) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                focusFirstChild(stopDeparturesRecycler)
            }
        }
    }

    /** Drills into one line's full remaining schedule for today at the current stop. */
    private fun showLineScheduleAtStop(routeShortName: String) {
        val stop = currentStopContext ?: return
        stopBackButton.visibility = View.VISIBLE
        stopDeparturesTitle.text = getString(R.string.stop_line_schedule_title, routeShortName, stop.name)
        stopDeparturesEmpty.visibility = View.GONE
        stopDeparturesRecycler.adapter = lineScheduleAdapter
        lineScheduleAdapter.submitList(emptyList())

        lifecycleScope.launch {
            val all = motisRepository.departuresAtStop(
                stop.stopId, stop.point,
                windowSeconds = FULL_SCHEDULE_WINDOW_SECONDS,
                n = FULL_SCHEDULE_COUNT
            )
            if (currentStopContext?.stopId != stop.stopId) return@launch
            val times = all.filter { it.routeShortName == routeShortName }
                .map { it.departEpochMillis }
                .sorted()
            lineScheduleAdapter.submitList(times)
            stopDeparturesEmpty.visibility = if (times.isEmpty()) View.VISIBLE else View.GONE
            focusFirstChild(stopDeparturesRecycler)
        }
    }

    /** Back arrow from a drilled-into line schedule, to the stop's full per-line summary list. */
    private fun returnToStopDepartures() {
        val stop = currentStopContext ?: return
        stopBackButton.visibility = View.GONE
        stopDeparturesTitle.text = stop.name
        stopDeparturesRecycler.adapter = stopLineAdapter
        stopLineAdapter.submitList(currentStopDepartures.groupByLine())
        stopDeparturesEmpty.visibility = if (currentStopDepartures.isEmpty()) View.VISIBLE else View.GONE
        focusFirstChild(stopDeparturesRecycler)
    }

    private fun updateFocusChain() {
        val sheetExpanded = bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED
        val activeRecycler = when {
            stopDeparturesContainer.visibility == View.VISIBLE -> stopDeparturesRecycler
            detailsContainer.visibility == View.VISIBLE -> detailsRecycler
            resultsRecycler.visibility == View.VISIBLE -> resultsRecycler
            else -> directModesRecycler
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
            searchButton.nextFocusDownId = R.id.zoomInButton
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

    /** DPAD_DOWN from the last row of the horizontal direct-modes carousel (see DirectModeAdapter). */
    private fun focusFirstTransitOptionIfAny(): Boolean {
        if (resultsRecycler.visibility != View.VISIBLE || routeAdapter.itemCount == 0) return false
        val firstItem = resultsRecycler.findViewHolderForAdapterPosition(0)?.itemView
            ?: resultsRecycler.layoutManager?.findViewByPosition(0)
        firstItem?.requestFocus() ?: return false
        return true
    }

    /** DPAD_DOWN from the horizontal route quick-switch row (see RouteSwitchAdapter). */
    private fun focusFirstDetailStepIfAny(): Boolean {
        if (stepAdapter.itemCount == 0) return false
        val firstItem = detailsRecycler.findViewHolderForAdapterPosition(0)?.itemView
            ?: detailsRecycler.layoutManager?.findViewByPosition(0)
        firstItem?.requestFocus() ?: return false
        return true
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
        stationMarkersController.destroy()
        clearPolyline()
        mapView.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MIN_ZOOM = 2
        private const val MAX_ZOOM = 20
        private const val FULL_SCHEDULE_WINDOW_SECONDS = 24 * 60 * 60
        private const val FULL_SCHEDULE_COUNT = 200
        private const val TICK_INTERVAL_MILLIS = 30_000L
    }
}
