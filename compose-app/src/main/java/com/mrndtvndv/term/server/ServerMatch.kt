package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import java.text.Normalizer

/**
 * The saved SSH server [query] names ("olivais", "corsair"): by its name or its host, ignoring case and accents. A name
 * or host equal to the query wins; else the only server whose name or host starts with it. Null when none or several.
 */
fun matchServer(query: String, servers: List<ServerConfig>): ServerConfig? {
    val wanted = plain(query)
    if (wanted.isEmpty()) return null
    val remote = servers.filter { !it.isLocal }
    fun names(server: ServerConfig) = listOf(plain(server.label), plain(server.host))
    remote.filter { server -> names(server).any { it == wanted } }.singleOrNull()?.let { return it }
    return remote.filter { server -> names(server).any { it.startsWith(wanted) } }.singleOrNull()
}

private fun plain(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
