package com.mrndtvndv.term.service

/**
 * The packages of the Keyarchy keyboard (release and debug), the only app that may ask Termarchy for port forwards or
 * read the state of its agents.
 */
internal val KEYARCHY_PACKAGES = setOf("org.ferrao.homekey", "org.ferrao.homekey.debug")

/**
 * Whether [packages], those of the UID Android stamped on a request, are Keyarchy's. A UID shared with any other app is
 * refused, and so is an unknown one.
 */
internal fun isAllowedCaller(packages: Array<String>?): Boolean =
    !packages.isNullOrEmpty() && packages.all { it in KEYARCHY_PACKAGES }
