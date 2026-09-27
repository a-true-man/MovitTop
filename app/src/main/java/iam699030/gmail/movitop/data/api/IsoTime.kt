package iam699030.gmail.movitop.data.api

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Parses the ISO-8601 timestamps MOTIS returns (`startTime`/`endTime`/`departure`/...). */
object IsoTime {

    // MOTIS emits UTC with a literal 'Z' (see RealMotisRepository.isoFor) — matched on the way out.
    private val PARSE_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    /** Parsed to epoch millis, or null if [iso] is null/unparseable. */
    fun toEpochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        for (pattern in PARSE_FORMATS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(iso)?.time
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    /** Formats [iso] as a local "HH:mm" clock time, or null if unparseable. */
    fun toClockText(iso: String?): String? {
        val epoch = toEpochMillis(iso) ?: return null
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))
    }
}
