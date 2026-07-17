package io.github.prathamesh2640.sync.sample.net

import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.sample.data.Note
import java.io.IOException

/**
 * [SyncNetworkAdapter] over [InMemorySyncApi]. Maps outcomes to [NetworkResult]
 * and never throws — when the API is "offline" it returns [NetworkResult.NetworkError],
 * exactly as a real transport failure would surface.
 */
class FakeNoteApiAdapter(private val api: InMemorySyncApi) : SyncNetworkAdapter<Note> {

    override suspend fun push(payload: List<Note>): NetworkResult<Unit> = guard {
        api.push(payload)
        NetworkResult.Success(Unit)
    }

    override suspend fun pull(since: Long): NetworkResult<List<Note>> = guard {
        NetworkResult.Success(api.pull(since))
    }

    override suspend fun delete(ids: List<String>): NetworkResult<Unit> = guard {
        api.delete(ids)
        NetworkResult.Success(Unit)
    }

    private suspend fun <T> guard(block: suspend () -> NetworkResult<T>): NetworkResult<T> =
        if (!api.online) NetworkResult.NetworkError(IOException("offline (simulated)")) else block()
}
