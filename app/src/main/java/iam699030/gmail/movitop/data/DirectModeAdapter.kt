package iam699030.gmail.movitop.data

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R
import java.util.Locale

/**
 * Compact walk/bike/driver/taxi cards shown in a horizontal row above the
 * transit suggestions (see [RouteAdapter]) — mirrors how Moovit separates
 * "walking & biking" from its main route list instead of mixing every mode
 * into one long vertical list.
 */
class DirectModeAdapter(
    private val onRouteSelected: (RouteOption) -> Unit,
    // Horizontal RecyclerViews have no natural "leave the row" edge for a
    // D-Pad the way a vertical list's last item does — without this, DOWN
    // from any card here would fall through to Android's generic spatial
    // focus search, which is unreliable across a mixed horizontal/vertical
    // layout. Returning true means it was handled (exited the row);
    // false lets the key propagate normally (e.g. no transit list to enter).
    private val onDownPressed: () -> Boolean = { false }
) : ListAdapter<RouteOption, DirectModeAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.directModeIcon)
        val label: TextView = view.findViewById(R.id.directModeLabel)
        val duration: TextView = view.findViewById(R.id.directModeDuration)
        val subtitle: TextView = view.findViewById(R.id.directModeSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_direct_mode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val option = getItem(position)
        val context = holder.itemView.context

        holder.icon.setImageResource(option.mode.iconRes)
        holder.icon.contentDescription = context.getString(option.mode.labelRes)
        holder.label.text = context.getString(option.mode.labelRes)
        holder.duration.text = formatDuration(context, option.durationMinutes)
        val subtitle = option.priceText ?: option.distanceText
        if (subtitle.isNullOrEmpty()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = subtitle
        }

        holder.itemView.setOnClickListener { onRouteSelected(option) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    onRouteSelected(option)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> onDownPressed()
                else -> false
            }
        }
    }

    private fun formatDuration(context: android.content.Context, minutes: Int): String =
        if (minutes >= 60) {
            val hours = minutes / 60.0
            val hoursText = if (hours % 1.0 == 0.0) hours.toInt().toString() else String.format(Locale.US, "%.1f", hours)
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
