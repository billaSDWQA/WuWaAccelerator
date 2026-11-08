package com.example.wuwaaccelerator

/**
 * Prefer topResumedActivity, but fall back to mResumedActivity when the
 * preferred record is "none" or does not contain a parseable component.
 */
internal object ForegroundPackageParser {

    private val packagePattern =
        Regex("""(?:\bu\d+\s+)?([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/""")

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
