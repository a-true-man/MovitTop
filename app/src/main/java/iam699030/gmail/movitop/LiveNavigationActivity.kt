package iam699030.gmail.movitop

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import iam699030.gmail.movitop.data.AlertMode
import iam699030.gmail.movitop.data.ReminderSettingsRepository
import iam699030.gmail.movitop.data.RemainingStopsAdapter
import iam699030.gmail.movitop.map.LiveNavMapController
import iam699030.gmail.movitop.map.MapThemeHelper
import iam699030.gmail.movitop.nav.LocationTracker
import iam699030.gmail.movitop.nav.NavState
import iam699030.gmail.movitop.nav.NavigationEngine
import iam699030.gmail.movitop.nav.NavigationStep
import iam699030.gmail.movitop.nav.PendingNavigation
import iam699030.gmail.movitop.nav.ReminderEvent
import kotlinx.coroutines.launch
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Map-centric live navigation: a [MapView] showing only the CURRENT step's
 * route (see [LiveNavMapController]), with a green header, a black
 * instruction bar, a get-off reminder toggle, a bottom card that swaps
 * between a single instruction summary and the remaining-stops list (Ride
 * steps), and a prev/next pager with dots — no swipe, explicit buttons only,
 * so the screen stays fully D-Pad navigable.
 *
 * Exiting is three distinct actions: the X button ends the trip for good
 * (confirm dialog); the chevron opens a full step overview to jump around;
 * the system Back button only minimizes — see [PendingNavigation] for how
 * the session survives that and gets resumed from MainActivity.
 */
class LiveNavigationActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var navMapController: LiveNavMapController

    private lateinit var etaText: TextView
    private lateinit var closeButton: MaterialButton
    private lateinit var chevronButton: MaterialButton

    private lateinit var instructionBar: View
    private lateinit var instructionText: TextView
    private lateinit var distanceText: TextView

    private lateinit var reminderToggle: MaterialSwitch
    private lateinit var reminderSettingsButton: MaterialButton

    private lateinit var instructionCardContent: View
    private lateinit var cardLabel: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardTime: TextView
    private lateinit var stopsRemainingHeader: View
    private lateinit var stopsRecycler: RecyclerView

    private lateinit var prevButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var dotsContainer: ViewGroup

    private lateinit var engine: NavigationEngine
    private var steps: List<NavigationStep> = emptyList()
    private val reminderSettingsRepository: ReminderSettingsRepository by lazy { ReminderSettingsRepository(this) }
    private val stopsAdapter = RemainingStopsAdapter()
    private var toneGenerator: ToneGenerator? = null

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> startEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)

        val freshSteps = PendingNavigation.steps
        val existingEngine = PendingNavigation.activeEngine
        if (freshSteps.isNullOrEmpty() && existingEngine == null) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_live_navigation)
        bindViews()
        applyInsets()
        setupMap()

        stopsRecycler.layoutManager = LinearLayoutManager(this)
        stopsRecycler.adapter = stopsAdapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Soft-exit: leaves PendingNavigation.activeEngine alone, the session keeps running.
                finish()
            }
        })

        closeButton.setOnClickListener { confirmEndNavigation() }
        chevronButton.setOnClickListener { showStepsOverview() }
        prevButton.setOnClickListener { if (::engine.isInitialized) engine.manualBack() }
        nextButton.setOnClickListener { if (::engine.isInitialized) engine.manualAdvance() }
        reminderSettingsButton.setOnClickListener { showReminderSettingsDialog() }

        val initialSettings = reminderSettingsRepository.get()
        reminderToggle.isChecked = initialSettings.enabled
        reminderToggle.setOnCheckedChangeListener { _, checked ->
            val updated = reminderSettingsRepository.get().copy(enabled = checked)
            reminderSettingsRepository.save(updated)
            if (::engine.isInitialized) engine.updateReminderSettings(updated)
        }

        if (freshSteps != null) {
            PendingNavigation.steps = null
            steps = freshSteps
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startEngine()
            } else {
                requestLocationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        } else {
            engine = existingEngine!!
            steps = engine.steps
            observeEngine()
        }

        closeButton.post { closeButton.requestFocus() }
    }

    private fun bindViews() {
        mapView = findViewById(R.id.navMapView)
        etaText = findViewById(R.id.navEtaText)
        closeButton = findViewById(R.id.navCloseButton)
        chevronButton = findViewById(R.id.navChevronButton)
        instructionBar = findViewById(R.id.navInstructionBar)
        instructionText = findViewById(R.id.navInstructionText)
        distanceText = findViewById(R.id.navDistanceText)
        reminderToggle = findViewById(R.id.navReminderToggle)
        reminderSettingsButton = findViewById(R.id.navReminderSettingsButton)
        instructionCardContent = findViewById(R.id.navInstructionCardContent)
        cardLabel = findViewById(R.id.navCardLabel)
        cardTitle = findViewById(R.id.navCardTitle)
        cardTime = findViewById(R.id.navCardTime)
        stopsRemainingHeader = findViewById(R.id.navStopsRemainingHeader)
        stopsRecycler = findViewById(R.id.navStopsRecycler)
        prevButton = findViewById(R.id.navPrevButton)
        nextButton = findViewById(R.id.navNextButton)
        dotsContainer = findViewById(R.id.navDotsContainer)
        navMapController = LiveNavMapController(this, mapView)
    }

    private fun applyInsets() {
        val contentRoot = findViewById<View>(R.id.navContentRoot)
        val initialPadding = Rect(
            contentRoot.paddingLeft, contentRoot.paddingTop, contentRoot.paddingRight, contentRoot.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPadding.left + bars.left,
                initialPadding.top + bars.top,
                initialPadding.right + bars.right,
                initialPadding.bottom + bars.bottom
            )
            insets
        }
    }

    /**
     * Same offline tile setup as [MainActivity.setupMap] (tile cache + the
     * downloaded .map file + theme) — this screen has its own [MapView]
     * instance, so it needs its own renderer layer; LiveNavMapController only
     * draws the route polyline/position marker on top of it, not the base
     * map itself. Plus D-Pad panning — no on-screen zoom/location controls
     * on this screen, unlike MainActivity's.
     */
    private fun setupMap() {
        mapView.mapScaleBar.isVisible = false
        mapView.setBuiltInZoomControls(false)
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { prevButton.requestFocus(); true }
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
        val mapFile = File(filesDir, "motis_data/israel.map")
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
    }

    private fun panBy(fractionLon: Double, fractionLat: Double) {
        val position = mapView.model.mapViewPosition
        val tilesAcross = 1 shl position.zoomLevel.toInt()
        val degreesPerTileLon = 360.0 / tilesAcross
        val degreesPerTileLat = 170.0 / tilesAcross
        val center = position.center
        position.center = LatLong(
            (center.latitude + fractionLat * degreesPerTileLat).coerceIn(-85.0, 85.0),
            center.longitude + fractionLon * degreesPerTileLon
        )
    }

    private fun startEngine() {
        val hasGps = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val tracker = if (hasGps) LocationTracker(applicationContext) else null
        engine = NavigationEngine(steps, tracker, reminderSettingsRepository.get())
        PendingNavigation.activeEngine = engine
        observeEngine()
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.state.collect { state -> renderState(state) } }
                launch { engine.stepChanged.collect { vibrate() } }
                launch { engine.reminderFired.collect { fireReminderAlert(it) } }
                launch { engine.trackGps() }
            }
        }
        engine.start(lifecycleScope)
    }

    private fun renderState(state: NavState) {
        if (state.finished) {
            instructionText.text = getString(R.string.nav_finished)
            distanceText.text = ""
            stopsRemainingHeader.visibility = View.GONE
            stopsRecycler.visibility = View.GONE
            instructionCardContent.visibility = View.VISIBLE
            cardLabel.text = ""
            cardTitle.text = getString(R.string.nav_finished)
            cardTime.text = ""
            nextButton.isEnabled = false
            updateDots(state.stepIndex)
            return
        }
        val step = state.step ?: return

        navMapController.showStep(step)
        state.currentPosition?.let { navMapController.updatePosition(it) }

        etaText.text = headerEtaText(state)
        prevButton.isEnabled = state.stepIndex > 0
        nextButton.isEnabled = true

        when (step) {
            is NavigationStep.Walk -> {
                instructionBar.visibility = View.VISIBLE
                instructionText.text = getString(R.string.nav_walk_instruction, step.instruction, step.distanceMeters.roundToInt())
                distanceText.text = state.distanceToTargetMeters?.let {
                    getString(R.string.nav_distance_meters, it.roundToInt())
                } ?: ""
                showInstructionCard(
                    label = getString(R.string.nav_card_label_walk),
                    title = step.instruction,
                    time = getString(R.string.distance_meters, step.distanceMeters.roundToInt())
                )
            }
            is NavigationStep.Board -> {
                instructionBar.visibility = View.VISIBLE
                instructionText.text = instructionTextFor(step)
                distanceText.text = getString(R.string.nav_confirm_board)
                showInstructionCard(
                    label = getString(R.string.nav_card_label_board),
                    title = "${step.routeLabel} · ${step.stopName}",
                    time = step.departTimeText?.let { getString(R.string.nav_depart_at, it) } ?: ""
                )
            }
            is NavigationStep.Ride -> {
                instructionBar.visibility = View.VISIBLE
                instructionText.text = instructionTextFor(step)
                distanceText.text = state.ridingStopsRemaining?.let {
                    getString(R.string.nav_reminder_fired_message, it, step.alightStopName)
                } ?: ""
                showStopsList(step, state.ridingStopsRemaining)
            }
            is NavigationStep.Alight -> {
                instructionBar.visibility = View.VISIBLE
                instructionText.text = instructionTextFor(step)
                distanceText.text = ""
                showInstructionCard(
                    label = getString(R.string.nav_card_label_alight),
                    title = step.stopName,
                    time = ""
                )
            }
            is NavigationStep.Arrive -> {
                instructionBar.visibility = View.VISIBLE
                instructionText.text = instructionTextFor(step)
                distanceText.text = ""
                showInstructionCard(
                    label = getString(R.string.nav_card_label_arrive),
                    title = step.placeName,
                    time = step.arriveTimeText.orEmpty()
                )
            }
        }

        updateDots(state.stepIndex)
        updateFocusChain()
    }

    private fun showInstructionCard(label: String, title: String, time: String) {
        instructionCardContent.visibility = View.VISIBLE
        stopsRemainingHeader.visibility = View.GONE
        stopsRecycler.visibility = View.GONE
        cardLabel.text = label
        cardTitle.text = title
        cardTime.text = time
        cardTime.visibility = if (time.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showStopsList(step: NavigationStep.Ride, stopsRemaining: Int?) {
        instructionCardContent.visibility = View.GONE
        stopsRemainingHeader.visibility = View.VISIBLE
        stopsRecycler.visibility = View.VISIBLE
        val visibleStops = if (stopsRemaining != null) {
            step.stops.takeLast((stopsRemaining + 1).coerceAtMost(step.stops.size))
        } else {
            step.stops
        }
        stopsAdapter.submitList(visibleStops)
    }

    private fun instructionTextFor(step: NavigationStep): String = when (step) {
        is NavigationStep.Walk -> getString(
            R.string.nav_walk_instruction, step.instruction, step.distanceMeters.roundToInt()
        )
        is NavigationStep.Board -> if (step.departTimeText != null) {
            getString(R.string.nav_board_instruction_timed, step.routeLabel, step.stopName, step.departTimeText)
        } else {
            getString(R.string.nav_board_instruction, step.routeLabel, step.stopName)
        }
        is NavigationStep.Ride -> if (step.arriveTimeText != null) {
            getString(R.string.nav_ride_instruction_timed, step.routeLabel, step.alightStopName, step.arriveTimeText)
        } else {
            getString(R.string.nav_ride_instruction, step.routeLabel, step.alightStopName)
        }
        is NavigationStep.Alight -> getString(R.string.nav_arrive_instruction, step.stopName)
        is NavigationStep.Arrive -> getString(R.string.nav_arrive_instruction, step.placeName)
    }

    /** "13:15 · 11 min left" — overall arrival time (last step) + remaining duration from the current step on. */
    private fun headerEtaText(state: NavState): String {
        val arriveTime = (steps.lastOrNull() as? NavigationStep.Arrive)?.arriveTimeText
        val remainingSeconds = steps.drop(state.stepIndex).sumOf { it.estimatedSeconds }
        val remainingText = formatDuration((remainingSeconds / 60).toInt().coerceAtLeast(1))
        return if (arriveTime != null) "$arriveTime · $remainingText" else remainingText
    }

    private fun formatDuration(minutes: Int): String = if (minutes >= 60) {
        val hours = minutes / 60.0
        val hoursText = if (hours % 1.0 == 0.0) hours.toInt().toString() else String.format(Locale.US, "%.1f", hours)
        getString(R.string.duration_hours, hoursText)
    } else {
        getString(R.string.duration_minutes, minutes)
    }

    private fun updateDots(currentIndex: Int) {
        if (dotsContainer.childCount != steps.size) {
            dotsContainer.removeAllViews()
            val dotSize = (8 * resources.displayMetrics.density).roundToInt()
            val margin = (3 * resources.displayMetrics.density).roundToInt()
            repeat(steps.size) {
                val dot = View(this)
                val params = ViewGroup.MarginLayoutParams(dotSize, dotSize).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                dot.layoutParams = params
                dotsContainer.addView(dot)
            }
        }
        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).setBackgroundResource(
                if (i == currentIndex) R.drawable.bg_nav_pager_dot_active else R.drawable.bg_nav_pager_dot_inactive
            )
        }
    }

    /** Routes D-Pad focus past the full-bleed MapView, which would otherwise win Android's default spatial focus search. */
    private fun updateFocusChain() {
        val afterReminderId = if (stopsRecycler.visibility == View.VISIBLE) R.id.navStopsRecycler else R.id.navPrevButton
        reminderSettingsButton.nextFocusDownId = afterReminderId
        reminderToggle.nextFocusDownId = afterReminderId
        prevButton.nextFocusUpId = if (stopsRecycler.visibility == View.VISIBLE) R.id.navStopsRecycler else R.id.navReminderSettingsButton
        nextButton.nextFocusUpId = prevButton.nextFocusUpId
    }

    private fun confirmEndNavigation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nav_close_confirm_title)
            .setMessage(R.string.nav_close_confirm_message)
            .setNegativeButton(R.string.nav_close_confirm_cancel, null)
            .setPositiveButton(R.string.nav_close_confirm_end) { _, _ ->
                if (::engine.isInitialized) engine.stop()
                PendingNavigation.activeEngine = null
                PendingNavigation.activeDestinationLabel = null
                finish()
            }
            .show()
    }

    private fun showStepsOverview() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_nav_steps_overview, null)
        val recycler = view.findViewById<RecyclerView>(R.id.navStepsOverviewRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        recycler.adapter = NavStepsOverviewAdapter(steps, this::instructionTextFor) { index ->
            if (::engine.isInitialized) engine.jumpTo(index)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showReminderSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_reminder_settings, null)
        val stopsLabel = view.findViewById<TextView>(R.id.reminderStopsBeforeLabel)
        val minusButton = view.findViewById<MaterialButton>(R.id.reminderStopsMinusButton)
        val plusButton = view.findViewById<MaterialButton>(R.id.reminderStopsPlusButton)
        val soundButton = view.findViewById<MaterialButton>(R.id.reminderModeSoundButton)
        val vibrateButton = view.findViewById<MaterialButton>(R.id.reminderModeVibrateButton)
        val bothButton = view.findViewById<MaterialButton>(R.id.reminderModeBothButton)
        val volumeSeekBar = view.findViewById<SeekBar>(R.id.reminderVolumeSeekBar)
        val saveButton = view.findViewById<MaterialButton>(R.id.reminderSaveButton)

        var current = reminderSettingsRepository.get()

        fun refreshStopsLabel() {
            stopsLabel.text = getString(R.string.nav_reminder_stops_before, current.stopsBefore)
        }
        fun refreshModeButtons() {
            val selectedBg = ContextCompat.getColor(this, R.color.movitop_primary)
            val unselectedBg = ContextCompat.getColor(this, R.color.movitop_chip_bg)
            val selectedText = ContextCompat.getColor(this, R.color.movitop_on_primary)
            val unselectedText = ContextCompat.getColor(this, R.color.movitop_chip_text)
            val buttons = listOf(soundButton to AlertMode.SOUND, vibrateButton to AlertMode.VIBRATE, bothButton to AlertMode.BOTH)
            buttons.forEach { (button, mode) ->
                val selected = current.alertMode == mode
                button.setBackgroundColor(if (selected) selectedBg else unselectedBg)
                button.setTextColor(if (selected) selectedText else unselectedText)
            }
        }

        refreshStopsLabel()
        refreshModeButtons()
        volumeSeekBar.progress = current.volume

        minusButton.setOnClickListener {
            current = current.copy(stopsBefore = (current.stopsBefore - 1).coerceAtLeast(1))
            refreshStopsLabel()
        }
        plusButton.setOnClickListener {
            current = current.copy(stopsBefore = (current.stopsBefore + 1).coerceAtMost(20))
            refreshStopsLabel()
        }
        soundButton.setOnClickListener { current = current.copy(alertMode = AlertMode.SOUND); refreshModeButtons() }
        vibrateButton.setOnClickListener { current = current.copy(alertMode = AlertMode.VIBRATE); refreshModeButtons() }
        bothButton.setOnClickListener { current = current.copy(alertMode = AlertMode.BOTH); refreshModeButtons() }

        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        saveButton.setOnClickListener {
            current = current.copy(volume = volumeSeekBar.progress)
            reminderSettingsRepository.save(current)
            if (::engine.isInitialized) engine.updateReminderSettings(current)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun fireReminderAlert(event: ReminderEvent) {
        val settings = reminderSettingsRepository.get()
        if (settings.alertMode == AlertMode.SOUND || settings.alertMode == AlertMode.BOTH) {
            playTone(settings.volume)
        }
        if (settings.alertMode == AlertMode.VIBRATE || settings.alertMode == AlertMode.BOTH) {
            vibrate()
        }
        Snackbar.make(
            findViewById(R.id.navRoot),
            getString(R.string.nav_reminder_fired_message, event.stopsRemaining, event.alightStopName),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun playTone(volumePercent: Int) {
        // ToneGenerator's volume is fixed at construction, and the user can change it in
        // settings between reminders — rebuild fresh each time rather than caching a stale volume.
        toneGenerator?.release()
        val generator = ToneGenerator(
            AudioManager.STREAM_NOTIFICATION,
            volumePercent.coerceIn(ToneGenerator.MIN_VOLUME, ToneGenerator.MAX_VOLUME)
        )
        toneGenerator = generator
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    override fun onDestroy() {
        // No unconditional engine.stop() here — a soft-exit (Back) or a plain
        // teardown from FLAG_ACTIVITY_REORDER_TO_FRONT must leave the session
        // running; only confirmEndNavigation() stops it.
        navMapController.destroy()
        toneGenerator?.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LiveNavigationActivity"
    }
}

/** One row per [NavigationStep] in the chevron's full-trip overview — reuses item_route_step.xml for visual consistency. */
private class NavStepsOverviewAdapter(
    private val steps: List<NavigationStep>,
    private val instructionTextFor: (NavigationStep) -> String,
    private val onStepClicked: (Int) -> Unit
) : RecyclerView.Adapter<NavStepsOverviewAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.stepDot)
        val title: TextView = view.findViewById(R.id.stepTitle)
        val subtitle: TextView = view.findViewById(R.id.stepSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_route_step, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val step = steps[position]
        holder.title.text = instructionTextFor(step)
        holder.subtitle.visibility = View.GONE
        val dotColor = when (step) {
            is NavigationStep.Board -> step.colorArgb
            is NavigationStep.Ride -> step.colorArgb
            else -> 0
        }
        val color = if (dotColor != 0) dotColor else ContextCompat.getColor(holder.itemView.context, R.color.movitop_primary)
        (holder.dot.background.mutate() as? GradientDrawable)?.setColor(color)
        holder.itemView.setOnClickListener { onStepClicked(position) }
    }

    override fun getItemCount() = steps.size
}
