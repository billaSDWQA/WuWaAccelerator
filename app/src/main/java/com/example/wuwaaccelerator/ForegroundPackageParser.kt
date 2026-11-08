package com.example.wuwaaccelerator

/**
 * Extracts the package name from the ActivityManager lines printed by
 * `dumpsys activity activities`.
 *
 * Android releases differ in whether the line includes a user token (for
 * example, "u0 ") before the component. Accept both formats, but only inspect
 * the two known resumed-activity fields.
 */
internal object ForegroundPackageParser {

    private val packagePattern =
        Regex("""(?:\\bu\\d+\\s+)?([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/""")

    fun parse(lines: List<String>): String? {
        val candidates = lines
            .filter {
                it.contains("topResumedActivity") || it.contains("mResumedActivity")
            }
            .sortedBy {
                if (it.contains("topResumedActivity")) 0 else 1
            }

        for (line in candidates) {
            val match = packagePattern.find(line) ?: continue
            return match.groupValues[1]
        }
        return null
    }
}
