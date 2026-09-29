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
 * One line's timetable for a chosen day (see LineTimesScreen.LineDetail) —
 * just time + headsign, since the screen's own header already names the line
 * and its direction (no per-row "from" stop or favorite star — favoriting
 * lives on the line-summary card one level up, see LineSummaryAdapter).
 * Tapping a row drills into that trip's full stop list.
 */
class LineDepartureAdapter(
    private val onSelected: (LineDeparture) -> Unit
) : ListAdapter<LineDeparture, LineDepartureAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.departureTime)
        val headsign: TextView = view.findViewById(R.id.departureHeadsign)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line_departure, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.time.text = GtfsTime.format(item.departureTime)
        holder.headsign.text = item.headsign.ifBlank { item.routeLongName }

        holder.itemView.setOnClickListener { onSelected(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onSelected(item)
                true
            } else {
                false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LineDeparture>() {
            override fun areItemsTheSame(old: LineDeparture, new: LineDeparture) = old.tripId == new.tripId
            override fun areContentsTheSame(old: LineDeparture, new: LineDeparture) = old == new
        }
    }
}
