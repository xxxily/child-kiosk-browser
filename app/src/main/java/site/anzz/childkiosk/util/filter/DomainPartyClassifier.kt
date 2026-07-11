package site.anzz.childkiosk.util.filter

import com.google.common.net.InetAddresses
import com.google.common.net.InternetDomainName
import java.net.IDN
import java.util.Locale

internal object DomainPartyClassifier {
    fun normalize(raw: String): String? {
        val candidate = extractHostCandidate(raw) ?: return null
        if (candidate.isBlank() || candidate.length > MAX_RAW_HOST_LENGTH) return null
        if (candidate.any { it.isISOControl() || it.isWhitespace() }) return null
        if ('%' in candidate) return null

        val unwrapped = candidate
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
        if (unwrapped.isBlank()) return null

        runCatching { InetAddresses.forString(unwrapped) }.getOrNull()?.let { address ->
            return InetAddresses.toAddrString(address).lowercase(Locale.ROOT)
        }

        val ascii = runCatching { IDN.toASCII(unwrapped, IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?: return null
        if (ascii.isBlank() || ascii.length > MAX_DNS_HOST_LENGTH) return null
        val labels = ascii.split('.')
        if (labels.any { it.isBlank() || it.length > MAX_DNS_LABEL_LENGTH }) return null
        if (runCatching { InternetDomainName.from(ascii) }.isFailure && ascii != LOCALHOST) return null
        return ascii
    }

    fun isIpLiteral(host: String): Boolean {
        return runCatching { InetAddresses.forString(host.removePrefix("[").removeSuffix("]")) }.isSuccess
    }

    fun isThirdParty(requestHost: String, topLevelHost: String): Boolean {
        val request = normalize(requestHost) ?: return false
        val top = normalize(topLevelHost) ?: return false
        if (request == top) return false
        if (isIpLiteral(request) || isIpLiteral(top) || isLocalhost(request) || isLocalhost(top)) {
            return true
        }
        return registrableDomain(request) != registrableDomain(top)
    }

    fun registrableDomain(host: String): String {
        val normalized = normalize(host) ?: return ""
        if (isIpLiteral(normalized) || isLocalhost(normalized)) return normalized
        val domain = runCatching { InternetDomainName.from(normalized) }.getOrNull() ?: return normalized
        return runCatching {
            if (domain.isUnderPublicSuffix) domain.topPrivateDomain().toString() else normalized
        }.getOrDefault(normalized)
    }

    private fun extractHostCandidate(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) {
            val authorityStart = schemeIndex + 3
            val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
                .let { if (it < 0) value.length else it }
            value = value.substring(authorityStart, authorityEnd)
        } else {
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        }
        value = value.substringAfterLast('@')

        if (value.startsWith('[')) {
            val endBracket = value.indexOf(']')
            if (endBracket <= 1) return null
            val remainder = value.substring(endBracket + 1)
            if (remainder.isNotEmpty() && !remainder.matches(Regex(":\\d+"))) return null
            return value.substring(1, endBracket)
        }

        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val possiblePort = value.substringAfterLast(':')
            if (possiblePort.isNotBlank() && possiblePort.all(Char::isDigit)) {
                value = value.substringBeforeLast(':')
            }
        }
        return value.trim('.')
    }

    private fun isLocalhost(host: String): Boolean {
        return host == LOCALHOST || host.endsWith(".$LOCALHOST")
    }

    private const val LOCALHOST = "localhost"
    private const val MAX_RAW_HOST_LENGTH = 512
    private const val MAX_DNS_HOST_LENGTH = 253
    private const val MAX_DNS_LABEL_LENGTH = 63
}

internal fun normalizeFilterHost(raw: String): String? = DomainPartyClassifier.normalize(raw)

internal fun isIpLiteral(host: String): Boolean = DomainPartyClassifier.isIpLiteral(host)

internal fun isHostsSinkAddress(raw: String): Boolean {
    val normalized = DomainPartyClassifier.normalize(raw) ?: return false
    return normalized == "0.0.0.0" ||
        normalized == "127.0.0.1" ||
        normalized == "::" ||
        normalized == "::1"
}

internal fun domainAnchorHost(pattern: String): String? {
    val hostPart = pattern.takeWhile { it != '/' && it != '^' && it != '*' && it != '?' && it != '#' }
    if (hostPart.isBlank()) return null
    return normalizeFilterHost(hostPart)
}
