package com.yourlibrary.sync.core.adapter

/**
 * Outcome of a single network operation performed by a [SyncNetworkAdapter].
 *
 * A [SyncNetworkAdapter] **must never throw** across the library boundary — it
 * maps every possible outcome (success, HTTP error, transport failure, anything
 * unexpected) onto one of these branches. Callers handle the result with an
 * exhaustive `when`, so adding a branch here in a future version is a source-
 * breaking change that the compiler will flag at every call site.
 *
 * ```kotlin
 * when (val result = adapter.pull(since = lastSync)) {
 *     is NetworkResult.Success    -> store(result.data)
 *     is NetworkResult.HttpError  -> log("server said ${result.code}")
 *     is NetworkResult.NetworkError -> scheduleRetry(result.cause)
 *     is NetworkResult.UnknownError -> report(result.cause)
 * }
 * ```
 *
 * @param T the payload type returned on success. Covariant (`out`) so a
 *   `NetworkResult<List<Note>>` is assignable where `NetworkResult<List<*>>`
 *   is expected, and so the error branches can be `NetworkResult<Nothing>`.
 */
public sealed class NetworkResult<out T> {

    /**
     * The operation completed successfully.
     *
     * @property data the payload returned by the remote. For push/delete
     *   operations that have no meaningful body this is [Unit].
     */
    public data class Success<out T>(val data: T) : NetworkResult<T>()

    /**
     * The server was reached but responded with a non-success HTTP status.
     *
     * Use this for 4xx/5xx responses — the request completed end to end, so a
     * blind retry may repeat the same error. Retry policy is the engine's call.
     *
     * @property code the HTTP status code (e.g. `409`, `500`).
     * @property message the server-supplied reason phrase or error body, if any.
     */
    public data class HttpError(val code: Int, val message: String) : NetworkResult<Nothing>()

    /**
     * A transport-level failure prevented a response (no connectivity, timeout,
     * connection refused, TLS failure).
     *
     * These are typically transient — the engine retries with backoff.
     *
     * @property cause the underlying I/O exception.
     */
    public data class NetworkError(val cause: Throwable) : NetworkResult<Nothing>()

    /**
     * An unexpected failure that is neither a clean HTTP status nor a recognised
     * transport error (e.g. a serialization failure while parsing the response).
     *
     * @property cause the underlying exception.
     */
    public data class UnknownError(val cause: Throwable) : NetworkResult<Nothing>()
}
