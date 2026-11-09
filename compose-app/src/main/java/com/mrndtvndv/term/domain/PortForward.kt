package com.mrndtvndv.term.domain

/** Which side listens: [LOCAL] the phone (ssh -L), [REMOTE] the server (ssh -R). */
enum class ForwardDirection(val letter: Char) { LOCAL('L'), REMOTE('R') }

/**
 * A port forward on one SSH connection. [bindHost]:[port] listens on the listening side and each connection is joined to
 * [host]:[hostPort] as the other side sees it: for [ForwardDirection.LOCAL] the phone listens and the server connects,
 * for [ForwardDirection.REMOTE] the server listens and the phone connects.
 */
data class PortForward(
    val direction: ForwardDirection,
    val bindHost: String,
    val port: Int,
    val host: String,
    val hostPort: Int,
) {
    /** "L3000", "R5555": what the key bar shows. */
    val label: String get() = "${direction.letter}$port"

    /** The ssh syntax, in full: "-L 127.0.0.1:3000:localhost:3000". */
    val spec: String get() = "-${direction.letter} $bindHost:$port:$host:$hostPort"
}

/** What a line of ssh-style arguments asks: to open a forward, or to cancel the one on [port]. */
sealed interface ForwardRequest {
    data class Open(val forward: PortForward) : ForwardRequest
    data class Cancel(val direction: ForwardDirection, val port: Int) : ForwardRequest
}

/**
 * Parses `-L [bind:]port[:host[:hostport]]`, `-R …` (the same), `-KL port` and `-KR port`, with or without a space after
 * the flag. A missing host is localhost and a missing host port is the same port, so `-L 5555` is
 * `-L 5555:localhost:5555`. Two parts are port:host when the first is a number, else bind:port. The listening side
 * binds to the loopback unless a bind address is given. Throws [IllegalArgumentException] with what is wrong.
 */
fun parseForwardRequest(arguments: String): ForwardRequest {
    val words = arguments.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    require(words.isNotEmpty()) { "Nothing to forward: use -L port or -R port" }
    val flag = words[0]
    val (cancel, letter, inline) = when {
        flag.startsWith("-K") && flag.length >= 3 -> Triple(true, flag[2], flag.substring(3))
        flag.startsWith("-") && flag.length >= 2 -> Triple(false, flag[1], flag.substring(2))
        else -> throw IllegalArgumentException("Unknown option $flag: use -L, -R, -KL or -KR")
    }
    val direction = ForwardDirection.entries.firstOrNull { it.letter == letter.uppercaseChar() && letter.isUpperCase() }
        ?: throw IllegalArgumentException("Unknown option $flag: use -L, -R, -KL or -KR")
    val rest = (listOf(inline) + words.drop(1)).filter { it.isNotEmpty() }
    require(rest.size == 1) { "Expected one forward after $flag, got ${rest.size}" }
    val parts = rest[0].split(':')
    if (cancel) {
        // ssh accepts bind:port here too; the port alone identifies a forward on this connection.
        return ForwardRequest.Cancel(direction, port(parts.last()))
    }
    val bindDefault = if (direction == ForwardDirection.LOCAL) "127.0.0.1" else "localhost"
    val (bind, portText, host, hostPortText) = when (parts.size) {
        1 -> listOf(bindDefault, parts[0], "localhost", parts[0])
        2 -> if (parts[0].all(Char::isDigit)) listOf(bindDefault, parts[0], parts[1], parts[0])
            else listOf(parts[0], parts[1], "localhost", parts[1])
        3 -> listOf(bindDefault, parts[0], parts[1], parts[2])
        4 -> parts
        else -> throw IllegalArgumentException("Too many parts in ${rest[0]}: use [bind:]port[:host[:hostport]]")
    }
    require(bind.isNotEmpty() && host.isNotEmpty()) { "Empty address in ${rest[0]}" }
    return ForwardRequest.Open(PortForward(direction, bind, port(portText), host, port(hostPortText)))
}

private fun port(text: String): Int {
    val value = text.toIntOrNull() ?: throw IllegalArgumentException("Not a port: \"$text\"")
    require(value in 1..65535) { "Port out of range: $value" }
    return value
}
