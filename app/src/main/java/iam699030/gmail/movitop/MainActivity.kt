package iam699030.gmail.movitop

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
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
import com.google.android.material.chip.Chip
import iam699030.gmail.movitop.chat.ChatAdapter
import iam699030.gmail.movitop.chat.ChatViewModel
import iam699030.gmail.movitop.chat.ChatViewModel.ConversationState
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.RouteAdapter
import iam699030.gmail.movitop.data.RouteStepAdapter
import kotlinx.coroutines.launch
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File

class MainActivity : AppCompatActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private lateinit var mapView: MapView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var detailsRecycler: RecyclerView
    private lateinit var summaryContainer: LinearLayout
    private lateinit var detailsContainer: LinearLayout
    private lateinit var detailsDuration: TextView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<NestedScrollView>

    private val chatAdapter = ChatAdapter()
    private val routeAdapter = RouteAdapter { option -> viewModel.selectRoute(option) }
    private val stepAdapter = RouteStepAdapter()

    private var routeOverlay: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must be ready before the MapView is inflated.
        AndroidGraphicFactory.createInstance(application)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contentRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapView = findViewById(R.id.mapView)
        chatRecycler = findViewById(R.id.chatRecycler)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        resultsRecycler = findViewById(R.id.resultsRecycler)
        detailsRecycler = findViewById(R.id.detailsRecycler)
        summaryContainer = findViewById(R.id.summaryContainer)
        detailsContainer = findViewById(R.id.detailsContainer)
        detailsDuration = findViewById(R.id.detailsDuration)

        setupMap()
        setupChatList()
        setupResultsSheet()
        setupInput()
        setupQuickChips()
        observeViewModel()

        // Boot the on-device MOTIS child process (foreground service).
        MotisForegroundService.start(this)
    }

    // --- Mapsforge offline map ------------------------------------------------

    private fun setupMap() {
        mapView.mapScaleBar.isVisible = false
        mapView.setBuiltInZoomControls(false)
        mapView.isClickable = true

        val tileCache = AndroidUtil.createTileCache(
            this,
            "movitop_tiles",
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
            rendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT)
            mapView.layerManager.layers.add(rendererLayer)
        } else {
            // Graceful fallback: no crash, just an empty map until the .map is pushed.
            Log.w(TAG, getString(R.string.map_missing_log, mapFile.absolutePath))
        }

        // Center on Israel regardless, so the empty/loaded map has a valid position.
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

        // Frame the route start.
        mapView.model.mapViewPosition.center = LatLong(points.first().lat, points.first().lon)
        mapView.model.mapViewPosition.zoomLevel = 10.toByte()
    }

    private fun clearPolyline() {
        routeOverlay?.let { mapView.layerManager.layers.remove(it) }
        routeOverlay = null
    }

    // --- Lists + bottom sheet -------------------------------------------------

    private fun setupChatList() {
        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatRecycler.adapter = chatAdapter
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
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    val target = if (detailsContainer.visibility == View.VISIBLE) {
                        detailsRecycler
                    } else {
                        resultsRecycler
                    }
                    focusFirstChild(target)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Details -> back to the 5-option summary (and clear the polyline).
                    viewModel.state.value is ConversationState.ViewingRouteDetails ->
                        viewModel.backToSummary()
                    // Summary visible -> collapse the sheet to reveal the full map.
                    bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN ->
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    // Nothing open -> default behavior (leave the screen).
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun setupInput() {
        sendButton.setOnClickListener { sendCurrentInput() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }
        messageInput.requestFocus()
    }

    private fun setupQuickChips() {
        val chipIds = listOf(R.id.chipHome, R.id.chipWork, R.id.chipKollel, R.id.chipFavorites)
        for (id in chipIds) {
            findViewById<Chip>(id).setOnClickListener { view ->
                viewModel.onUserInput((view as Chip).text.toString())
            }
        }
    }

    // --- State observation ----------------------------------------------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        chatAdapter.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                chatRecycler.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }
                launch {
                    viewModel.routes.collect { routes ->
                        routeAdapter.submitList(routes.orEmpty())
                    }
                }
                launch {
                    viewModel.routeDetail.collect { detail ->
                        stepAdapter.submitList(detail?.steps.orEmpty()) {
                            if (detail != null && detailsContainer.visibility == View.VISIBLE) {
                                focusFirstChild(detailsRecycler)
                            }
                        }
                        if (detail != null) drawPolyline(detail.polyline) else clearPolyline()
                    }
                }
                launch {
                    viewModel.state.collect { state -> renderState(state) }
                }
            }
        }
    }

    private fun renderState(state: ConversationState) {
        when (state) {
            ConversationState.ResultsReady -> {
                showSummaryPane()
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                focusFirstChild(resultsRecycler)
            }
            is ConversationState.ViewingRouteDetails -> {
                showDetailsPane()
                detailsDuration.text = formatDuration(state.selectedRoute.durationMinutes)
                // Polyline + step focus are handled when routeDetail loads (async).
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            else -> {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
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

    private fun sendCurrentInput() {
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        messageInput.text?.clear()
        viewModel.onUserInput(text)
    }

    /** D-Pad: move focus to the first row of the given list once it is shown. */
    private fun focusFirstChild(recycler: RecyclerView) {
        recycler.post {
            val firstItem = recycler.findViewHolderForAdapterPosition(0)?.itemView
                ?: recycler.layoutManager?.findViewByPosition(0)
            firstItem?.requestFocus()
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

    // --- Lifecycle ------------------------------------------------------------

    override fun onDestroy() {
        clearPolyline()
        mapView.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
