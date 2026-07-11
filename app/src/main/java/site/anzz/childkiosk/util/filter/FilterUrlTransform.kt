package site.anzz.childkiosk.util.filter

import java.util.Locale

/** Parsed raw-query view that never decodes or reconstructs untouched URL bytes. */
internal class FilterUrlTransform private constructor(
    private val url: String,
    private val queryStart: Int,
    private val fragmentStart: Int,
    private val segments: List<String>
) {
    fun hasSensitiveAuthenticationParameter(): Boolean {
        return segments.any { segment -> normalizedParamName(segment) in AUTHENTICATION_QUERY_PARAMS }
    }

    fun removeParams(includedParams: Set<String>, exceptedParams: Set<String>): String? {
        var removed = false
        val retainedSegments = segments.filter { segment ->
            val name = normalizedParamName(segment)
            val included = includedParams.any { matchesParam(name, it) }
            val excepted = exceptedParams.any { matchesParam(name, it) }
            val remove = name.isNotBlank() && included && !excepted
            if (remove) removed = true
            !remove
        }
        if (!removed) return null

        val result = StringBuilder(url.length)
        result.append(url, 0, queryStart)
        if (retainedSegments.isNotEmpty()) {
            result.append('?')
            result.append(retainedSegments.joinToString("&"))
        }
        if (fragmentStart < url.length) {
            result.append(url, fragmentStart, url.length)
        }
        return result.toString()
    }

    companion object {
        fun parse(url: String): FilterUrlTransform? {
            val fragmentStart = url.indexOf('#').let { if (it < 0) url.length else it }
            val queryStart = url.indexOf('?')
            if (queryStart < 0 || queryStart > fragmentStart) return null
            val rawQuery = url.substring(queryStart + 1, fragmentStart)
            val segments = buildList {
                var start = 0
                for (index in 0..rawQuery.length) {
                    if (index == rawQuery.length || rawQuery[index] == '&') {
                        add(rawQuery.substring(start, index))
                        start = index + 1
                    }
                }
            }
            return FilterUrlTransform(url, queryStart, fragmentStart, segments)
        }

        private fun matchesParam(name: String, ruleParam: String): Boolean {
            val normalizedRule = ruleParam.lowercase(Locale.US)
            return if (normalizedRule.endsWith('*')) {
                name.startsWith(normalizedRule.dropLast(1))
            } else {
                name == normalizedRule
            }
        }

        private fun normalizedParamName(segment: String): String {
            val rawName = segment.substringBefore('=')
            if ('%' !in rawName) return rawName.lowercase(Locale.US)
            val decoded = StringBuilder(rawName.length)
            var index = 0
            while (index < rawName.length) {
                val char = rawName[index]
                if (char == '%' && index + 2 < rawName.length) {
                    val value = rawName.substring(index + 1, index + 3).toIntOrNull(16)
                    if (value != null && value in 0x20..0x7E) {
                        decoded.append(value.toChar())
                        index += 3
                        continue
                    }
                }
                decoded.append(char)
                index++
            }
            return decoded.toString().lowercase(Locale.US)
        }

        private val AUTHENTICATION_QUERY_PARAMS = setOf(
            "signature",
            "sig",
            "x-amz-signature",
            "x-goog-signature",
            "oauth_signature",
            "token",
            "access_token",
            "id_token",
            "auth_token",
            "oauth_token",
            "session_token",
            "authorization",
            "code",
            "code_verifier",
            "state",
            "assertion",
            "samlrequest",
            "samlresponse",
            "client_secret",
            "api_key",
            "credential"
        )
    }
}
