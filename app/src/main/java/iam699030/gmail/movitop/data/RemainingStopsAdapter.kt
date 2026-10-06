package iam699030.gmail.movitop.data

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.nav.RideStop

/** Renders the "remaining stops" list shown on the live-navigation screen during a Ride step. */
class RemainingStopsAdapter : ListAdapter<RideStop, RemainingStopsAdapter.StopViewHolder>(DIFF) {

    class StopViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.remainingStopName)
        val time: TextView = view.findViewById(R.id.remainingStopTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_remaining_stop, parent, false)
        return StopViewHolder(view)
    }

    override fun onBindViewHolder(holder: StopViewHolder, position: Int) {
        val stop = getItem(position)
        holder.name.text = stop.name
        holder.time.text = stop.arriveTimeText.orEmpty()
        // The last row is the alight stop itself — bold it so it stands out from the ones passed through.
        val isAlightStop = position == itemCount - 1
        holder.name.setTypeface(null, if (isAlightStop) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RideStop>() {
            override fun areItemsTheSame(oldItem: RideStop, newItem: RideStop) =
                oldItem.name == newItem.name && oldItem.point == newItem.point

            override fun areContentsTheSame(oldItem: RideStop, newItem: RideStop) = oldItem == newItem
        }
    }
}
