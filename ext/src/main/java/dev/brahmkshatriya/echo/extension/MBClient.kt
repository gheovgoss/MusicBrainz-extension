package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private fun mbLog(msg: String) = println("MbExt: $msg")

class MBClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val userAgent: () -> String,
) {
    private companion object {
        const val BASE = "https://musicbrainz.org/ws/2/"
        const val MIN_INTERVAL_MS = 1_100L
        const val MAX_ATTEMPTS = 3
        const val CACHE_SIZE = 300
    }

    private class Resp(val code: Int, val body: String?)

    private val mutex = Mutex()
    private var lastCallAt = 0L
    private val json = Json { ignoreUnknownKeys = true }

    private val cache = object : LinkedHashMap<String, JsonObject>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>) = size > CACHE_SIZE
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject? {
        val url = (BASE + path.trimStart('/')).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("fmt", "json")
        }.build()
        val key = url.toString()

        synchronized(cache) { cache[key] }?.let { return it }

        return mutex.withLock {
            synchronized(cache) { cache[key] }?.let { return@withLock it }

            for (attempt in 0 until MAX_ATTEMPTS) {
                val wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)

                val resp = try {
                    withContext(Dispatchers.IO) { execute(url) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mbLog("errore di rete: ${e.message}")
                    null
                }
                lastCallAt = System.currentTimeMillis()

                if (resp == null) return@withLock null
                if (resp.code == 503) {
                    mbLog("503 (rate limit o User-Agent), tentativo ${attempt + 1}/$MAX_ATTEMPTS")
                    delay(2_000L * (attempt + 1))
                    continue
                }
                val text = resp.body ?: run {
                    mbLog("HTTP ${resp.code} per $path")
                    return@withLock null
                }
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return@withLock null
                synchronized(cache) { cache[key] = obj }
                return@withLock obj
            }
            null
        }
    }

    private fun execute(url: HttpUrl): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent())
            .header("Accept", "application/json")
            .get().build()
        http.newCall(req).execute().use { r ->
            return Resp(r.code, if (r.isSuccessful) r.body.string() else null)
        }
    }

    suspend fun getExternal(url: String): JsonObject? {
        synchronized(cache) { cache[url] }?.let { return it }
        val text = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url)
                    .header("User-Agent", userAgent())
                    .header("Accept", "application/json")
                    .get().build()
                http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mbLog("errore esterno: ${e.message}")
            null
        } ?: return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        synchronized(cache) { cache[url] = obj }
        return obj
    }
}