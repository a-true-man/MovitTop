package iam699030.gmail.movitop.data

import android.content.Context
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.api.GeocodeDto
import java.io.Serializable

/** A geocoded location shown in the search overlay autocomplete list. */
data class GeocodePlace(
    val name: String,
    val subtitle: String?,
    val lat: Double,
    val lon: Double,
    val id: String? = null
) : Serializable {

    val displayKey: String get() = id ?: "$name@$lat,$lon"
}

fun GeocodeDto.toGeocodePlace(context: Context): GeocodePlace? {
    val placeName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return GeocodePlace(
        name = placeName,
        subtitle = buildSubtitle(context, placeName, city, state, country, type),
        lat = lat,
        lon = lon,
        id = id
    )
}

private fun buildSubtitle(
    context: Context,
    name: String,
    city: String?,
    state: String?,
    country: String?,
    type: String?
): String? {
    val parts = linkedSetOf<String>()
    city?.trim()?.takeIf { it.isNotEmpty() && !name.equals(it, ignoreCase = true) }?.let(parts::add)
    state?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
    country?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
    if (parts.isNotEmpty()) return parts.joinToString(" · ")

    type?.trim()?.takeIf { it.isNotEmpty() }?.let { return formatPlaceType(context, it) }

    val commaParts = name.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (commaParts.size >= 2) {
        return commaParts.drop(1).joinToString(" · ")
    }
    return null
}

/**
 * These used to be hardcoded Hebrew literals, which meant a search result's
 * subtitle stayed in Hebrew even after switching the app to English (see
 * [iam699030.gmail.movitop.SettingsActivity]) — routed through string
 * resources now so it follows the same per-app locale as everything else.
 */
private fun formatPlaceType(context: Context, type: String): String = when (type.uppercase()) {
    "STOP" -> context.getString(R.string.place_type_stop)
    "COORDINATE" -> context.getString(R.string.place_type_coordinate)
    "PLACE" -> context.getString(R.string.place_type_place)
    "STREET" -> context.getString(R.string.place_type_street)
    "ADDRESS" -> context.getString(R.string.place_type_address)
    else -> type
}
