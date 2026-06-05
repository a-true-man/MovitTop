package iam699030.gmail.movitop.chat

/**
 * Lightweight Hebrew intent parser for the chat box.
 *
 * It removes filler/stop words and then uses the directional prefixes
 * 'מ' (from / origin) and 'ל' (to / destination) to split a sentence into an
 * [ParsedTrip].
 *
 * Example:
 *   "אני רוצה לנסוע מרחוב הרצל לירושלים"
 *     -> origin = "רחוב הרצל", destination = "ירושלים"
 *
 * If no directional prefix is present, the whole cleaned phrase is treated as a
 * destination (e.g. a quick-chip tap like "בית" or a bare "ירושלים").
 */
data class ParsedTrip(
    val origin: String?,
    val destination: String?
)

object HebrewParser {

    /** Filler words and travel verbs that carry no place information. */
    private val STOP_WORDS: Set<String> = setOf(
        "אני", "אנחנו", "אתה", "את", "רוצה", "רוצים", "צריך", "צריכה", "צריכים",
        "מעוניין", "מעוניינת", "בבקשה", "אפשר", "כדי", "איך", "מתי", "מה", "כמה",
        "להגיע", "להגעה", "לנסוע", "לנסיעה", "נוסע", "נוסעת", "נוסעים", "ללכת",
        "לטייל", "לרכב", "לצאת", "מגיע", "מגיעה", "הולך", "הולכת", "וגם", "גם",
        "עכשיו", "היום", "מחר", "כאן", "שם", "בערך", "אולי", "וגם", "של", "עם",
        "בוא", "בואו", "תן", "תני", "לי", "לנו"
    )

    // Hebrew letters used as inseparable prepositions.
    private const val FROM_PREFIX = 'מ'
    private const val TO_PREFIX = 'ל'

    fun parse(input: String): ParsedTrip {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) {
            return ParsedTrip(origin = null, destination = null)
        }

        val origin = StringBuilder()
        val destination = StringBuilder()

        // section: 0 = none yet, 1 = collecting origin, 2 = collecting destination
        var section = 0
        var sawPrefix = false

        for (token in tokens) {
            when {
                startsWithPrefix(token, FROM_PREFIX) -> {
                    section = 1
                    sawPrefix = true
                    append(origin, token.substring(1))
                }
                startsWithPrefix(token, TO_PREFIX) -> {
                    section = 2
                    sawPrefix = true
                    append(destination, token.substring(1))
                }
                section == 1 -> append(origin, token)
                section == 2 -> append(destination, token)
                else -> {
                    // No prefix seen yet: accumulate as an implicit destination.
                    append(destination, token)
                }
            }
        }

        // Without any directional prefix, the phrase is just a destination.
        if (!sawPrefix) {
            val dest = tokens.joinToString(" ").trim()
            return ParsedTrip(origin = null, destination = dest.ifEmpty { null })
        }

        return ParsedTrip(
            origin = origin.toString().trim().ifEmpty { null },
            destination = destination.toString().trim().ifEmpty { null }
        )
    }

    private fun tokenize(input: String): List<String> =
        input.trim()
            .split(Regex("\\s+"))
            .map { it.trim().trim('.', ',', '!', '?', '"', '\'', '\u05F3', '\u05F4') }
            .filter { it.isNotEmpty() && it !in STOP_WORDS }

    /** A real prefix needs at least one more letter after the preposition. */
    private fun startsWithPrefix(token: String, prefix: Char): Boolean =
        token.length > 1 && token[0] == prefix

    private fun append(sb: StringBuilder, word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(w)
    }
}
