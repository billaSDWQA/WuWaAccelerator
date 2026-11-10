package com.example.wuwaaccelerator

/**
 * Conservative preflight for the single SGPU min_freq write path.
 *
 * This is only an early rejection check. A successful result never replaces
 * the actual write plus read-back verification in GpuUserService.
 */
internal object GpuWriteAccessPolicy {
    fun canAttemptWrite(
        uid: Int,
        shellUid: Int,
        rootUid: Int,
        nodeWritableByCurrentProcess: Boolean
    ): Boolean = when (uid) {
        rootUid -> true
        shellUid -> nodeWritableByCurrentProcess
        else -> false
    }
}
