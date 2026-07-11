package site.anzz.childkiosk.performance

import com.google.common.net.InetAddresses
import com.google.common.net.InternetDomainName
import java.net.IDN
import java.net.Inet6Address
import java.net.URI
import java.util.Locale

internal data class HighPerformanceOrigin internal constructor(
    val scheme: String,
    val asciiHost: String,
    /** Non-default explicit port. Default HTTP/HTTPS ports are normalized to null. */
    val port: Int?,
    val isIpAddress: Boolean
) {
    val effectivePort: Int
        get() = port ?: if (scheme == HTTPS_SCHEME) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT

    val isLocalhost: Boolean
        get() = asciiHost == LOCALHOST || asciiHost.endsWith(".$LOCALHOST")

    val unicodeHost: String
        get() = if (isIpAddress) asciiHost else runCatching { IDN.toUnicode(asciiHost) }.getOrDefault(asciiHost)

    val value: String
        get() {
            val renderedHost = if (asciiHost.contains(':')) "[$asciiHost]" else asciiHost
            return buildString {
                append(scheme)
                append("://")
                append(renderedHost)
                port?.let {
                    append(':')
                    append(it)
                }
            }
        }

    fun canIncludeSubdomains(): Boolean {
        if (isIpAddress || isLocalhost || !asciiHost.contains('.')) return false
        val domain = runCatching { InternetDomainName.from(asciiHost) }.getOrNull() ?: return false
        return !domain.isPublicSuffix
    }

    override fun toString(): String = value
}

internal object HighPerformanceOriginParser {
    /**
     * Parses a manually entered rule. Only a pure Origin is accepted; a single trailing slash is
     * tolerated because browsers commonly display origins that way.
     */
    fun parseRuleOrigin(raw: String): HighPerformanceOrigin {
        return parse(raw, allowUrlPath = false)
    }

    /** Extracts an Origin from a complete Web App or committed top-level page URL. */
    fun extractFromUrl(raw: String): HighPerformanceOrigin {
        return parse(raw, allowUrlPath = true)
    }

