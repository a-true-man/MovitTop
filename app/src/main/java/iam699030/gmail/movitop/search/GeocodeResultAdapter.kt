package iam699030.gmail.movitop.search

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.GeocodePlace

class GeocodeResultAdapter(
    private val onPlaceSelected: (GeocodePlace) -> Unit
) : ListAdapter<GeocodePlace, GeocodeResultAdapter.PlaceViewHolder>(DIFF) {

    class PlaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.placeName)
        val subtitle: TextView = view.findViewById(R.id.placeSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_geocode_result, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        val place = getItem(position)
        holder.name.text = place.name
        if (place.subtitle.isNullOrBlank()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = place.subtitle
        }
        holder.itemView.setOnClickListener { onPlaceSelected(place) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onPlaceSelected(place)
                true
            } else {
                false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GeocodePlace>() {
            override fun areItemsTheSame(old: GeocodePlace, new: GeocodePlace) =
                old.displayKey == new.displayKey

            override fun areContentsTheSame(old: GeocodePlace, new: GeocodePlace) = old == new
        }
    }
}
