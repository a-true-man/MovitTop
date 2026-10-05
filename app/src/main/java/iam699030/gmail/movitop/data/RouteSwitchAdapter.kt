package iam699030.gmail.movitop.data

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
import com.google.android.material.card.MaterialCardView
import iam699030.gmail.movitop.R
import java.util.Locale

/**
 * Quick-switch strip shown above the route detail pane — every option from
 * the last search (direct modes and transit alike), so switching to a
 * different one just re-opens its detail in place instead of forcing the
 * user back to the results list to pick again.
 */
class RouteSwitchAdapter(
    private val onRouteSelected: (RouteOption) -> Unit,
    // Mirrors DirectModeAdapter's onDownPressed: DPAD_DOWN off this
    // horizontal row has no natural "next" target for spatial focus search,
    // so the caller wires it explicitly into the step list below.
    private val onDownPressed: () -> Boolean = { false }
) : ListAdapter<RouteOption, RouteSwitchAdapter.ViewHolder>(DIFF) {

    private var selectedId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val icon: ImageView = view.findViewById(R.id.routeSwitchIcon)
        val duration: TextView = view.findViewById(R.id.routeSwitchDuration)
        val subtitle: TextView = view.findViewById(R.id.routeSwitchSubtitle)
    }

    fun setSelectedId(id: String) {
        if (id == selectedId) return
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_switch_option, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val option = getItem(position)
        val context = holder.itemView.context

        holder.icon.setImageResource(option.mode.iconRes)
        holder.icon.contentDescription = context.getString(option.mode.labelRes)
        holder.duration.text = formatDuration(context, option.durationMinutes)

        val subtitle = option.transitBadges.firstOrNull()?.label ?: option.priceText ?: option.distanceText
        holder.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.subtitle.text = subtitle

        val isSelected = option.id == selectedId
        val strokeColor = ContextCompat.getColor(
            context,
            if (isSelected) R.color.movitop_primary else R.color.movitop_input_stroke
        )
        holder.card.strokeWidth = dp(holder.itemView, if (isSelected) 2f else 1f)
        holder.card.setStrokeColor(strokeColor)
        holder.card.setCardBackgroundColor(
            ContextCompat.getColor(
                context,
                if (isSelected) R.color.movitop_chip_bg else R.color.movitop_surface
            )
        )

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

    private fun dp(view: View, value: Float): Int =
        (value * view.context.resources.displayMetrics.density).toInt()

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
