package iam699030.gmail.movitop.data

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

fun GeocodeDto.toGeocodePlace(): GeocodePlace? {
    val placeName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return GeocodePlace(
        name = placeName,
        subtitle = buildSubtitle(placeName, city, state, country, type),
        lat = lat,
        lon = lon,
        id = id
    )
}

private fun buildSubtitle(
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

    type?.trim()?.takeIf { it.isNotEmpty() }?.let { return formatPlaceType(it) }

    val commaParts = name.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (commaParts.size >= 2) {
        return commaParts.drop(1).joinToString(" · ")
    }
    return null
}

private fun formatPlaceType(type: String): String = when (type.uppercase()) {
    "STOP" -> "תחנה"
    "COORDINATE" -> "נקודה"
    "PLACE" -> "מקום"
    "STREET" -> "רחוב"
    "ADDRESS" -> "כתובת"
    else -> type
}
