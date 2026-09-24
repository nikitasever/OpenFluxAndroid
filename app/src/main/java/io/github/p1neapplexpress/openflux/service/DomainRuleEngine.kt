package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.DomainRulesPreferences

enum class RouteDecision { BYPASS, TUNNEL }

/**
 * Decides, for a destination IP, whether the connection should bypass the
 * tunnel (dial out directly, protect()'d) or go through it as usual.
 *
 * IPs are shared (CDNs, load balancers), so a single IP can map to several
 * domains with conflicting rules. Ambiguity always resolves to TUNNEL: for a
 * censorship-circumvention app, accidentally leaving a domain inside the
 * tunnel is a UX annoyance, but accidentally leaking one outside it defeats
 * the app's purpose - the unsafe direction is bypassing, so ties go the
 * other way.
 */
class DomainRuleEngine(private val prefs: DomainRulesPreferences) {

    fun decide(ip: String): RouteDecision {
        if (!prefs.isEnabled) return RouteDecision.TUNNEL
        val rules = prefs.domains
        if (rules.isEmpty()) return RouteDecision.TUNNEL

        val domains = DnsResolutionCache.domainsFor(ip)
        if (domains.isEmpty()) return RouteDecision.TUNNEL

        val matches = domains.map { matchesAnyRule(it, rules) }
        val anyMatch = matches.any { it }
        val anyNonMatch = matches.any { !it }

        // Unanimous verdict across every known domain for this IP.
        val bypassSelected = prefs.mode == DomainRulesPreferences.MODE_BYPASS
        return when {
            anyMatch && anyNonMatch -> RouteDecision.TUNNEL // ambiguous: safe default
            anyMatch -> if (bypassSelected) RouteDecision.BYPASS else RouteDecision.TUNNEL
            else -> if (bypassSelected) RouteDecision.TUNNEL else RouteDecision.BYPASS
        }
    }

    /** True if [domain] equals a rule or is a subdomain of one ("api.vk.com" matches rule "vk.com"). */
    private fun matchesAnyRule(domain: String, rules: Set<String>): Boolean {
        if (domain in rules) return true
        return rules.any { rule -> rule.isNotBlank() && domain.endsWith(".$rule") }
    }
}
