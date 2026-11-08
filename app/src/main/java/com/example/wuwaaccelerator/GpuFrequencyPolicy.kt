package com.example.wuwaaccelerator

/**
 * Whitelist and decision logic for the single SGPU interface/min_freq node.
 * Frequencies are expressed in kHz, as reported by this device's devfreq node.
 */
internal object GpuFrequencyPolicy {

    const val BOOST_MIN_FREQ = "750000"

    // Includes the values accepted by the first published debug build so that
    // an existing backup from that build can still be validated and restored.
    private val validFrequencies = setOf(
        "0", "450000", "544000", "648000", "750000",
        "864000", "960000", "1025000", "1152000", "1300000"
    )

    fun isValid(value: String): Boolean = value.trim() in validFrequencies

    /**
     * Returns true when the known original value is below the test floor,
     * false when it is already at/above the floor, and null for unknown data.
     */
    fun shouldRaise(original: String?): Boolean? {
        val normalized = original?.trim() ?: return null
        if (!isValid(normalized)) return null
        val frequency = normalized.toLongOrNull() ?: return null
        return frequency < BOOST_MIN_FREQ.toLong()
    }
}
