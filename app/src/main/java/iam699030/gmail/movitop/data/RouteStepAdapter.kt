package iam699030.gmail.movitop.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R

/** Renders the step-by-step instructions in the route detail bottom sheet. */
class RouteStepAdapter : ListAdapter<RouteStep, RouteStepAdapter.StepViewHolder>(DIFF) {

    class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.stepTitle)
        val subtitle: TextView = view.findViewById(R.id.stepSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_step, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = getItem(position)
        holder.title.text = step.title
        if (step.subtitle.isNullOrEmpty()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = step.subtitle
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RouteStep>() {
            override fun areItemsTheSame(old: RouteStep, new: RouteStep) =
                old.title == new.title
            override fun areContentsTheSame(old: RouteStep, new: RouteStep) = old == new
        }
    }
}
