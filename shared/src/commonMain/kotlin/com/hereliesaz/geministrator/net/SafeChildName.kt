package com.hereliesaz.geministrator.net

/**
 * True when [name] can be joined to a folder as one child: not empty, not `.` or `..`, and holding no separator
 * (either kind), NUL or other control character. A name that came from a provider (a picked folder's display names,
 * an archive entry) is a claim, not a path, and must pass this before it is used as one.
 */
fun isSafeChildName(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }
