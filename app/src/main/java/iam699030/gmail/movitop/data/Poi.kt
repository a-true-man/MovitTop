package iam699030.gmail.movitop.data

import androidx.annotation.StringRes
import iam699030.gmail.movitop.R

/**
 * Broad POI groupings shown as the Home sheet's category filter chips.
 * Mapped from OSM amenity/shop/tourism/leisure tags by extract_osm_pois.py —
 * see that script's CATEGORY_MAP for the exact tag → category rules.
 */
enum class PoiCategory(@param:StringRes val labelRes: Int) {
    FOOD_DRINK(R.string.poi_category_food_drink),
    SHOPPING(R.string.poi_category_shopping),
    HEALTH(R.string.poi_category_health),
    FINANCE(R.string.poi_category_finance),
    LEISURE(R.string.poi_category_leisure),
    OTHER(R.string.poi_category_other);

    companion object {
        fun fromKey(key: String): PoiCategory? = when (key) {
            "food_drink" -> FOOD_DRINK
            "shopping" -> SHOPPING
            "health" -> HEALTH
            "finance" -> FINANCE
            "leisure" -> LEISURE
            "other" -> OTHER
            else -> null
        }
    }
}

/**
 * One point of interest (restaurant, pharmacy, shop, bank, ...), bundled
 * from OpenStreetMap — see [PoiRepository] and extract_osm_pois.py.
 */
data class Poi(
    val name: String,
    val category: PoiCategory,
    /** The raw OSM tag value (e.g. "pharmacy", "supermarket") for display/debugging. */
    val subtype: String,
    val address: String,
    /** Sun..Sat, each "HH:MM-HH:MM" or "" when unknown — see [iam699030.gmail.movitop.util.isOpenNow]. */
    val hoursByDay: List<String>,
    val point: GeoPoint
)
