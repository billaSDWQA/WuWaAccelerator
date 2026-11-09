package com.example.wuwaaccelerator

/** Values that may still be owned by this app during a two-step sysfs update. */
internal data class GpuRestoreRecord(
    val original: String,
    val lastApplied: String,
    val previousApplied: String
)

internal object GpuRestorePolicy {
    /**
     * Never overwrite an unrecognized current value. Original is accepted so a
     * crash before the requested write can safely clear the unused backup.
     */
    fun mayRestore(current: String?, record: GpuRestoreRecord): Boolean =
        current != null && (
            current == record.original ||
                current == record.lastApplied ||
                current == record.previousApplied
            )

    fun needsWrite(current: String, record: GpuRestoreRecord): Boolean =
        current != record.original
}
