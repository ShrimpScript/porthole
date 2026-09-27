package dev.shrimpscript.porthole.net

/**
 * porthole://pair?host=<ip or name>[:port]&code=<six digits> - what `portholed pair`
 * puts in its QR. Scanned inside the app or opened from the phone's camera, it carries
 * everything pairing needs, so nothing is typed.
 */
data class PairLink(val host: String, val code: String) {
    companion object {
        private val digits = Regex("^[0-9]{6}$")

        fun parse(text: String): PairLink? {
            val t = text.trim()
            if (!t.startsWith("porthole://pair", ignoreCase = true)) return null
            val query = t.substringAfter('?', "")
            val params = query.split('&').mapNotNull { p ->
                val k = p.substringBefore('=', ""); val v = p.substringAfter('=', "")
                if (k.isBlank()) null else k.lowercase() to java.net.URLDecoder.decode(v, "UTF-8")
            }.toMap()
            val host = params["host"].orEmpty().trim()
            val code = params["code"].orEmpty().trim()
            if (host.isBlank() || host.length > 200 || !digits.matches(code)) return null
            if (host.any { it.isWhitespace() || it == '/' }) return null
            return PairLink(host, code)
        }
    }
}
