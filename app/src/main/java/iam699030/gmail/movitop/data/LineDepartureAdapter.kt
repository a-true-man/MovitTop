package iam699030.gmail.movitop.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R

class LineDepartureAdapter : ListAdapter<LineDeparture, LineDepartureAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.departureTime)
        val headsign: TextView = view.findViewById(R.id.departureHeadsign)
        val fromStop: TextView = view.findViewById(R.id.departureFromStop)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line_departure, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.time.text = formatTime(item.departureTime)
        holder.headsign.text = item.headsign.ifBlank { item.routeLongName }
        holder.fromStop.text = holder.itemView.context.getString(
            R.string.line_times_from_stop, item.firstStopName
        )
    }

    /** GTFS allows "25:10:00" for past-midnight trips — normalize to "01:10". */
    private fun formatTime(gtfsTime: String): String {
        val parts = gtfsTime.split(":")
        if (parts.size < 2) return gtfsTime
        val hour = parts[0].toIntOrNull() ?: return gtfsTime
        return "%02d:%s".format(hour % 24, parts[1])
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LineDeparture>() {
            override fun areItemsTheSame(old: LineDeparture, new: LineDeparture) =
                old.departureTime == new.departureTime && old.headsign == new.headsign
            override fun areContentsTheSame(old: LineDeparture, new: LineDeparture) = old == new
        }
    }
}
