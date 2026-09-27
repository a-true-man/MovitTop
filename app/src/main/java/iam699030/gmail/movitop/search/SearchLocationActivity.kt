package iam699030.gmail.movitop.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.SimpleTextWatcher
import iam699030.gmail.movitop.data.GeocodePlace
import kotlinx.coroutines.launch

class SearchLocationActivity : AppCompatActivity() {

    private val viewModel: SearchLocationViewModel by viewModels()

    private lateinit var searchInput: EditText
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var searchProgress: ProgressBar
    private lateinit var emptyState: TextView

    private val resultAdapter = GeocodeResultAdapter { place -> returnPlace(place) }

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
                launch {
                    viewModel.results.collect { results ->
                        resultAdapter.submitList(results) {
                            if (results.isNotEmpty() && searchInput.hasFocus()) {
                                searchInput.nextFocusDownId = R.id.resultsRecycler
                            }
                        }
                        emptyState.visibility =
                            if (results.isEmpty() && searchInput.text.length >= 2) View.VISIBLE
                            else View.GONE
                    }
                }
                launch {
                    viewModel.isSearching.collect { searching ->
                        searchProgress.visibility = if (searching) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun returnPlace(place: GeocodePlace) {
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
