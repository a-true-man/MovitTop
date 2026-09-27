package iam699030.gmail.movitop.nearby

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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
import com.google.android.material.button.MaterialButton
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.NearbyDeparture
import iam699030.gmail.movitop.nav.LocationTracker
import iam699030.gmail.movitop.search.SearchLocationActivity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Nearby" — upcoming departures around the user's current GPS position, the
 * way Moovit's nearby-stops tab works. Picking one returns its stop as a
 * destination pick to [iam699030.gmail.movitop.MainActivity], reusing
 * [SearchLocationActivity]'s result contract so the caller needs no separate
 * launcher.
 */
class NearbyActivity : AppCompatActivity() {

    private val viewModel: NearbyViewModel by viewModels()
    private val adapter = NearbyDepartureAdapter { departure -> returnDeparture(departure) }

    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var refreshButton: MaterialButton

    private var lastPoint: GeoPoint? = null

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) locateAndLoad() else viewModel.locationUnavailable() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_nearby)

        val toolbar = findViewById<MaterialToolbar>(R.id.nearbyToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nearbyRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        progress = findViewById(R.id.nearbyProgress)
        emptyText = findViewById(R.id.nearbyEmptyText)
        recycler = findViewById(R.id.nearbyRecycler)
        refreshButton = findViewById(R.id.nearbyRefreshButton)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        refreshButton.setOnClickListener { onCurrentLocationRequested() }

        observeViewModel()
        onCurrentLocationRequested()
    }

    private fun onCurrentLocationRequested() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            locateAndLoad()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun locateAndLoad() {
        lifecycleScope.launch {
            val point = withTimeoutOrNull(10_000L) {
                LocationTracker(this@NearbyActivity).updates().firstOrNull()
            }
            if (point == null) {
                viewModel.locationUnavailable()
                return@launch
            }
            lastPoint = point
            viewModel.load(point)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is NearbyViewModel.LoadState.Locating -> {
                            progress.visibility = View.VISIBLE
                            emptyText.visibility = View.GONE
                            recycler.visibility = View.GONE
                        }
                        is NearbyViewModel.LoadState.LocationUnavailable -> {
                            progress.visibility = View.GONE
                            recycler.visibility = View.GONE
                            emptyText.visibility = View.VISIBLE
                            emptyText.text = getString(R.string.map_location_unavailable)
                        }
                        is NearbyViewModel.LoadState.Loaded -> {
                            progress.visibility = View.GONE
                            adapter.submitList(state.departures)
                            if (state.departures.isEmpty()) {
                                recycler.visibility = View.GONE
                                emptyText.visibility = View.VISIBLE
                                emptyText.text = getString(R.string.nearby_empty)
                            } else {
                                recycler.visibility = View.VISIBLE
                                emptyText.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    private fun returnDeparture(departure: NearbyDeparture) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(SearchLocationActivity.EXTRA_PLACE_NAME, departure.stopName)
                putExtra(SearchLocationActivity.EXTRA_PLACE_LAT, departure.stopPoint.lat)
                putExtra(SearchLocationActivity.EXTRA_PLACE_LON, departure.stopPoint.lon)
            }
        )
        finish()
    }
}
