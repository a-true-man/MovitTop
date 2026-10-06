package iam699030.gmail.movitop.nav

/**
 * Hands a computed navigation-step list from [iam699030.gmail.movitop.MainActivity]
 * to [iam699030.gmail.movitop.LiveNavigationActivity], and keeps the running
 * [NavigationEngine] alive across a "soft exit" (system Back minimizes the
 * live-nav screen back to MainActivity without ending the trip — only the
 * explicit X/close action clears [activeEngine]). In-memory only (same
 * process, single instance) — deliberately simple rather than Parcelable,
 * since a nav session is only ever produced and consumed within one live app
 * session; it does not need to survive process death.
 */
object PendingNavigation {
    /** Single-use handoff: a fresh step list for LiveNavigationActivity to build a new engine from. */
    var steps: List<NavigationStep>? = null

    /** The in-flight session, if any — set when a session starts, cleared only by an explicit end-navigation action. */
    var activeEngine: NavigationEngine? = null

    /** Shown on MainActivity's resume-navigation chip. */
    var activeDestinationLabel: String? = null
}
