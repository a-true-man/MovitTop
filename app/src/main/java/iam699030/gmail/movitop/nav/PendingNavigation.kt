package iam699030.gmail.movitop.nav

/**
 * Hands a computed navigation-step list from [iam699030.gmail.movitop.MainActivity]
 * to [iam699030.gmail.movitop.LiveNavigationActivity]. In-memory only (same
 * process, single instance) — deliberately simple rather than Parcelable,
 * since a nav plan is only ever produced and consumed within one live app
 * session; it does not need to survive process death.
 */
object PendingNavigation {
    var steps: List<NavigationStep>? = null
}
