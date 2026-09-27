package iam699030.gmail.movitop

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import iam699030.gmail.movitop.data.LineDepartureAdapter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Search a line, see its scheduled departures for a chosen day. Pure local
 * lookup against the SQLite DB built by build_line_schedules.py — no MOTIS
 * query, no network, works even if the routing engine isn't running.
 */
class LineTimesActivity : AppCompatActivity() {

    private val viewModel: LineTimesViewModel by viewModels()
    private val adapter = LineDepartureAdapter()

    private lateinit var queryInput: EditText
    private lateinit var dateButton: MaterialButton
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_times)

        queryInput = findViewById(R.id.lineQueryInput)
        dateButton = findViewById(R.id.lineDateButton)
        recycler = findViewById(R.id.departuresRecycler)
        emptyText = findViewById(R.id.lineTimesEmptyText)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.search(s?.toString().orEmpty())
            }
        })

        dateButton.setOnClickListener { pickDate() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    dateButton.text = SimpleDateFormat("dd/MM", Locale.getDefault())
                        .format(state.dateEpochMillis)
                    adapter.submitList(state.results)

                    val showEmpty = when {
                        !state.isAvailable -> true
                        state.searched && state.results.isEmpty() -> true
                        else -> false
                    }
                    emptyText.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    emptyText.text = if (!state.isAvailable) {
                        getString(R.string.line_times_unavailable)
                    } else {
                        getString(R.string.line_times_no_results)
                    }
                    recycler.visibility = if (state.results.isEmpty()) View.GONE else View.VISIBLE
                }
            }
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
                viewModel.setDate(calendar.timeInMillis)
                viewModel.search(queryInput.text.toString())
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
