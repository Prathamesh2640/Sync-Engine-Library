package io.github.prathamesh2640.sync.retrofit

import io.github.prathamesh2640.sync.core.adapter.NetworkResult
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * End-to-end tests for [RetrofitSyncAdapter] driven over real HTTP with
 * [MockWebServer]. They prove the adapter maps every wire outcome onto the
 * correct [NetworkResult] branch and never throws across the boundary.
 */
class RetrofitSyncAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: RetrofitSyncAdapter<TestNote>

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TestApi::class.java)
        adapter = RetrofitSyncAdapter(
            pushCall = api::push,
            pullCall = api::pull,
            deleteCall = api::delete,
        )
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
            // already shut down by a test exercising transport failure
        }
    }

    private fun note(id: String) = TestNote(
        id = id,
        title = "t-$id",
        lastModified = 1L,
        syncState = SyncState.PENDING,
    )

    // --- success --------------------------------------------------------------

    @Test
    fun pull_success_returns_parsed_entities() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id":"a","title":"t-a","lastModified":1,"syncState":"PENDING","isDeleted":false}]""",
            ),
        )

        val result = adapter.pull(since = 0)

        assertTrue(result is NetworkResult.Success)
        assertEquals(listOf("a"), (result as NetworkResult.Success).data.map { it.id })
    }

    @Test
    fun push_success_returns_unit() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = adapter.push(listOf(note("a")))

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun delete_success_returns_unit() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = adapter.delete(listOf("a"))

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun pull_no_content_returns_empty_list() = runTest {
        server.enqueue(MockResponse().setResponseCode(204)) // 204: Retrofit yields a null body

        val result = adapter.pull(since = 0)

        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).data.isEmpty())
    }

    // --- HTTP error -----------------------------------------------------------

    @Test
    fun server_error_maps_to_http_error_with_code() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = adapter.push(listOf(note("a")))

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(500, (result as NetworkResult.HttpError).code)
    }

    // --- transport failure ----------------------------------------------------

    @Test
    fun connection_failure_maps_to_network_error() = runTest {
        server.shutdown() // nothing listening → IOException on connect

        val result = adapter.pull(since = 0)

        assertTrue(result is NetworkResult.NetworkError)
    }

    // --- unexpected (parse) failure ------------------------------------------

    @Test
    fun malformed_body_maps_to_unknown_error() = runTest {
        // 200 but the body is an object where a JSON array is expected → the Gson
        // converter throws a RuntimeException (not IOException) → UnknownError.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"not":"an-array"}"""))

        val result = adapter.pull(since = 0)

        assertTrue(result is NetworkResult.UnknownError)
    }
}

// --- test-only fixtures -------------------------------------------------------

internal data class TestNote(
    override val id: String,
    val title: String,
    override val lastModified: Long,
    override val syncState: SyncState,
    override val isDeleted: Boolean = false,
) : SyncableEntity

/** Plain, concrete host-style Retrofit service the adapter wraps via method refs. */
internal interface TestApi {
    @POST("push")
    suspend fun push(@Body notes: List<TestNote>): Response<Unit>

    @GET("pull")
    suspend fun pull(@Query("since") since: Long): Response<List<TestNote>>

    @POST("delete")
    suspend fun delete(@Body ids: List<String>): Response<Unit>
}
