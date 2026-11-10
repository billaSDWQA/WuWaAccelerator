package com.example.wuwaaccelerator

/**
 * Reacts quickly to load increases but requires sustained low load before
 * lowering the GPU floor. Thermal and memory guardrails can lower immediately.
 */
internal class GpuAdaptiveHysteresis(
    private val increaseSamples: Int = 1,
    private val decreaseSamples: Int = 4
) {
    private var current: String? = null
    private var pending: String? = null
    private var pendingSamples = 0

    fun update(proposed: String?, forceImmediate: Boolean = false): String? {
        if (proposed == null) {
            reset()
            return null
        }
        if (forceImmediate) {
            current = proposed
            clearPending()
            return current
        }
        if (proposed == current) {
            clearPending()
            return current
        }

        if (pending != proposed) {
            pending = proposed
            pendingSamples = 1
        } else {
            pendingSamples++
        }

        val currentValue = current?.toLongOrNull()
        val proposedValue = proposed.toLongOrNull() ?: return current
        val requiredSamples = if (currentValue == null || proposedValue > currentValue) {
            increaseSamples
        } else {
            decreaseSamples
        }

        if (pendingSamples >= requiredSamples) {
            current = proposed
            clearPending()
        }
        return current
    }

    fun reset() {
        current = null
        clearPending()
    }

    private fun clearPending() {
        pending = null
        pendingSamples = 0
    }
}
