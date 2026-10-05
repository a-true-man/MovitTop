package iam699030.gmail.movitop.nearby

import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.RelativeTime

/**
 * Renders [NearbyListItem]'s three kinds with two row layouts: departures
 * reuse [iam699030.gmail.movitop.R.layout.item_nearby_departure] (line +
 * clock time), Rav-Kav/POI rows share [R.layout.item_nearby_place] (name +
 * category/distance + optional open-now badge) since they're shaped alike.
 */
class NearbyListAdapter(
    private val onDepartureSelected: (NearbyListItem.Departure) -> Unit,
    private val onPlaceSelected: (NearbyListItem) -> Unit
) : ListAdapter<NearbyListItem, RecyclerView.ViewHolder>(DIFF) {

    private class DepartureHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.nearbyColorDot)
        val line: TextView = view.findViewById(R.id.nearbyLine)
        val stopInfo: TextView = view.findViewById(R.id.nearbyStopInfo)
        val time: TextView = view.findViewById(R.id.nearbyTime)
        val minutes: TextView = view.findViewById(R.id.nearbyMinutes)
    }

    private class PlaceHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.placeColorDot)
        val name: TextView = view.findViewById(R.id.placeName)
        val subtitle: TextView = view.findViewById(R.id.placeSubtitle)
        val openNow: TextView = view.findViewById(R.id.placeOpenNow)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is NearbyListItem.Departure -> VIEW_TYPE_DEPARTURE
        else -> VIEW_TYPE_PLACE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_DEPARTURE) {
            DepartureHolder(inflater.inflate(R.layout.item_nearby_departure, parent, false))
        } else {
            PlaceHolder(inflater.inflate(R.layout.item_nearby_place, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is NearbyListItem.Departure -> bindDeparture(holder as DepartureHolder, item)
            else -> bindPlace(holder as PlaceHolder, item)
        }
    }

    private fun bindDeparture(holder: DepartureHolder, item: NearbyListItem.Departure) {
        val departure = item.departure
        val context = holder.itemView.context
        (holder.dot.background.mutate() as? GradientDrawable)?.setColor(departure.colorArgb)
        holder.line.text = if (departure.headsign.isNotBlank()) {
            "${departure.routeShortName} · ${departure.headsign}"
        } else {
            departure.routeShortName
        }
        val distanceText = context.getString(R.string.nearby_distance_meters, departure.distanceMeters.toInt())
        holder.stopInfo.text = context.getString(R.string.nearby_label_with_distance, departure.stopName, distanceText)
        holder.time.text = departure.departTimeText
        holder.minutes.text = if (RelativeTime.isStale(departure.departEpochMillis)) {
            ""
        } else {
            RelativeTime.describe(context, departure.departEpochMillis)
        }
        holder.itemView.setOnClickListener { onDepartureSelected(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onDepartureSelected(item); true
            } else {
                false
            }
        }
    }

    private fun bindPlace(holder: PlaceHolder, item: NearbyListItem) {
        val context = holder.itemView.context
        val distanceText = context.getString(R.string.nearby_distance_meters, item.distanceMeters.toInt())
        val dotColor: Int
        when (item) {
            is NearbyListItem.RavKav -> {
                holder.name.text = item.station.name
                holder.subtitle.text = context.getString(
                    R.string.nearby_label_with_distance,
                    context.getString(R.string.poi_category_charging),
                    distanceText
                )
                dotColor = androidx.core.content.ContextCompat.getColor(context, R.color.movitop_ravkav_marker)
            }
            is NearbyListItem.Place -> {
                holder.name.text = item.poi.name
                holder.subtitle.text = context.getString(
                    R.string.nearby_label_with_distance,
                    context.getString(item.poi.category.labelRes),
                    distanceText
                )
                dotColor = androidx.core.content.ContextCompat.getColor(context, poiDotColorRes(item.poi.category))
            }
            else -> return
        }
        (holder.dot.background.mutate() as? GradientDrawable)?.setColor(dotColor)

        when (item.isOpenNow) {
            true -> {
                holder.openNow.visibility = View.VISIBLE
                holder.openNow.text = context.getString(R.string.poi_open_now)
                holder.openNow.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.movitop_departs_soon))
            }
            false -> {
                holder.openNow.visibility = View.VISIBLE
                holder.openNow.text = context.getString(R.string.poi_closed_now)
                holder.openNow.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.movitop_text_secondary))
            }
            null -> holder.openNow.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onPlaceSelected(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onPlaceSelected(item); true
            } else {
                false
            }
        }
    }

    private fun poiDotColorRes(category: iam699030.gmail.movitop.data.PoiCategory): Int = when (category) {
        iam699030.gmail.movitop.data.PoiCategory.FOOD_DRINK -> R.color.movitop_poi_food_drink
        iam699030.gmail.movitop.data.PoiCategory.SHOPPING -> R.color.movitop_poi_shopping
        iam699030.gmail.movitop.data.PoiCategory.HEALTH -> R.color.movitop_poi_health
        iam699030.gmail.movitop.data.PoiCategory.FINANCE -> R.color.movitop_poi_finance
        iam699030.gmail.movitop.data.PoiCategory.LEISURE -> R.color.movitop_poi_leisure
        iam699030.gmail.movitop.data.PoiCategory.OTHER -> R.color.movitop_poi_other
    }

    companion object {
        private const val VIEW_TYPE_DEPARTURE = 0
        private const val VIEW_TYPE_PLACE = 1

        private val DIFF = object : DiffUtil.ItemCallback<NearbyListItem>() {
            override fun areItemsTheSame(old: NearbyListItem, new: NearbyListItem): Boolean = when {
                old is NearbyListItem.Departure && new is NearbyListItem.Departure ->
                    old.departure.stopName == new.departure.stopName &&
                        old.departure.departTimeText == new.departure.departTimeText &&
                        old.departure.routeShortName == new.departure.routeShortName
                old is NearbyListItem.RavKav && new is NearbyListItem.RavKav ->
                    old.station.point == new.station.point && old.station.name == new.station.name
                old is NearbyListItem.Place && new is NearbyListItem.Place ->
                    old.poi.point == new.poi.point && old.poi.name == new.poi.name
                else -> false
            }

            override fun areContentsTheSame(old: NearbyListItem, new: NearbyListItem): Boolean = when {
                old is NearbyListItem.Departure && new is NearbyListItem.Departure -> old.departure == new.departure
                old is NearbyListItem.RavKav && new is NearbyListItem.RavKav -> old.station == new.station
                old is NearbyListItem.Place && new is NearbyListItem.Place -> old.poi == new.poi
                else -> false
            }
        }
    }
}
