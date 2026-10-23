package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf

private fun lbLog(msg: String) = println("LbExt: $msg")

class MusicBrainzExtension : LoginClient.CustomInput, ExtensionClient, SearchFeedClient {

    companion object {
        const val KEY_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://api.listenbrainz.org"

        const val KEY_CONTACT = "contact"

    }

    private lateinit var settings: Settings
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentUser: User? = null
    private var token: String? = null

    // --- Settings ---

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingTextInput("API URL", KEY_API_URL, "Change only for compatible servers (self-hosted, Libre.fm...)", DEFAULT_API_URL),

        SettingTextInput("Contact (email or URL)", KEY_CONTACT, "Sent to MusicBrainz in the User-Agent, as they require", ""),
    )

    override fun setSettings(settings: Settings) { this.settings = settings }

    private fun apiRoot() = settings.getString(KEY_API_URL)?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_API_URL


    // --- Login---

    override val forms = listOf(
        LoginClient.Form(
            key = "lb_login",
            label = "ListenBrainz",
            icon = LoginClient.InputField.Type.Password,
            inputFields = listOf(
                LoginClient.InputField(LoginClient.InputField.Type.Password, "token", "User token (listenbrainz.org/settings)", true)
            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val t = data["token"]?.trim().orEmpty()
        require(t.isNotEmpty()) { "Token required" }
        val req = Request.Builder().url(apiRoot() + "/1/validate-token")
            .header("Authorization", "Token $t").get().build()
        val body = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { it.body.string() }
        }
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (o?.get("valid")?.jsonPrimitive?.booleanOrNull != true) error("Invalid ListenBrainz token")
        val name = o["user_name"]?.jsonPrimitive?.contentOrNull ?: "ListenBrainz"
        return listOf(User(id = name, name = name, subtitle = "ListenBrainz", extras = mapOf("token" to t)))
    }

    override fun setLoginUser(user: User?) {
        currentUser = user
        token = user?.extras?.get("token")
    }

    override suspend fun getCurrentUser(): User? = currentUser?.copy(extras = emptyMap())

    // --- Data Preparation ---

    private val mb = MBClient(userAgent = {
        val contact = settings.getString(KEY_CONTACT)?.trim().orEmpty().ifEmpty { "unknown" }
        "EchoMusicBrainz/0.1 ( $contact )"
    })

    private suspend fun lookupMbid(d: TrackDetails): String? {
        d.track.extras["mbid"]?.takeIf { it.isNotBlank() }?.let { return it }

        val names = d.track.artists.map { it.name }.filter { it.isNotBlank() }
        val candidates = listOfNotNull(names.joinToString(", ").ifEmpty { null }, names.firstOrNull()).distinct()
        if (candidates.isEmpty() || d.track.title.isBlank()) return null

        for (artist in candidates) {
            val url = (apiRoot() + "/1/metadata/lookup/").toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("recording_name", d.track.title)
                .build()
            val body = try {
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).header("Authorization", "Token ${token.orEmpty()}").get().build()).execute().use {
                        val b = if (it.isSuccessful) it.body.string() else null
                        lbLog("lookup HTTP ${it.code} per '$artist' - '${d.track.title}': ${b?.take(300)}")
                        b
                    }
                }
            } catch (e: Exception) {
                lbLog("lookup errore: ${e.message}")
                null
            } ?: continue
            val mbid = runCatching {
                json.parseToJsonElement(body).jsonObject["recording_mbid"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!mbid.isNullOrBlank()) return mbid
        }
        return null
    }

    // --- Search ---

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        PagedData.Single<Shelf> { mb.searchAll(query) }.toFeed()
}