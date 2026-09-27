package iam699030.gmail.movitop.data

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import iam699030.gmail.movitop.R

/** Renders the step-by-step instructions in the route detail bottom sheet. */
class RouteStepAdapter : ListAdapter<RouteStep, RouteStepAdapter.StepViewHolder>(DIFF) {

    class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.stepTitle)
        val subtitle: TextView = view.findViewById(R.id.stepSubtitle)
        val dot: View = view.findViewById(R.id.stepDot)
        val timeRange: TextView = view.findViewById(R.id.stepTimeRange)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_step, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = getItem(position)
        val context = holder.itemView.context
        holder.title.text = step.title
        if (step.subtitle.isNullOrEmpty()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = step.subtitle
        }

        // Same color as this leg's map polyline (see LegColors) — makes a
        // line change or a walking segment obvious in the step list too.
        val dotColor = if (step.colorArgb != 0) step.colorArgb else
            ContextCompat.getColor(context, R.color.movitop_primary)
        (holder.dot.background.mutate() as? GradientDrawable)?.setColor(dotColor)

        val depart = step.departTimeText
        val arrive = step.arriveTimeText
        holder.timeRange.text = when {
            depart != null && arrive != null -> context.getString(R.string.route_step_time_range, depart, arrive)
            depart != null -> context.getString(R.string.route_step_depart_only, depart)
            arrive != null -> context.getString(R.string.route_step_arrive_only, arrive)
            else -> null
        }
        holder.timeRange.visibility = if (holder.timeRange.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RouteStep>() {
            override fun areItemsTheSame(old: RouteStep, new: RouteStep) =
                old.title == new.title
            override fun areContentsTheSame(old: RouteStep, new: RouteStep) = old == new
        }
    }
}
