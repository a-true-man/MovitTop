package iam699030.gmail.movitop

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
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
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import iam699030.gmail.movitop.nav.LocationTracker
import iam699030.gmail.movitop.nav.NavigationEngine
import iam699030.gmail.movitop.nav.NavigationStep
import iam699030.gmail.movitop.nav.PendingNavigation
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen live navigation: shows one [NavigationStep] at a time and
 * advances automatically (GPS proximity when available, schedule countdown
 * otherwise) or on manual next/back — see [NavigationEngine] for why it's
 * hybrid. Text + vibration only for now; TTS is a planned follow-up behind
 * its own mute-able toggle, not wired in yet.
 */
class LiveNavigationActivity : AppCompatActivity() {

    private lateinit var modeText: TextView
    private lateinit var instructionText: TextView
    private lateinit var distanceText: TextView
    private lateinit var upcomingText: TextView
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private lateinit var engine: NavigationEngine
    private var steps: List<NavigationStep> = emptyList()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> startEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = PendingNavigation.steps
        if (pending.isNullOrEmpty()) {
            finish()
            return
        }
        steps = pending
        PendingNavigation.steps = null

        enableEdgeToEdge()
        setContentView(R.layout.activity_live_navigation)

        val root = findViewById<View>(R.id.navRoot)
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

        modeText = findViewById(R.id.navModeText)
        instructionText = findViewById(R.id.navInstructionText)
        distanceText = findViewById(R.id.navDistanceText)
        upcomingText = findViewById(R.id.navUpcomingText)
        backButton = findViewById(R.id.navBackButton)
        nextButton = findViewById(R.id.navNextButton)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startEngine()
        } else {
            requestLocationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun startEngine() {
        val hasGps = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val tracker = if (hasGps) LocationTracker(this) else null
        engine = NavigationEngine(steps, tracker)

        backButton.setOnClickListener { engine.manualBack() }
        nextButton.setOnClickListener { engine.manualAdvance() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    engine.state.collect { state ->
                        if (state.finished) {
                            instructionText.text = getString(R.string.nav_finished)
                            distanceText.text = ""
                            upcomingText.text = ""
                            nextButton.isEnabled = false
                            return@collect
                        }
                        val step = state.step ?: return@collect
                        instructionText.text = instructionTextFor(step)
                        nextButton.text = if (step is NavigationStep.Board) {
                            getString(R.string.nav_confirm_board)
                        } else {
                            getString(R.string.nav_next_button)
                        }
                        backButton.isEnabled = state.stepIndex > 0
                        distanceText.text = state.distanceToTargetMeters?.let {
                            getString(R.string.nav_distance_meters, it.roundToInt())
                        } ?: ""
                        modeText.text = if (state.usingGps) {
                            getString(R.string.nav_mode_gps)
                        } else {
                            getString(R.string.nav_mode_schedule)
                        }
                        val next = steps.getOrNull(state.stepIndex + 1)
                        upcomingText.text = next?.let {
                            getString(R.string.nav_upcoming, instructionTextFor(it))
                        } ?: ""
                    }
                }
                launch {
                    engine.stepChanged.collect { vibrate() }
                }
                launch {
                    engine.trackGps()
                }
            }
        }
        engine.start(lifecycleScope)
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
        if (::engine.isInitialized) engine.stop()
        super.onDestroy()
    }
}
