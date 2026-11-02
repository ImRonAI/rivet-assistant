package com.hiron.emrys.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** As-you-type help for a search field: Brave Suggest and Spellcheck through the brain. Errors give nothing. */
interface Suggester {
    suspend fun suggest(q: String): List<String>

    /** The corrected query, or null when there is no correction. */
    suspend fun spellcheck(q: String): String?
}

/**
 * The brain's Brave search proxy (`/v1/search/<kind>`, `/v1/suggest`, `/v1/spellcheck`), behind the device token.
 * The Brave keys stay on the brain; results are kept in memory only.
 */
class SearchClient(http: OkHttpClient, private val gateway: () -> GatewayConfig) : Suggester {
    private val searching = http.newBuilder().readTimeout(SEARCH_TIMEOUT_S, TimeUnit.SECONDS).callTimeout(SEARCH_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val quick = http.newBuilder().readTimeout(QUICK_TIMEOUT_S, TimeUnit.SECONDS).callTimeout(QUICK_TIMEOUT_S, TimeUnit.SECONDS).build()

    suspend fun search(query: SearchQuery): Result<SearchResults> {
        val url = url("v1/search/${query.kind.wire}") { b ->
            if (query.q.isNotBlank()) b.addQueryParameter("q", query.q.trim())
            query.count?.let { b.addQueryParameter("count", it.toString()) }
            query.freshness?.let { b.addQueryParameter("freshness", it.wire) }
            query.location?.takeIf { it.isNotBlank() }?.let { b.addQueryParameter("location", it) }
            query.maxTokens?.let { b.addQueryParameter("max_tokens", it.toString()) }
        } ?: return Result.failure(SearchError.Failed(NOT_CONFIGURED))
        return try {
            val (code, body, retryAfter) = get(searching, url)
            if (code == 200) {
                SearchCodec.results(body)?.let { Result.success(it) } ?: Result.failure(SearchError.Failed(UNREADABLE))
            } else {
                Result.failure(error(code, body, retryAfter))
            }
        } catch (e: InterruptedIOException) {
            Result.failure(SearchError.Failed("Search took too long. Try again."))
        } catch (e: IOException) {
            Result.failure(SearchError.Offline)
        }
    }

    override suspend fun suggest(q: String): List<String> {
        val url = url("v1/suggest") { it.addQueryParameter("q", q).addQueryParameter("count", SUGGESTIONS.toString()) } ?: return emptyList()
        return quietly(url)?.let(SearchCodec::suggestions).orEmpty()
    }

    override suspend fun spellcheck(q: String): String? {
        val url = url("v1/spellcheck") { it.addQueryParameter("q", q) } ?: return null
        return quietly(url)?.let(SearchCodec::corrected)
    }

    /** A 200 body, or null on any error. */
    private suspend fun quietly(url: HttpUrl): JSONObject? = try {
        get(quick, url).takeIf { it.first == 200 }?.second
    } catch (e: IOException) {
        null
    }

    private fun url(path: String, query: (HttpUrl.Builder) -> Unit): HttpUrl? {
        val config = gateway()
        if (!config.complete) return null
        val builder = "${config.baseUrl()}/$path".toHttpUrlOrNull()?.newBuilder() ?: return null
        query(builder)
        return builder.build()
    }

    /** The status, the JSON body (empty when it isn't JSON) and the brain's `retry_after`. */
    private suspend fun get(client: OkHttpClient, url: HttpUrl): Triple<Int, JSONObject, Int?> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${gateway().token}").get().build()
        client.newCall(request).await().use { response ->
            val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            val retry = if (body.has("retry_after") && !body.isNull("retry_after")) body.optInt("retry_after") else null
            Triple(response.code, body, retry)
        }
    }

    private fun error(code: Int, body: JSONObject, retryAfter: Int?): SearchError {
        val said = body.optStringOrNull("error")?.takeIf { it.isNotBlank() }
        return when (code) {
            401 -> SearchError.Failed("Emrys's brain didn't accept this phone's device token. Check it in Settings.")
            404 -> SearchError.Failed("Emrys's brain can't search yet. It needs an update.")
            429 -> SearchError.Busy(retryAfter)
            503 -> SearchError.NotSetUp
            400 -> SearchError.Failed(said ?: "Emrys couldn't search for that.")
            504 -> SearchError.Failed(said ?: "Brave Search took too long.")
            else -> SearchError.Failed(said ?: "Brave Search didn't answer properly.")
        }
    }

    companion object {
        const val SEARCH_TIMEOUT_S = 15L
        const val QUICK_TIMEOUT_S = 5L
        const val SUGGESTIONS = 8
        const val NOT_CONFIGURED = "Set the brain URL and device token in Settings first."
        private const val UNREADABLE = "Emrys's brain sent results this app can't read."
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}
