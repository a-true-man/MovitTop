package iam699030.gmail.movitop.data

import iam699030.gmail.movitop.nav.haversineMeters

/** One callable dispatch number shown in the driver/taxi station-picker dialog. */
data class CallableStation(
    val name: String,
    val phone: String,
    val subtitle: String? = null
)

/**
 * Nationwide "driver" (דרייבר) shared-ride operators — this service type is
 * ordered by phone/WhatsApp rather than tied to a physical city station, so
 * (unlike [TaxiStations]) the same short list is shown everywhere.
 */
object DriverStations {
    val nationwide: List<CallableStation> = listOf(
        CallableStation("הדרייברים (driverim.online)", "+972538862997", "וואטסאפ להזמנת נסיעה"),
        CallableStation("Hi Driver", "037575555", "הזמנת מונית/דרייבר 24/7"),
        CallableStation("דרייברים — משלוחים ונסיעות", "0534128066", "נסיעות ומשלוחים לכל הארץ")
    )
}

/** One city's central taxi dispatch number(s), plus the point used to match it to a trip's origin. */
data class CityTaxiStations(
    val city: String,
    val point: GeoPoint,
    val stations: List<CallableStation>
)

/**
 * A curated set of major-city taxi stations, real dispatch numbers found
 * online per city (not a single nationwide operator, unlike [DriverStations]
 * — taxi stations are local businesses). [nearest] picks by straight-line
 * distance to the trip's origin so a Jerusalem search never surfaces a
 * Beer Sheva number.
 */
object TaxiStations {
    val byCity: List<CityTaxiStations> = listOf(
        CityTaxiStations(
            "ירושלים", GeoPoint(31.7683, 35.2137),
            listOf(CallableStation("מוניות ירושלים", "023762055", "זמינה 24 שעות"))
        ),
        CityTaxiStations(
            "תל אביב-יפו", GeoPoint(32.0853, 34.7818),
            listOf(CallableStation("מוניות קסטל", "036990111"))
        ),
        CityTaxiStations(
            "חיפה", GeoPoint(32.7940, 34.9896),
            listOf(CallableStation("מוניות רוממה", "048244644"))
        ),
        CityTaxiStations(
            "באר שבע", GeoPoint(31.2518, 34.7913),
            listOf(CallableStation("מוניות עירוני באר שבע", "086656667"))
        ),
        CityTaxiStations(
            "ראשון לציון", GeoPoint(31.9730, 34.7925),
            listOf(CallableStation("מוניות החוף", "039501003"))
        ),
        CityTaxiStations(
            "פתח תקווה", GeoPoint(32.0917, 34.8878),
            listOf(CallableStation("מוניות ארלוזורוב", "039222712"))
        ),
        CityTaxiStations(
            "אשדוד", GeoPoint(31.8044, 34.6553),
            listOf(CallableStation("תחנת מוניות קניון אשדוד", "088562222"))
        ),
        CityTaxiStations(
            "נתניה", GeoPoint(32.3215, 34.8532),
            listOf(CallableStation("מוניות נתניה Seven", "099777777"))
        ),
        CityTaxiStations(
            "חולון", GeoPoint(32.0167, 34.7792),
            listOf(CallableStation("מוניות וולפסון 2000 חולון", "035036666"))
        ),
        CityTaxiStations(
            "רמת גן ובני ברק", GeoPoint(32.0823, 34.8141),
            listOf(CallableStation("מוניות רמת גן", "0723926072"))
        ),
        CityTaxiStations(
            "רחובות", GeoPoint(31.8928, 34.8113),
            listOf(CallableStation("מוניות רכבת רחובות", "089120120"))
        ),
        CityTaxiStations(
            "אשקלון", GeoPoint(31.6693, 34.5715),
            listOf(CallableStation("מוניות השעון אשקלון", "086788888"))
        ),
        CityTaxiStations(
            "אילת", GeoPoint(29.5581, 34.9482),
            listOf(CallableStation("תחנת מוניות פתאל אילת", "086334141"))
        ),
        CityTaxiStations(
            "טבריה", GeoPoint(32.7922, 35.5312),
            listOf(CallableStation("מוניות טבריה", "046717222"))
        ),
        CityTaxiStations(
            "נוף הגליל", GeoPoint(32.7098, 35.3037),
            listOf(CallableStation("מוניות בן גוריון", "046566060"))
        ),
        CityTaxiStations(
            "כפר סבא", GeoPoint(32.1858, 34.9077),
            listOf(CallableStation("תחנת מוניות כפר סבא", "097661111"))
        ),
        CityTaxiStations(
            "בית שמש", GeoPoint(31.7476, 34.9885),
            listOf(CallableStation("מוניות בית שמש", "039611112"))
        )
    )

    /** Nearest curated city to [origin] by straight-line distance; [byCity]'s first entry if [origin] is unknown. */
    fun nearest(origin: GeoPoint?): CityTaxiStations {
        if (origin == null) return byCity.first()
        return byCity.minBy { haversineMeters(origin, it.point) }
    }
}