    private fun parse(raw: String, allowUrlPath: Boolean): HighPerformanceOrigin {
        require(raw.none(Char::isISOControl)) { "Origin contains control characters" }
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= MAX_INPUT_LENGTH) { "Origin is empty or too long" }
        require(value.none { it.isISOControl() || it.isWhitespace() }) { "Origin contains whitespace or controls" }
        require('\\' !in value) { "Backslashes are not allowed in an Origin" }

        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Invalid Origin", it) }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Origin scheme is required")
        require(scheme == HTTP_SCHEME || scheme == HTTPS_SCHEME) { "Only HTTP and HTTPS Origins are allowed" }
        require(uri.isAbsolute && !uri.isOpaque) { "Origin must be an absolute hierarchical URI" }
        if (!allowUrlPath) {
            require(uri.rawQuery == null) { "Origin query parameters are not allowed" }
            require(uri.rawFragment == null) { "Origin fragments are not allowed" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Origin paths are not allowed" }
        }

        val authority = uri.rawAuthority ?: throw IllegalArgumentException("Origin host is required")
        val parsedAuthority = parseAuthority(authority)
        val normalizedHost = normalizeHost(parsedAuthority.host)
        val normalizedPort = parsedAuthority.port?.let { explicitPort ->
            when {
                scheme == HTTP_SCHEME && explicitPort == HTTP_DEFAULT_PORT -> null
                scheme == HTTPS_SCHEME && explicitPort == HTTPS_DEFAULT_PORT -> null
                else -> explicitPort
            }
        }

        return HighPerformanceOrigin(
            scheme = scheme,
            asciiHost = normalizedHost.value,
            port = normalizedPort,
            isIpAddress = normalizedHost.isIpAddress
        )
    }

    private fun parseAuthority(authority: String): ParsedAuthority {
        require(authority.isNotBlank() && authority.length <= MAX_AUTHORITY_LENGTH) { "Origin host is required" }
        require('@' !in authority) { "User information is not allowed in an Origin" }
        require('%' !in authority) { "Escaped or zone-qualified hosts are not allowed" }

        if (authority.startsWith('[')) {
            val closeBracket = authority.indexOf(']')
            require(closeBracket > 1) { "Invalid IPv6 host" }
            val host = authority.substring(1, closeBracket)
            val remainder = authority.substring(closeBracket + 1)
            val port = when {
                remainder.isEmpty() -> null
                remainder.startsWith(':') -> parsePort(remainder.substring(1))
                else -> throw IllegalArgumentException("Invalid IPv6 authority")
            }
            val address = runCatching { InetAddresses.forString(host) }.getOrNull()
            require(address is Inet6Address) { "Invalid IPv6 host" }
            return ParsedAuthority(InetAddresses.toAddrString(address), port)
        }

        require('[' !in authority && ']' !in authority) { "Invalid host brackets" }
        val firstColon = authority.indexOf(':')
        val lastColon = authority.lastIndexOf(':')
        require(firstColon == lastColon) { "IPv6 hosts must use brackets" }
        return if (lastColon >= 0) {
            ParsedAuthority(
                host = authority.substring(0, lastColon),
                port = parsePort(authority.substring(lastColon + 1))
            )
        } else {
            ParsedAuthority(authority, null)
        }
    }

    private fun parsePort(rawPort: String): Int {
        require(rawPort.isNotEmpty() && rawPort.all(Char::isDigit)) { "Invalid Origin port" }
        val port = rawPort.toIntOrNull() ?: throw IllegalArgumentException("Invalid Origin port")
        require(port in MIN_PORT..MAX_PORT) { "Invalid Origin port" }
        return port
    }

    private fun normalizeHost(rawHost: String): NormalizedHost {
        val host = rawHost.trimEnd('.')
        require(host.isNotBlank() && host.length <= MAX_HOST_LENGTH) { "Invalid Origin host" }

        val address = runCatching { InetAddresses.forString(host) }.getOrNull()
        if (address != null) {
            return NormalizedHost(InetAddresses.toAddrString(address).lowercase(Locale.ROOT), true)
        }
        require(host.any { !it.isDigit() && it != '.' }) { "Invalid IPv4 host" }

        val ascii = runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        }.getOrElse { throw IllegalArgumentException("Invalid internationalized host", it) }
            .lowercase(Locale.ROOT)
        require(ascii.isNotBlank() && ascii.length <= MAX_HOST_LENGTH) { "Invalid Origin host" }
        require(ascii.split('.').all { it.isNotEmpty() && it.length <= MAX_DNS_LABEL_LENGTH }) {
            "Invalid Origin host labels"
        }
        // Guava applies additional DNS syntax validation without performing network lookups.
        require(runCatching { InternetDomainName.from(ascii) }.isSuccess) { "Invalid Origin host" }
        return NormalizedHost(ascii, false)
    }

    private data class ParsedAuthority(val host: String, val port: Int?)
    private data class NormalizedHost(val value: String, val isIpAddress: Boolean)

    private const val MAX_INPUT_LENGTH = 2_048
    private const val MAX_AUTHORITY_LENGTH = 512
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_DNS_LABEL_LENGTH = 63
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
}

internal object HighPerformanceOriginMatcher {
    fun match(
        urlOrOrigin: String,
        rules: List<HighPerformanceRuntimeRule>
    ): HighPerformanceRuntimeRule? {
        val candidate = runCatching { HighPerformanceOriginParser.extractFromUrl(urlOrOrigin) }.getOrNull()
            ?: return null
        val parsedRules = rules.mapNotNull { rule ->
            if (!rule.enabled) return@mapNotNull null
            val configured = runCatching { HighPerformanceOriginParser.parseRuleOrigin(rule.origin) }.getOrNull()
                ?: return@mapNotNull null
            rule to configured
        }
        return parsedRules.firstOrNull { (_, configured) -> candidate.value == configured.value }?.first
            ?: parsedRules.firstOrNull { (rule, configured) ->
                matchesSubdomain(candidate, configured, rule.includeSubdomains)
            }?.first
    }

    fun matches(candidate: HighPerformanceOrigin, rule: HighPerformanceRuntimeRule): Boolean {
        if (!rule.enabled) return false
        val configured = runCatching { HighPerformanceOriginParser.parseRuleOrigin(rule.origin) }.getOrNull()
            ?: return false
        if (candidate.value == configured.value) return true
        return matchesSubdomain(candidate, configured, rule.includeSubdomains)
    }

    private fun matchesSubdomain(
        candidate: HighPerformanceOrigin,
        configured: HighPerformanceOrigin,
        includeSubdomains: Boolean
    ): Boolean {
        if (!includeSubdomains || !configured.canIncludeSubdomains()) return false
        if (candidate.scheme != configured.scheme || candidate.effectivePort != configured.effectivePort) return false
        if (candidate.isIpAddress || candidate.asciiHost == configured.asciiHost) return false
        return candidate.asciiHost.endsWith(".${configured.asciiHost}")
    }
}

private const val HTTP_SCHEME = "http"
private const val HTTPS_SCHEME = "https"
private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val LOCALHOST = "localhost"
