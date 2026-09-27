package iam699030.gmail.movitop.data

import iam699030.gmail.movitop.data.api.GeocodeDto
import iam699030.gmail.movitop.data.api.MotisApi

/**
 * Resolves free-text Hebrew place names against the MOTIS geocoder.
 *
 * MOTIS often fails on spaced compound names ("מודיעין עילית") or returns the
 * wrong hit when a suffix like "עילית" matches a different city ("עתלית"). This
 * helper retries aliases, fixes common NLP typos, adds city context for streets,
 * and ranks candidates before giving up.
 */
class PlaceGeocoder(private val api: MotisApi) {

    class PlaceNotFoundException(place: String) :
        Exception("No geocode result for \"$place\"")

    suspend fun resolve(place: String, context: String? = null): String {
        val queries = buildQueries(place, context)
        for (query in queries) {
            val results = api.geocode(query)
            val hit = pickBest(place, query, results) ?: continue
            return "${hit.lat},${hit.lon}"
        }
        throw PlaceNotFoundException(place)
    }

    private fun buildQueries(place: String, context: String?): List<String> {
        val normalized = normalizePlaceName(place)
        val out = linkedSetOf<String>()

        fun add(query: String?) {
            val q = query?.trim().orEmpty()
            if (q.isNotEmpty()) out += q
        }

        add(normalized)
        PLACE_ALIASES[normalized]?.forEach { add(it) }

        if (' ' in normalized) {
            add(normalized.replace(' ', '-'))
        }
        if ('-' in normalized) {
            add(normalized.replace('-', ' '))
        }

        val contextNorm = context?.let { normalizePlaceName(it) }
        if (contextNorm != null) {
            add("$normalized, $contextNorm")
            add("$normalized $contextNorm")
            PLACE_ALIASES[contextNorm]?.forEach { alias ->
                add("$normalized, $alias")
                add("$normalized $alias")
            }
        }

        // "משך חכמה 62 מודיעין עילית" — try the trailing city + street variants.
        for (city in TRAILING_CITIES) {
            if (!normalized.endsWith(city) || normalized.length <= city.length) continue
            val street = normalized.removeSuffix(city).trim()
            if (street.isEmpty()) continue
            add(city)
            PLACE_ALIASES[city]?.forEach { add(it) }
            add("$street, $city")
            PLACE_ALIASES[city]?.forEach { alias ->
                add("$street, $alias")
                add("$street $alias")
            }
        }

        return out.toList()
    }

    private fun normalizePlaceName(place: String): String =
        place.trim()
            .replace(Regex("\\s+"), " ")
            .let { fixStrippedPrefix(it) }

    /** Repairs common NLP mistakes where a leading מ/ל was removed from a name. */
    private fun fixStrippedPrefix(place: String): String = when {
        place.startsWith("ודיעין") -> place.replaceFirst("ודיעין", "מודיעין")
        place.startsWith("שך חכמה") -> place.replaceFirst("שך חכמה", "משך חכמה")
        else -> place
    }

    private fun pickBest(
        original: String,
        query: String,
        results: List<GeocodeDto>
    ): GeocodeDto? {
        if (results.isEmpty()) return null

        val tokens = tokenizeForScoring(original.ifEmpty { query })
        val queryTokens = tokenizeForScoring(query)

        return results.maxByOrNull { hit ->
            scoreCandidate(tokens, queryTokens, hit)
        }?.takeIf { scoreCandidate(tokens, queryTokens, it) > 0 }
    }

    private fun tokenizeForScoring(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim('-', '–', '—') }
            .filter { it.length > 1 }

    private fun scoreCandidate(
        originalTokens: List<String>,
        queryTokens: List<String>,
        hit: GeocodeDto
    ): Int {
        val name = hit.name?.trim().orEmpty()
        if (name.isEmpty()) return -100

        var score = 0
        val nameLower = name.lowercase()
        val joinedOriginal = originalTokens.joinToString(" ").lowercase()

        if (name.equals(joinedOriginal, ignoreCase = true)) score += 120
        if (name.contains(joinedOriginal, ignoreCase = true)) score += 80

        for (token in originalTokens) {
            when {
                name.contains(token, ignoreCase = true) -> score += 15
                token.length >= 4 && nameLower.contains(token.lowercase()) -> score += 8
                else -> score -= 12
            }
        }

        // Prefer alias hits that contain the full intended compound name.
        for (alias in queryTokens) {
            if (alias.length >= 4 && name.contains(alias, ignoreCase = true)) score += 5
        }

        // Penalise suffix-only matches (ביתר עילית → עתלית).
        if (originalTokens.size >= 2) {
            val matched = originalTokens.count { name.contains(it, ignoreCase = true) }
            if (matched < 2) score -= 40
        }

        // Prefer Modiin Illit over plain Modiin when the user asked for עילית.
        if (originalTokens.any { it.contains("עילית") } && name.contains("Ilit", ignoreCase = true)) {
            score += 25
        }
        if (originalTokens.any { it.contains("עילית") } &&
            (name == "מודיעין" || name.equals("Modiin", ignoreCase = true))
        ) {
            score -= 30
        }

        return score
    }

    companion object {
        private val TRAILING_CITIES: List<String> = listOf(
            "מודיעין עילית",
            "מודיעין-עילית",
            "ביתר עילית",
            "ביתר-עילית",
            "מודיעין מכבים רעות",
            "תל אביב",
            "תל אביב יפו",
            "באר שבע",
            "בני ברק",
            "פתח תקווה",
            "ראשון לציון",
            "כפר סבא",
        )

        private val PLACE_ALIASES: Map<String, List<String>> = mapOf(
            "מודיעין עילית" to listOf("Modiin Illit", "מודיעין-עילית"),
            "מודיעין-עילית" to listOf("Modiin Illit", "מודיעין עילית"),
            "ביתר עילית" to listOf("Beitar Illit", "Beitar Ilit", "ביתר"),
            "ביתר-עילית" to listOf("Beitar Illit", "Beitar Ilit"),
            "מודיעין מכבים רעות" to listOf("Modiin-Maccabim-Reut", "Modiin"),
            "קריית גת" to listOf("Kiryat Gat"),
            "קריית שמונה" to listOf("Kiryat Shmona"),
            "קריית אתא" to listOf("Kiryat Ata"),
            "תל אביב" to listOf("Tel Aviv", "Tel Aviv-Yafo"),
            "תל אביב יפו" to listOf("Tel Aviv-Yafo", "Tel Aviv"),
            "תל-אביב" to listOf("Tel Aviv", "Tel Aviv-Yafo"),
            "ירושלים" to listOf("Jerusalem"),
            "באר שבע" to listOf("Beersheba", "Beer Sheva"),
            "בני ברק" to listOf("Bnei Brak"),
            "פתח תקווה" to listOf("Petah Tikva"),
            "ראשון לציון" to listOf("Rishon LeZion"),
            "רמת גן" to listOf("Ramat Gan"),
            "כפר סבא" to listOf("Kfar Saba"),
            "הרצליה" to listOf("Herzliya"),
            "נתניה" to listOf("Netanya"),
            "חיפה" to listOf("Haifa"),
            "עכו" to listOf("Acre", "Akko"),
            "טבריה" to listOf("Tiberias"),
            "צפת" to listOf("Safed", "Tzfat"),
            "אשדוד" to listOf("Ashdod"),
            "אשקלון" to listOf("Ashkelon"),
            "לוד" to listOf("Lod"),
            "רמלה" to listOf("Ramla"),
            "עפולה" to listOf("Afula"),
            "נצרת" to listOf("Nazareth"),
        )
    }
}
