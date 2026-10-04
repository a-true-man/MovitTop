package iam699030.gmail.movitop.nearby

import iam699030.gmail.movitop.data.NearbyDeparture
import iam699030.gmail.movitop.data.Poi
import iam699030.gmail.movitop.data.PoiCategory
import iam699030.gmail.movitop.data.RavKavStation

/**
 * One row in the Home sheet's mixed nearby list — a transit departure, a
 * Rav-Kav charging point, or a generic POI, unioned so they can share one
 * distance-sorted, category-filterable [androidx.recyclerview.widget.RecyclerView].
 */
sealed class NearbyListItem(val distanceMeters: Double, val isOpenNow: Boolean?) {

    class Departure(val departure: NearbyDeparture) :
        NearbyListItem(departure.distanceMeters, isOpenNow = null)

    class RavKav(val station: RavKavStation, distanceMeters: Double, isOpenNow: Boolean?) :
        NearbyListItem(distanceMeters, isOpenNow)

    class Place(val poi: Poi, distanceMeters: Double, isOpenNow: Boolean?) :
        NearbyListItem(distanceMeters, isOpenNow)
}

/** The Home sheet's category filter chips — [TRANSIT] and [CHARGING] map to the
 * existing stop/Rav-Kav data, the rest to [PoiCategory]. */
enum class NearbyFilter {
    ALL, TRANSIT, CHARGING, FOOD_DRINK, SHOPPING, HEALTH, FINANCE, LEISURE, OTHER;

    fun matches(item: NearbyListItem): Boolean = when (this) {
        ALL -> true
        TRANSIT -> item is NearbyListItem.Departure
        CHARGING -> item is NearbyListItem.RavKav
        else -> (item as? NearbyListItem.Place)?.poi?.category?.name == name
    }
}

enum class NearbySort { DISTANCE, OPEN_NOW }
