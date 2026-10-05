package iam699030.gmail.movitop.data

import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R
import java.util.Locale

/**
 * Renders TRANSIT [RouteOption] summaries — duration, the chain of lines
 * ridden (colored like their map legs, see [LegColors]), and a live "leaves
 * in X min" countdown. Direct modes (walk/bike/driver/taxi) render in the
 * separate horizontal [DirectModeAdapter] row instead.
 */
class RouteAdapter(
    private val onRouteSelected: (RouteOption) -> Unit
) : ListAdapter<RouteOption, RouteAdapter.RouteViewHolder>(DIFF) {

    class RouteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val duration: TextView = view.findViewById(R.id.modeDuration)
        val timeRange: TextView = view.findViewById(R.id.modeTimeRange)
        val badgeRow: ViewGroup = view.findViewById(R.id.lineBadgeRow)
        val departsIn: TextView = view.findViewById(R.id.departsIn)
        val viaStop: TextView = view.findViewById(R.id.viaStop)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_option, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val option = getItem(position)
        val context = holder.itemView.context

        holder.duration.text = formatDuration(context, option.durationMinutes)
        holder.itemView.setOnClickListener { onRouteSelected(option) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onRouteSelected(option)
                true
            } else {
                false
            }
        }

        val depart = option.departTimeText
        val arrive = option.arriveTimeText
        if (depart != null && arrive != null) {
            holder.timeRange.visibility = View.VISIBLE
            holder.timeRange.text = context.getString(R.string.route_option_time_range, depart, arrive)
        } else {
            holder.timeRange.visibility = View.GONE
        }

        bindBadges(holder.badgeRow, option.transitBadges)

        val departEpoch = option.departEpochMillis
        if (departEpoch != null && !RelativeTime.isStale(departEpoch)) {
            holder.departsIn.visibility = View.VISIBLE
            holder.departsIn.text = RelativeTime.describe(context, departEpoch)
        } else {
            holder.departsIn.visibility = View.GONE
        }

        if (option.viaStopText.isNullOrEmpty()) {
            holder.viaStop.visibility = View.GONE
        } else {
            holder.viaStop.visibility = View.VISIBLE
            holder.viaStop.text = if (departEpoch != null) "· ${option.viaStopText}" else option.viaStopText
        }
    }

    /**
     * Refreshes just the "leaves in X" / via-stop text on already-bound rows
     * directly on their views, without going through [onBindViewHolder] —
     * that path rebuilds the whole line-badge chain (see [bindBadges], which
     * removes and re-inflates a view per badge), which is unnecessary work
     * to repeat on every relative-time tick when the badges themselves never
     * change between ticks. See MainActivity's `tickEvery(...)` caller.
     */
    fun refreshRelativeTimes(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? RouteViewHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) continue
            val option = getItem(position)
            val context = holder.itemView.context

            val departEpoch = option.departEpochMillis
            if (departEpoch != null && !RelativeTime.isStale(departEpoch)) {
                holder.departsIn.visibility = View.VISIBLE
                holder.departsIn.text = RelativeTime.describe(context, departEpoch)
            } else {
                holder.departsIn.visibility = View.GONE
            }

            if (!option.viaStopText.isNullOrEmpty()) {
                holder.viaStop.text = if (departEpoch != null) "· ${option.viaStopText}" else option.viaStopText
            }
        }
    }

    /** Rebuilds the badge chain — a variable-length chip-per-line list, so it's built, not bound. */
    private fun bindBadges(row: ViewGroup, badges: List<TransitLineBadge>) {
        row.removeAllViews()
        val inflater = LayoutInflater.from(row.context)
        badges.forEachIndexed { index, badge ->
            if (index > 0) {
                val separator = TextView(row.context).apply {
                    text = ">"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(row.context, R.color.movitop_text_secondary))
                    setPadding(dp(row, 4f), 0, dp(row, 4f), 0)
                }
                row.addView(separator)
            }
            val chip = inflater.inflate(R.layout.item_line_badge_chip, row, false)
            val icon = chip.findViewById<ImageView>(R.id.badgeIcon)
            val label = chip.findViewById<TextView>(R.id.badgeLabel)
            label.text = badge.label
            label.setTextColor(badge.colorArgb)
            icon.setColorFilter(badge.colorArgb)
            (chip.background.mutate() as? GradientDrawable)?.setStroke(dp(row, 1.5f), badge.colorArgb)
            row.addView(chip)
        }
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.context.resources.displayMetrics.density).toInt()

    private fun formatDuration(context: android.content.Context, minutes: Int): String =
        if (minutes >= 60) {
            val hours = minutes / 60.0
            // "1.5" but "1" instead of "1.0"
            val hoursText = if (hours % 1.0 == 0.0) {
                hours.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", hours)
            }
            context.getString(R.string.duration_hours, hoursText)
        } else {
            context.getString(R.string.duration_minutes, minutes)
        }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RouteOption>() {
            override fun areItemsTheSame(old: RouteOption, new: RouteOption) = old.id == new.id
            override fun areContentsTheSame(old: RouteOption, new: RouteOption) = old == new
        }
    }
}
