package io.github.prathamesh2640.sync.sample.sync

import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.sample.data.Note

/**
 * The three conflict-resolution strategies the sample demonstrates. Each is a
 * pure [ConflictResolver] — no I/O, no mutation of its arguments.
 */
enum class NoteResolver(val label: String, val resolver: ConflictResolver<Note>) {

    /** The version with the newer [Note.lastModified] wins. */
    LAST_WRITE_WINS(
        "Last-write-wins",
        ConflictResolver { local, remote -> if (local.lastModified >= remote.lastModified) local else remote },
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
