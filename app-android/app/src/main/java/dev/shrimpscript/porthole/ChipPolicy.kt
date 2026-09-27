package dev.shrimpscript.porthole

/**
 * The decisions behind the working chips, kept pure so they are testable: which chips
 * are stale against the session list, and whether a working-off frame may cancel a
 * notification that a "Done" has since taken over.
 */
object ChipPolicy {
    /** Chips whose session the list no longer reports as working. */
    fun stale(chips: Set<String>, workingIds: Set<String>): Set<String> = chips - workingIds

    /** A working-off frame clears the id only if no Done was posted for it recently. */
    fun mayCancel(doneAtMs: Long?, nowMs: Long, graceMs: Long = 4000): Boolean =
        doneAtMs == null || nowMs - doneAtMs > graceMs
}
