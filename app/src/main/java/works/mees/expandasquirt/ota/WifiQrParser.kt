package works.mees.expandasquirt.ota

data class ParsedWifiQr(
    val ssid: String,
    val password: String?,
    val security: String?,
    val hidden: Boolean,
)

sealed class WifiQrResult {
    data class Ok(val creds: ParsedWifiQr) : WifiQrResult()
    data class Err(val reason: String) : WifiQrResult()
}

fun parseWifiQr(payload: String): WifiQrResult {
    if (!payload.startsWith("WIFI:", ignoreCase = true)) {
        return WifiQrResult.Err("not a wifi qr")
    }

    val fields = parseFields(payload.drop(5))
    val ssid = fields["S"]
    if (ssid.isNullOrEmpty()) {
        return WifiQrResult.Err("missing ssid")
    }

    val security = fields["T"]
    val password = if (security == "nopass") null else fields["P"]
    val hidden = fields["H"].equals("true", ignoreCase = true)

    return WifiQrResult.Ok(
        ParsedWifiQr(
            ssid = ssid,
            password = password,
            security = security,
            hidden = hidden,
        ),
    )
}

private fun parseFields(body: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (field in splitUnescaped(body, ';')) {
        if (field.isEmpty()) continue
        val keyEnd = indexOfUnescaped(field, ':')
        if (keyEnd <= 0) continue

        val key = field.substring(0, keyEnd)
        if (key !in setOf("S", "T", "P", "H")) continue

        result[key] = decodeEscapes(field.substring(keyEnd + 1))
    }
    return result
}

private fun splitUnescaped(value: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false

    for (ch in value) {
        when {
            escaped -> {
                current.append('\\')
                current.append(ch)
                escaped = false
            }
            ch == '\\' -> escaped = true
            ch == delimiter -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
    }

    if (escaped) current.append('\\')
    parts += current.toString()
    return parts
}

private fun indexOfUnescaped(value: String, target: Char): Int {
    var escaped = false
    value.forEachIndexed { index, ch ->
        when {
            escaped -> escaped = false
            ch == '\\' -> escaped = true
            ch == target -> return index
        }
    }
    return -1
}

private fun decodeEscapes(value: String): String {
    val decoded = StringBuilder()
    var escaped = false

    for (ch in value) {
        if (escaped) {
            decoded.append(if (ch in setOf(';', ':', ',', '"', '\\')) ch else ch)
            escaped = false
        } else if (ch == '\\') {
            escaped = true
        } else {
            decoded.append(ch)
        }
    }

    if (escaped) decoded.append('\\')
    return decoded.toString()
}
