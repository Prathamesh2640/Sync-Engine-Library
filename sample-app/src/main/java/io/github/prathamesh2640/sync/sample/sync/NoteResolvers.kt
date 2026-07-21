package io.github.prathamesh2640.sync.sample.sync

import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.sample.data.Note

/**
 * The three conflict-resolution strategies the sample demonstrates. Each is a
 * pure [ConflictResolver] — no I/O, no mutation of its arguments — with one
 * deliberate, narrow exception documented on [LAST_WRITE_WINS] below.
 */
enum class NoteResolver(val label: String, val resolver: ConflictResolver<Note>) {

    /**
     * The version with the newer [Note.lastModified] wins — but only if `remote`'s
     * timestamp is plausible. `remote` is untrusted, network-sourced input
     * (see [ConflictResolver]'s SEC-09 note): a compromised or clock-skewed
     * server could otherwise send a timestamp far in the future and win every
     * conflict for that note forever. A timestamp more than [MAX_CLOCK_SKEW_MILLIS]
     * ahead of the device clock is treated as untrustworthy and local wins
     * instead. This one wall-clock read is a deliberate, narrow deviation from
     * "pure" for defensive reasons — it does not depend on `local` or `remote`'s
     * content, only on rejecting an implausible future timestamp.
     */
    LAST_WRITE_WINS(
        "Last-write-wins",
        ConflictResolver { local, remote ->
            val remoteIsPlausible = remote.lastModified <= System.currentTimeMillis() + MAX_CLOCK_SKEW_MILLIS
            if (remoteIsPlausible && remote.lastModified > local.lastModified) remote else local
        },
    ),

    /** The remote copy always wins. */
    SERVER_WINS(
        "Server-wins",
        ConflictResolver { _, remote -> remote },
    ),

    /** Keep the remote title, concatenate both bodies, take the newer timestamp. */
    MERGE(
        "Merge",
        ConflictResolver { local, remote ->
            remote.copy(
                body = listOf(remote.body, local.body).filter { it.isNotBlank() }.distinct().joinToString("\n---\n"),
                lastModified = maxOf(local.lastModified, remote.lastModified),
            )
        },
    );

    companion object {
        val DEFAULT: NoteResolver = LAST_WRITE_WINS
    }
}

/**
 * How far ahead of the device clock a remote timestamp may be before
 * [NoteResolver.LAST_WRITE_WINS] distrusts it.
 *
 * Declared at file scope, not inside `NoteResolver`'s companion object: Kotlin
 * initialises enum constants before an enum class's own companion object, so an
 * enum entry cannot reference a companion member at construction time.
 */
private const val MAX_CLOCK_SKEW_MILLIS: Long = 5 * 60 * 1000L // 5 minutes
