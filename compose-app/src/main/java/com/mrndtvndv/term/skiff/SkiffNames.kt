package com.mrndtvndv.term.skiff

import java.text.Normalizer

/**
 * The zmx name for a session called [title]: zmx names become socket files, so accents go, and anything but
 * letters, digits, dot, dash and underscore becomes a dash. A name already in [taken] gets a number.
 */
fun skiffSessionId(title: String, taken: Set<String>): String {
    val base = Normalizer.normalize(title.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifEmpty { "session" }
    if (base !in taken) return base
    return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
}
