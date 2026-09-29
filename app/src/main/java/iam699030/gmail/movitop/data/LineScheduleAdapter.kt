package iam699030.gmail.movitop.data

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
 * One line's full schedule at a stop, drilled into from [StopLineAdapter] —
 * just times, since the pane's header already names the line (repeating a
 * line badge on every row here was the whole thing being fixed). A time
 * still shows a live "in X" label while it's upcoming (re-bound on a timer,
 * see util/Ticker.kt); once it's passed, the label just goes blank instead
 * of freezing on a wrong countdown.
 */
class LineScheduleAdapter : ListAdapter<Long, LineScheduleAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.scheduleTime)
        val relative: TextView = view.findViewById(R.id.scheduleRelative)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line_schedule_time, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val epochMillis = getItem(position)
        val context = holder.itemView.context

        holder.time.text = CLOCK_FORMAT.format(Date(epochMillis))
        holder.relative.text = if (RelativeTime.isStale(epochMillis)) {
            ""
        } else {
            RelativeTime.describe(context, epochMillis)
        }
    }

    companion object {
        private val CLOCK_FORMAT get() = SimpleDateFormat("HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<Long>() {
            override fun areItemsTheSame(old: Long, new: Long) = old == new
            override fun areContentsTheSame(old: Long, new: Long) = old == new
        }
    }
}
