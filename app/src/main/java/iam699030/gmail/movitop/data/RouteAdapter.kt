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

/** Renders the 5 [RouteOption] summaries inside the results bottom sheet. */
class RouteAdapter(
    private val onRouteSelected: (RouteOption) -> Unit
) : ListAdapter<RouteOption, RouteAdapter.RouteViewHolder>(DIFF) {

    class RouteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.modeIcon)
        val label: TextView = view.findViewById(R.id.modeLabel)
        val duration: TextView = view.findViewById(R.id.modeDuration)
        val price: TextView = view.findViewById(R.id.modePrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_option, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val option = getItem(position)
        val context = holder.itemView.context

        holder.icon.setImageResource(option.mode.iconRes)
        holder.label.text = context.getString(option.mode.labelRes)
        holder.duration.text = formatDuration(holder, option.durationMinutes)
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

        if (option.priceText.isNullOrEmpty()) {
            holder.price.visibility = View.GONE
        } else {
            holder.price.visibility = View.VISIBLE
            holder.price.text = option.priceText
        }
    }

    private fun formatDuration(holder: RouteViewHolder, minutes: Int): String {
        val context = holder.itemView.context
        return if (minutes >= 60) {
            val hours = minutes / 60.0
            // "1.5" but "1" instead of "1.0"
            val hoursText = if (hours % 1.0 == 0.0) {
                hours.toInt().toString()
            } else {
                String.format("%.1f", hours)
            }
            context.getString(R.string.duration_hours, hoursText)
        } else {
            context.getString(R.string.duration_minutes, minutes)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RouteOption>() {
            override fun areItemsTheSame(old: RouteOption, new: RouteOption) = old.mode == new.mode
            override fun areContentsTheSame(old: RouteOption, new: RouteOption) = old == new
        }
    }
}
