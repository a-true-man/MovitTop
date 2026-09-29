package iam699030.gmail.movitop.data

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One card per line at a map-tapped stop (see [GroupedLineDeparture]) — the
 * top-level pane of the stop-tap results sheet. Shows the line's next
 * upcoming departure with a live countdown plus a couple more of its
 * upcoming times, so the several trips a busy line makes in the fetch window
 * don't repeat as separate rows. Tapping a card drills into that line's full
 * schedule at this stop (see MainActivity.showLineScheduleAtStop).
 */
class StopLineAdapter(
    private val onSelected: (GroupedLineDeparture) -> Unit
) : ListAdapter<GroupedLineDeparture, StopLineAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.lineColorDot)
        val title: TextView = view.findViewById(R.id.lineTitle)
        val agency: TextView = view.findViewById(R.id.lineAgency)
        val nextTime: TextView = view.findViewById(R.id.lineNextTime)
        val nextRelative: TextView = view.findViewById(R.id.lineNextRelative)
        val upcoming: TextView = view.findViewById(R.id.lineUpcomingTimes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stop_line_summary, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        val now = System.currentTimeMillis()

        (holder.dot.background.mutate() as? GradientDrawable)?.setColor(item.colorArgb)
        holder.title.text = if (item.headsign.isNotBlank()) {
            "${item.routeShortName} · ${item.headsign}"
        } else {
            item.routeShortName
        }
        holder.agency.text = item.agencyName.orEmpty()
        holder.agency.visibility = if (item.agencyName.isNullOrBlank()) View.GONE else View.VISIBLE

        // Once the soonest fetched time has passed, advance to the next one
        // still ahead — the whole point of storing every upcoming epoch
        // instead of just one is that this needs no fresh engine query. If
        // every fetched epoch has passed, there's nothing left to show: don't
        // fall back to the last (already-departed) one, or the countdown
        // freezes on "departing now" forever instead of going blank.
        val upcomingEpochs = item.epochsMillis.filter { it > now }
        val next = upcomingEpochs.firstOrNull()
        if (next != null) {
            holder.nextTime.text = CLOCK_FORMAT.format(Date(next))
            holder.nextRelative.text = RelativeTime.describe(context, next, now)
        } else {
            holder.nextTime.text = ""
            holder.nextRelative.text = ""
        }

        val rest = upcomingEpochs.drop(1).take(MAX_EXTRA_TIMES_SHOWN)
        if (rest.isEmpty()) {
            holder.upcoming.visibility = View.GONE
        } else {
            holder.upcoming.visibility = View.VISIBLE
            val joined = rest.joinToString(" · ") { CLOCK_FORMAT.format(Date(it)) }
            holder.upcoming.text = context.getString(R.string.stop_line_also_at, joined)
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
        private const val MAX_EXTRA_TIMES_SHOWN = 3
        private val CLOCK_FORMAT get() = SimpleDateFormat("HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<GroupedLineDeparture>() {
            override fun areItemsTheSame(old: GroupedLineDeparture, new: GroupedLineDeparture) =
                old.routeShortName == new.routeShortName
            override fun areContentsTheSame(old: GroupedLineDeparture, new: GroupedLineDeparture) = old == new
        }
    }
}
