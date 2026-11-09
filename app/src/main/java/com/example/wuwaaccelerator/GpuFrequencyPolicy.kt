package com.example.wuwaaccelerator

/**
 * Whitelist and decision logic for the single SGPU interface/min_freq node.
 * Frequencies are expressed in kHz, as reported by this device's devfreq node.
 */
internal object GpuFrequencyPolicy {

    const val BOOST_MIN_FREQ = "750000"

    // Known steps observed on the supported SM-A5560 firmware.
    private val validFrequencies = setOf(
        "0", "450000", "544000", "648000", "750000",
        "864000", "960000", "1025000", "1152000", "1300000"
    )

    fun isValid(value: String): Boolean = value.trim() in validFrequencies

    fun shouldRaise(original: String?): Boolean? {
        val normalized = original?.trim() ?: return null
        if (!isValid(normalized)) return null
        val frequency = normalized.toLongOrNull() ?: return null
        return frequency < BOOST_MIN_FREQ.toLong()
    }

    /**
     * Do not request a floor above the current hardware cap or a step the
     * driver does not advertise. Missing optional nodes are treated as unknown.
     */
    fun isHardwareTargetSupported(
        target: String,
        maximum: String?,
        availableFrequencies: Set<String>?
    ): Boolean {
        if (!isValid(target)) return false

        val targetValue = target.toLongOrNull() ?: return false
        if (maximum != null) {
            val maximumValue = maximum.trim().toLongOrNull() ?: return false
            if (targetValue > maximumValue) return false
        }

        if (availableFrequencies != null && target !in availableFrequencies) {
            return false
        }

        return true
    }
}
