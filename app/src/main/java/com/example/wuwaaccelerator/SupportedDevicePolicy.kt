package com.example.wuwaaccelerator

/**
 * This build contains a fixed SGPU node and tuning value for one verified
 * device target. Fail closed on every other manufacturer/model combination.
 */
internal object SupportedDevicePolicy {

    private const val MANUFACTURER = "samsung"
    private const val MODEL = "SM-A5560"

    fun isSupported(manufacturer: String?, model: String?): Boolean {
        return manufacturer?.trim()?.equals(MANUFACTURER, ignoreCase = true) == true &&
            model?.trim()?.equals(MODEL, ignoreCase = true) == true
    }
}
