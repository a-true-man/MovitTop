package iam699030.gmail.movitop.data

/**
 * All of one line's upcoming departures at a stop, collapsed into a single
 * card (see StopLineAdapter). The map's stop-tap sheet fetches a flat,
 * chronological [NearbyDeparture] list where the same line reappears once per
 * trip — grouping by [routeShortName] avoids showing that same line five
 * times in a row; [epochsMillis] (chronological) is what lets the card still
 * show several of its upcoming times at a glance.
 */
data class GroupedLineDeparture(
    val routeShortName: String,
    val headsign: String,
    val agencyName: String?,
    val colorArgb: Int,
    val epochsMillis: List<Long>
)

/** Groups a chronological departures list by line, each line kept at its soonest occurrence's position. */
fun List<NearbyDeparture>.groupByLine(): List<GroupedLineDeparture> {
    val byLine = LinkedHashMap<String, MutableList<NearbyDeparture>>()
    forEach { departure -> byLine.getOrPut(departure.routeShortName) { mutableListOf() } += departure }
    return byLine.values.map { group ->
        val first = group.first()
        GroupedLineDeparture(
            routeShortName = first.routeShortName,
            headsign = first.headsign,
            agencyName = first.agencyName,
            colorArgb = first.colorArgb,
            epochsMillis = group.map { it.departEpochMillis }
        )
    }
}
