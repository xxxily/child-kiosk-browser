package site.anzz.childkiosk.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HighPerformanceOriginTest {
    @Test
    fun normalizesCaseDefaultPortsDnsDotsAndIdn() {
        assertEquals("https://example.com", parse("HTTPS://ExAmPle.COM:443/").value)
        assertEquals("http://example.com", parse("http://example.com.:80").value)
        assertEquals("https://example.com:8443", parse("https://example.com:8443").value)
        val idn = parse("https://例子.测试")
        assertEquals("https://xn--fsqu00a.xn--0zwm56d", idn.value)
        assertEquals("例子.测试", idn.unicodeHost)
    }

    @Test
    fun supportsIpv4AndBracketedIpv6() {
        val ipv4 = parse("http://192.168.1.10:8080")
        assertEquals("http://192.168.1.10:8080", ipv4.value)
        assertTrue(ipv4.isIpAddress)
        assertFalse(ipv4.canIncludeSubdomains())

        val ipv6 = parse("https://[2001:0db8:0:0:0:0:0:1]:443")
        assertEquals("https://[2001:db8::1]", ipv6.value)
        assertTrue(ipv6.isIpAddress)
        assertFalse(ipv6.canIncludeSubdomains())
    }

    @Test
    fun manualRulesRejectPathsQueriesFragmentsUserInfoAndUnsafeSyntax() {
        listOf(
            "https://example.com/path",
            "https://example.com?x=1",
            "https://example.com/#fragment",
            "https://user@example.com",
            "https://example.com\\evil",
            "https://example.com:0",
            "https://example.com:65536",
            "https://example.com:not-a-port",
            "javascript://example.com",
            "file://example.com",
            "data:text/plain,hello",
            "example.com",
            "https://",
            "https://exa mple.com",
            "https://example.com\n"
        ).forEach(::assertRuleRejected)
    }

    @Test
    fun fullPageUrlsExtractOnlyTheOrigin() {
        assertEquals(
            "https://example.com:8443",
            HighPerformanceOriginParser.extractFromUrl("https://EXAMPLE.com:8443/path?q=1#fragment").value
        )
        assertRuleRejected("https://example.com/path")
    }

    @Test
    fun exactAndSubdomainMatchingRequiresSchemePortAndLabelBoundary() {
        val exactRule = rule("https://example.com")
        assertTrue(matches("https://example.com/page", exactRule))
        assertFalse(matches("https://child.example.com/page", exactRule))

        val subdomainRule = exactRule.copy(includeSubdomains = true)
        assertTrue(matches("https://child.example.com/page", subdomainRule))
        assertTrue(matches("https://deep.child.example.com/page", subdomainRule))
        assertFalse(matches("http://child.example.com/page", subdomainRule))
        assertFalse(matches("https://child.example.com:8443/page", subdomainRule))
        assertFalse(matches("https://badexample.com/page", subdomainRule))
        assertFalse(matches("https://example.com.evil.test/page", subdomainRule))
    }

    @Test
    fun matchingFailsClosedForDisabledInvalidOrNonWebCandidates() {
        val enabled = rule("https://example.com")
        assertNull(HighPerformanceOriginMatcher.match("about:blank", listOf(enabled)))
        assertNull(HighPerformanceOriginMatcher.match("data:text/plain,hello", listOf(enabled)))
        assertNull(HighPerformanceOriginMatcher.match("https://example.com", listOf(enabled.copy(enabled = false))))
        assertNull(
            HighPerformanceOriginMatcher.match(
                "https://example.com",
                listOf(enabled.copy(origin = "not-an-origin"))
            )
        )
    }

    @Test
    fun exactRuleWinsOverBroaderSubdomainRule() {
        val parent = rule("https://example.com").copy(id = "parent", includeSubdomains = true)
        val exact = rule("https://www.example.com").copy(id = "exact")

        assertEquals(
            "exact",
            HighPerformanceOriginMatcher.match(
                "https://www.example.com/page",
                listOf(parent, exact)
            )?.id
        )
    }

    @Test
    fun publicSuffixIpAndLocalhostCannotGrantSubdomains() {
        assertFalse(parse("https://com").canIncludeSubdomains())
        assertFalse(parse("https://co.uk").canIncludeSubdomains())
        assertFalse(parse("https://github.io").canIncludeSubdomains())
        assertFalse(parse("http://localhost:8080").canIncludeSubdomains())
        assertFalse(parse("http://dev.localhost:8080").canIncludeSubdomains())
        assertFalse(parse("https://127.0.0.1").canIncludeSubdomains())
        assertTrue(parse("https://example.co.uk").canIncludeSubdomains())
    }

    private fun parse(value: String): HighPerformanceOrigin =
        HighPerformanceOriginParser.parseRuleOrigin(value)

    private fun matches(url: String, rule: HighPerformanceRuntimeRule): Boolean {
        return HighPerformanceOriginMatcher.matches(
            HighPerformanceOriginParser.extractFromUrl(url),
            rule
        )
    }

    private fun rule(origin: String): HighPerformanceRuntimeRule {
        return HighPerformanceRuntimeRule(
            id = "test-rule",
            origin = origin,
            enabled = true,
            includeSubdomains = false,
            displayName = null,
            updatedAt = 1L
        )
    }

    private fun assertRuleRejected(value: String) {
        try {
            HighPerformanceOriginParser.parseRuleOrigin(value)
            fail("Expected rule Origin to be rejected: $value")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}
