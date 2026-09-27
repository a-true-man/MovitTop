package iam699030.gmail.movitop.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import iam699030.gmail.movitop.R

/** @param onToggleFavorite called with a departure's [LineDeparture.routeShortName] when its star is tapped. */
class LineDepartureAdapter(
    private val onToggleFavorite: (String) -> Unit
) : ListAdapter<LineDeparture, LineDepartureAdapter.ViewHolder>(DIFF) {

    private var favoriteLines: Set<String> = emptySet()

    /** Refreshes which rows show as starred without touching the departure list itself. */
    fun setFavorites(lines: Collection<String>) {
        favoriteLines = lines.toSet()
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.departureTime)
        val headsign: TextView = view.findViewById(R.id.departureHeadsign)
        val fromStop: TextView = view.findViewById(R.id.departureFromStop)
        val starButton: MaterialButton = view.findViewById(R.id.favoriteStarButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line_departure, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        holder.time.text = formatTime(item.departureTime)
        holder.headsign.text = item.headsign.ifBlank { item.routeLongName }
        holder.fromStop.text = context.getString(
            R.string.line_times_from_stop, item.firstStopName
        )

        val isFavorite = item.routeShortName in favoriteLines
        holder.starButton.iconTint = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (isFavorite) R.color.movitop_star_active else R.color.movitop_text_secondary
            )
        )
        holder.starButton.contentDescription = context.getString(
            if (isFavorite) R.string.favorite_star_remove_description else R.string.favorite_star_add_description
        )
        holder.starButton.setOnClickListener { onToggleFavorite(item.routeShortName) }
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
