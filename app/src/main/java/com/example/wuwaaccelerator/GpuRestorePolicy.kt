package com.example.wuwaaccelerator

/** Values that may still be owned by this app during a two-step sysfs update. */
internal data class GpuRestoreRecord(
    val original: String,
    val lastApplied: String,
    val previousApplied: String
)

internal object GpuRestorePolicy {
    /** Refuse to overwrite a value that is not one of our recorded transition states. */
    fun mayRestore(current: String?, record: GpuRestoreRecord): Boolean =
        current != null && (
            current == record.original ||
                current == record.lastApplied ||
                current == record.previousApplied
            )

    fun needsWrite(current: String, record: GpuRestoreRecord): Boolean =
        current != record.original
}
