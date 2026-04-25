package com.couchfi.player

/**
 * Decode literal XML/HTML entity escapes (`&amp;`, `&apos;`, `&quot;`,
 * `&lt;`, `&gt;`, `&#NN;`, `&#xNN;`) into their actual characters.
 *
 * Some music taggers — and a fair number of Icecast/Icy stations — emit
 * tag strings that have been round-tripped through XML and never decoded
 * on the way out, so titles arrive looking like `Don&apos;t Stop` or
 * `R&amp;B`. We decode at the boundary (scanner / stream source) so the
 * rest of the app sees clean text.
 *
 * Unknown entities (e.g. `&foo;`) and bare `&` characters pass through
 * unchanged; out-of-range numeric escapes are also left alone.
 */
internal fun unescapeXmlEntities(s: String): String {
    if (s.indexOf('&') < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '&') { sb.append(c); i++; continue }
        val semi = s.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue }
        val ent = s.substring(i + 1, semi)
        val replacement: String? = when {
            ent == "amp"  -> "&"
            ent == "lt"   -> "<"
            ent == "gt"   -> ">"
            ent == "quot" -> "\""
            ent == "apos" -> "'"
            ent.startsWith("#x") || ent.startsWith("#X") ->
                ent.drop(2).toIntOrNull(16)
                    ?.takeIf { it in 0..0x10FFFF }
                    ?.let { String(Character.toChars(it)) }
            ent.startsWith("#") ->
                ent.drop(1).toIntOrNull()
                    ?.takeIf { it in 0..0x10FFFF }
                    ?.let { String(Character.toChars(it)) }
            else -> null
        }
        if (replacement != null) {
            sb.append(replacement)
            i = semi + 1
        } else {
            sb.append(c); i++
        }
    }
    return sb.toString()
}
