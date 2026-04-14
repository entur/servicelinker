package no.entur.servicelinker.handlers

import no.entur.servicelinker.model.InjectionPlan

/**
 * Shared mutable state between handlers processing a single document.
 *
 * Flags start as `true` when the plan has nothing to inject for that
 * section, so handlers never fire unnecessarily — "nothing to inject"
 * and "already injected" are the same state.
 */
class InjectionContext(val plan: InjectionPlan) {
    var stopsInjected = plan.stubScheduledStopPoints.isEmpty()
    var linksInjected = plan.newServiceLinks.isEmpty()
    var currentJpId: String? = null
}
