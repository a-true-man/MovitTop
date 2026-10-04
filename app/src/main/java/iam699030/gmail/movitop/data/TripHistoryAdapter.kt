package iam699030.gmail.movitop.data

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R

/**
 * Horizontal row of past/pinned trips in the Home sheet (see
 * [TripHistoryRepository]) — same horizontal-carousel D-Pad handling as
 * [DirectModeAdapter] (no natural "leave the row" edge for DPAD_DOWN without it).
 */
class TripHistoryAdapter(
    private val onTripSelected: (TripHistoryEntry) -> Unit,
    private val onPinToggled: (TripHistoryEntry) -> Unit,
    private val onDownPressed: () -> Boolean = { false }
) : ListAdapter<TripHistoryEntry, TripHistoryAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val origin: TextView = view.findViewById(R.id.tripHistoryOrigin)
        val destination: TextView = view.findViewById(R.id.tripHistoryDestination)
        val pinButton: View = view.findViewById(R.id.tripHistoryPinButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trip_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.origin.text = entry.originName
        holder.destination.text = entry.destinationName
        holder.pinButton.alpha = if (entry.pinned) 1f else 0.3f

        holder.itemView.setOnClickListener { onTripSelected(entry) }
        holder.pinButton.setOnClickListener { onPinToggled(entry) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    onTripSelected(entry)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> onDownPressed()
                else -> false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TripHistoryEntry>() {
            override fun areItemsTheSame(old: TripHistoryEntry, new: TripHistoryEntry) = old.key == new.key
            override fun areContentsTheSame(old: TripHistoryEntry, new: TripHistoryEntry) = old == new
        }
    }
}
