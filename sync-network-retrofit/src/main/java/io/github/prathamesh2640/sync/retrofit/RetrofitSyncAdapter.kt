package io.github.prathamesh2640.sync.retrofit

import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

/**
 * Retrofit-backed [SyncNetworkAdapter] — turns a host's Retrofit API into the
 * never-throwing [NetworkResult] surface the engine expects.
 *
 * The host keeps ownership of their Retrofit setup (base URL, converter,
 * interceptors, auth). They pass this adapter three suspend call references —
 * one per engine operation — each returning a Retrofit [Response]. This adapter
 * adds nothing to the wire; it only runs the call on an I/O dispatcher and maps
 * the outcome onto a [NetworkResult] branch, honouring the
 * [SyncNetworkAdapter] contract that **nothing is ever thrown across the
 * boundary** (a serialization failure, a lost connection, or a 5xx must all come
 * back as a value).
 *
 * Passing call references (rather than the library owning a generic Retrofit
 * service) sidesteps Retrofit's type-erasure limits on generic service
 * interfaces and keeps `Retrofit`/`OkHttpClient` out of the public API — only
 * [Response] appears, which the host uses anyway.
 *
 * ```kotlin
 * // Host's own Retrofit service — plain, concrete, idiomatic Retrofit:
 * interface NoteApi {
 *     @POST("notes/push")
 *     suspend fun push(@Body notes: List<Note>): Response<Unit>
 *
 *     @GET("notes")
 *     suspend fun pull(@Query("since") since: Long): Response<List<Note>>
 *
 *     @POST("notes/delete")
 *     suspend fun delete(@Body ids: List<String>): Response<Unit>
 * }
 *
 * val api = retrofit.create(NoteApi::class.java)
 * val adapter = RetrofitSyncAdapter<Note>(
 *     pushCall = api::push,
 *     pullCall = api::pull,
 *     deleteCall = api::delete,
 * )
 * val engine = SyncEngine.create(adapter = adapter, store = store)
 * ```
 *
 * ### Result mapping
 * - `2xx` → [NetworkResult.Success] (pull yields the body, or an empty list on an
 *   empty/`204` response; push/delete yield [Unit]).
 * - non-`2xx` → [NetworkResult.HttpError] with the status code and reason.
 * - [IOException] (no connectivity, timeout, connection refused, TLS) →
 *   [NetworkResult.NetworkError].
 * - anything else (e.g. a converter failure parsing the body) →
 *   [NetworkResult.UnknownError].
 * - [CancellationException] is re-thrown, never swallowed, so structured
 *   concurrency and coroutine cancellation keep working.
 *
 * ### Idempotency
 * Retries reuse the same [SyncableEntity.id] (a client UUID), so a replayed push
 * is safe as long as the host keys server-side writes on that id — see the
 * [SyncNetworkAdapter] contract.
 *
 * @param T the [SyncableEntity] subtype this adapter transfers.
 * @param pushCall the host call that uploads a batch; returns the raw HTTP
 *   [Response] (body ignored).
 * @param pullCall the host call that fetches entities changed since an epoch-ms
 *   watermark; returns the changed entities in the response body.
 * @param deleteCall the host call that confirms server-side hard-deletion of the
 *   given ids; returns the raw HTTP [Response] (body ignored).
 * @param ioDispatcher dispatcher the network calls run on; injectable for tests.
 *   Defaults to [Dispatchers.IO].
 */
public class RetrofitSyncAdapter<T : SyncableEntity> @JvmOverloads constructor(
    private val pushCall: suspend (List<T>) -> Response<Unit>,
    private val pullCall: suspend (Long) -> Response<List<T>>,
    private val deleteCall: suspend (List<String>) -> Response<Unit>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncNetworkAdapter<T> {

    override suspend fun push(payload: List<T>): NetworkResult<Unit> =
        execute { mapResponse(pushCall(payload)) { } }

    override suspend fun pull(since: Long): NetworkResult<List<T>> =
        execute { mapResponse(pullCall(since)) { body -> body ?: emptyList() } }

    override suspend fun delete(ids: List<String>): NetworkResult<Unit> =
        execute { mapResponse(deleteCall(ids)) { } }

    /**
     * Runs [block] on [ioDispatcher] and converts any throwable it raises into
     * the matching [NetworkResult] branch — the single place the "never throw"
     * contract is enforced.
     */
    private suspend fun <R> execute(block: suspend () -> NetworkResult<R>): NetworkResult<R> =
        withContext(ioDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e // cooperative cancellation must propagate
            } catch (e: IOException) {
                NetworkResult.NetworkError(e)
            } catch (e: Throwable) {
                NetworkResult.UnknownError(e)
            }
        }

    /** Maps a resolved [Response] onto Success (via [onSuccess]) or HttpError. */
    private fun <B, R> mapResponse(response: Response<B>, onSuccess: (B?) -> R): NetworkResult<R> =
        if (response.isSuccessful) {
            NetworkResult.Success(onSuccess(response.body()))
        } else {
            NetworkResult.HttpError(
                code = response.code(),
                message = response.message().ifBlank { "HTTP ${response.code()}" },
            )
        }
}
