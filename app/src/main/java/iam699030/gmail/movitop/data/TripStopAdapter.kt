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

/**
 * One trip's full stop-by-stop list — see [TripStop] and LineTimesScreen.TripDetail.
 * A continuous rail runs through every dot (railTop/railBottom in
 * item_trip_stop.xml); the first and last stop hide the segment pointing
 * outside the trip so the line starts and ends exactly at the terminus, and
 * get a larger dot + bold name to read as the trip's endpoints.
 */
class TripStopAdapter : ListAdapter<TripStop, TripStopAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val railTop: View = view.findViewById(R.id.tripStopRailTop)
        val dot: View = view.findViewById(R.id.tripStopDot)
        val railBottom: View = view.findViewById(R.id.tripStopRailBottom)
        val name: TextView = view.findViewById(R.id.tripStopName)
        val arrival: TextView = view.findViewById(R.id.tripStopArrival)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip_stop, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.name.text = item.stopName
        holder.arrival.text = GtfsTime.format(item.arrivalTime)

        val isFirst = position == 0
        val isLast = position == itemCount - 1
        holder.railTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
        holder.railBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

        val dotSize = if (isFirst || isLast) TERMINUS_DOT_DP else REGULAR_DOT_DP
        val density = holder.itemView.resources.displayMetrics.density
        holder.dot.layoutParams = holder.dot.layoutParams.apply {
            width = (dotSize * density).toInt()
            height = (dotSize * density).toInt()
        }
        holder.dot.requestLayout()

        holder.name.typeface = if (isFirst || isLast) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    companion object {
        private const val REGULAR_DOT_DP = 10
        private const val TERMINUS_DOT_DP = 14

        private val DIFF = object : DiffUtil.ItemCallback<TripStop>() {
            override fun areItemsTheSame(old: TripStop, new: TripStop) =
                old.stopName == new.stopName && old.arrivalTime == new.arrivalTime
            override fun areContentsTheSame(old: TripStop, new: TripStop) = old == new
        }
    }
}
