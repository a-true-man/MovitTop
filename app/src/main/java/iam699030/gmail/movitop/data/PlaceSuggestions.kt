package iam699030.gmail.movitop.data

/**
 * Fast local prefix matching for Hebrew place names. Supplements MOTIS geocode
 * so suggestions appear instantly while the network request is in flight.
 */
object PlaceSuggestions {

    private val KNOWN_PLACES: List<String> = listOf(
        "ירושלים",
        "תל אביב",
        "תל אביב יפו",
        "חיפה",
        "באר שבע",
        "אשדוד",
        "אשקלון",
        "נתניה",
        "בני ברק",
        "פתח תקווה",
        "ראשון לציון",
        "רמת גן",
        "חולון",
        "בת ים",
        "כפר סבא",
        "הרצליה",
        "רעננה",
        "מודיעין",
        "מודיעין עילית",
        "מודיעין מכבים רעות",
        "בית שמש",
        "ביתר עילית",
        "לוד",
        "רמלה",
        "עפולה",
        "נצרת",
        "עכו",
        "טבריה",
        "צפת",
        "אילת",
        "קריית גת",
        "קריית שמונה",
        "קריית אתא",
        "משך חכמה",
        "בית שמש",
        "Jerusalem",
        "Tel Aviv",
        "Haifa",
        "Modiin Illit",
        "Beitar Illit",
    )

    fun localMatches(query: String, limit: Int = 8): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        return KNOWN_PLACES
            .mapNotNull { place ->
                val score = matchScore(place, q) ?: return@mapNotNull null
                place to score
            }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
    }

    private fun matchScore(place: String, query: String): Int? {
        val placeLower = place.lowercase()
        val queryLower = query.lowercase()
        return when {
            place.equals(query, ignoreCase = true) -> 0
            place.startsWith(query, ignoreCase = true) -> 1
            placeLower.contains(queryLower) -> 10 + placeLower.indexOf(queryLower)
            else -> null
        }
    }
}
