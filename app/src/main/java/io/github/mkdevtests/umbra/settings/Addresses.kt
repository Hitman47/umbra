package io.github.mkdevtests.umbra.settings

/** A service's address taken apart, to type as little as possible: http(s), the host, the port, a path. */
data class Address(val secure: Boolean = false, val host: String = "", val port: String = "", val path: String = "") {
    /** Back to a URL; an empty port takes [defaultPort]. "" without a host. */
    fun url(defaultPort: Int?): String {
        val name = host.trim()
        if (name.isEmpty()) return ""
        val number = port.trim().ifEmpty { defaultPort?.toString().orEmpty() }
        return (if (secure) "https://" else "http://") + name + (if (number.isEmpty()) "" else ":$number") + path
    }
}

/** "https://192.168.1.10:9696/prowlarr" → its parts; a bare "nas:8080" is http. */
fun parseAddress(url: String): Address {
    val text = url.trim()
    val secure = text.startsWith("https://", ignoreCase = true)
    val rest = text.substringAfter("://")
    val authority = rest.substringBefore('/')
    val path = rest.substring(authority.length).trimEnd('/')
    return Address(secure, authority.substringBefore(':'), authority.substringAfter(':', "").filter(Char::isDigit), path)
}

/** The host of an address ("smb" host, URL, "host:port"): what another service on the same machine would use. */
fun hostOf(address: String): String = address.trim().substringAfter("://").substringBefore('/').substringBefore(':')
