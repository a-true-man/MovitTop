package iam699030.gmail.movitop.nearby

import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.NearbyDeparture
import iam699030.gmail.movitop.data.RelativeTime

/** Renders each upcoming nearby departure; tapping one picks its stop as the destination. */
class NearbyDepartureAdapter(
    private val onSelected: (NearbyDeparture) -> Unit
) : ListAdapter<NearbyDeparture, NearbyDepartureAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.nearbyColorDot)
        val line: TextView = view.findViewById(R.id.nearbyLine)
        val stopInfo: TextView = view.findViewById(R.id.nearbyStopInfo)
        val time: TextView = view.findViewById(R.id.nearbyTime)
        val minutes: TextView = view.findViewById(R.id.nearbyMinutes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_departure, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        (holder.dot.background.mutate() as? GradientDrawable)?.setColor(item.colorArgb)
        holder.line.text = if (item.headsign.isNotBlank()) {
            "${item.routeShortName} · ${item.headsign}"
        } else {
            item.routeShortName
        }
        holder.stopInfo.text = context.getString(
            R.string.nearby_distance_meters, item.distanceMeters.toInt()
        ).let { distanceText -> "${item.stopName} · $distanceText" }
        holder.time.text = item.departTimeText
        holder.minutes.text = if (RelativeTime.isStale(item.departEpochMillis)) {
            ""
        } else {
            RelativeTime.describe(context, item.departEpochMillis)
        }

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
        private val DIFF = object : DiffUtil.ItemCallback<NearbyDeparture>() {
            override fun areItemsTheSame(old: NearbyDeparture, new: NearbyDeparture) =
                old.stopName == new.stopName && old.departTimeText == new.departTimeText &&
                    old.routeShortName == new.routeShortName
            override fun areContentsTheSame(old: NearbyDeparture, new: NearbyDeparture) = old == new
        }
    }
}
