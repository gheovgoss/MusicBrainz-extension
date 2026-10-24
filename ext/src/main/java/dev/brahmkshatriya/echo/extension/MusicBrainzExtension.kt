package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
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
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private fun lbLog(msg: String) = println("MbExt: $msg")

class MusicBrainzExtension : LoginClient.CustomInput, ExtensionClient, SearchFeedClient,
    AlbumClient, ArtistClient, TrackClient, HomeFeedClient, RadioClient, LibraryFeedClient,
    PlaylistClient {

    companion object {
        const val KEY_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://api.listenbrainz.org"
        const val KEY_CONTACT = "contact"
        const val STATS_RANGE = "month"
        const val KEY_MOCK = "mock_home"

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

        SettingSwitch(
            "Mock data", KEY_MOCK,
            "Developer setting for MusicBrainz, leave this to false", false
        ),
    )

    override fun setSettings(settings: Settings) { this.settings = settings }

    private fun apiRoot() = settings.getString(KEY_API_URL)?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_API_URL

    private fun mockEnabled() = settings.getBoolean(KEY_MOCK) ?: false


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
                lbLog("lookup error: ${e.message}")
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
        PagedData.Single { mb.searchAll(query) }.toFeed()

    // --- Album ---
    override suspend fun loadAlbum(album: Album): Album = mb.loadAlbumDetails(album)

    override suspend fun loadTracks(album: Album): Feed<Track> =
        PagedData.Single { mb.albumTracks(album) }.toFeed()

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // --- Artist ---

    override suspend fun loadArtist(artist: Artist): Artist = mb.loadArtistDetails(artist)

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> =
        PagedData.Single { artistFeed(artist) }.toFeed()

    private suspend fun artistFeed(artist: Artist): List<Shelf> = coroutineScope {
        val popular = async { popularTracks(artist) }
        val links = async { mb.artistLinks(artist) }
        val disc = async { mb.artistShelves(artist) }

        val popularTracks = popular.await()
        val linkList = links.await()
        val discography = disc.await()

        buildList {
            if (popularTracks.isNotEmpty()) {
                add(Shelf.Lists.Tracks(id = "popular", title = "Popular tracks", list = popularTracks))
            }
            if (linkList.isNotEmpty()) {
                add(
                    Shelf.Lists.Categories(
                        id = "links",
                        title = "Listen / find",
                        list = linkList.map { (label, url) ->
                            Shelf.Category(
                                id = url,
                                title = label,
                                subtitle = url.substringAfter("://").substringBefore('/'),
                            )
                        }
                    )
                )
            }
            addAll(discography)
        }
    }
    private suspend fun popularTracks(artist: Artist): List<Track> {
        val mbid = artist.extras["mbid"] ?: run {
            lbLog("popular: artista senza mbid (${artist.id})")
            return emptyList()
        }
        if (token == null) lbLog("popular: no login, server can send 401")
        val root = lbGet(lbUrl("1", "popularity", "top-recordings-for-artist", mbid))
        lbLog("popular: keys=${root?.keys}")
        return root?.arr("items")
            ?.mapNotNull { (it as? JsonObject)?.popularTrack(artist) }
            ?.take(10).orEmpty()
    }

    // --- Track ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track =
        mb.loadTrackDetails(track)

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media = error("No stream audio from MB")

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // --- Home ---

    override suspend fun loadHomeFeed(): Feed<Shelf> =
        PagedData.Single { homeShelves() }.toFeed()

    private fun lbUrl(vararg segments: String, params: Map<String, String> = emptyMap()): HttpUrl =
        apiRoot().toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun homeShelves(): List<Shelf> = coroutineScope {
        val user = currentUser?.id ?: return@coroutineScope emptyList()
        val real = linkedMapOf<String, Shelf>()

        val recent = async {
            lbGet(lbUrl("1", "user", user, "listens", params = mapOf("count" to "50")))
                ?.obj("payload")?.arr("listens")
                ?.mapNotNull { (it as? JsonObject)?.listenToTrack() }.orEmpty()
        }
        val topArtists = async {
            lbGet(lbUrl("1", "stats", "user", user, "artists",
                params = mapOf("range" to STATS_RANGE, "count" to "20")))
                ?.obj("payload")?.arr("artists")
                ?.mapNotNull { (it as? JsonObject)?.statArtist() }.orEmpty()
        }
        val topAlbums = async {
            lbGet(lbUrl("1", "stats", "user", user, "release-groups",
                params = mapOf("range" to STATS_RANGE, "count" to "20")))
                ?.obj("payload")?.arr("release_groups")
                ?.mapNotNull { (it as? JsonObject)?.statAlbum() }.orEmpty()
        }
        val fresh = async {
            lbGet(lbUrl("1", "user", user, "fresh_releases",
                params = mapOf("days" to "30", "future" to "false", "sort" to "release_date")))
                ?.obj("payload")?.arr("releases")
                ?.mapNotNull { (it as? JsonObject)?.freshAlbum() }?.take(20).orEmpty()
        }
        val recs = async {
            val mbids = lbGet(lbUrl("1", "cf", "recommendation", "user", user, "recording",
                params = mapOf("count" to "20")))
                ?.obj("payload")?.arr("mbids")
                ?.mapNotNull { (it as? JsonObject)?.get("recording_mbid").str() }.orEmpty()
            mb.recordingsByMbids(mbids)
        }

        fun tracks(id: String, l: List<Track>) {
            if (l.isNotEmpty()) real[id] = Shelf.Lists.Tracks(id = id, title = HOME_TITLES.getValue(id), list = l)
        }
        tracks("recent", recent.await())
        tracks("recs", recs.await())
        topArtists.await().takeIf { it.isNotEmpty() }?.let {
            real["top_artists"] = Shelf.Lists.Items(id = "top_artists", title = HOME_TITLES.getValue("top_artists"), list = it)
        }
        topAlbums.await().takeIf { it.isNotEmpty() }?.let {
            real["top_albums"] = Shelf.Lists.Items(id = "top_albums", title = HOME_TITLES.getValue("top_albums"), list = it)
        }
        fresh.await().takeIf { it.isNotEmpty() }?.let {
            real["fresh"] = Shelf.Lists.Items(id = "fresh", title = HOME_TITLES.getValue("fresh"), list = it)
        }
        if (!mockEnabled()) return@coroutineScope real.values.toList()

        val mock = mb.mockHomeShelves()
        HOME_ORDER.mapNotNull { real[it] ?: mock[it] }
    }

    private suspend fun lbGet(url: HttpUrl): JsonObject? = try {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply {
                token?.let { header("Authorization", "Token $it") }
            }.get().build()
            http.newCall(req).execute().use {
                if (it.isSuccessful) it.body.string()
                else { lbLog("HTTP ${it.code} per $url"); null }
            }
        }?.let { text ->
            when (val el = runCatching { json.parseToJsonElement(text) }.getOrNull()) {
                is JsonObject -> el
                is JsonArray -> JsonObject(mapOf("items" to el))
                else -> null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        lbLog("errore: ${e.message}")
        null
    }

    // --- Radio ---

    private val slowHttp by lazy {
        http.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(130, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        val seed: Artist? = when (item) {
            is Artist -> item
            is Album -> item.artists.firstOrNull()
            is Track -> item.artists.firstOrNull()
            else -> null
        }
        val artist = seed ?: error("No artist to build a radio from")
        val title = when (item) {
            is Artist -> "${item.name} Radio"
            is Album -> "${item.title} Radio"
            is Track -> "${item.title} Radio"
            else -> "${artist.name} Radio"
        }
        return Radio(
            id = "${item::class.simpleName}:${item.id}",
            title = title,
            cover = item.cover ?: artist.cover,
            authors = listOf(artist),
            extras = buildMap {
                artist.extras["mbid"]?.let { put("mbid", it) }
                put("name", artist.name)
            },
        )
    }

    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> =
        PagedData.Single { radioTracks(radio) }.toFeed()

    private suspend fun radioTracks(radio: Radio): List<Track> {
        val mbid = radio.extras["mbid"]
        val name = radio.extras["name"]
        for (key in listOfNotNull(mbid, name).distinct()) {
            val t = lbRadio("artist:($key)")
            if (t.isNotEmpty()) return t
        }
        return emptyList()
    }

    private suspend fun lbRadio(prompt: String): List<Track> {
        val url = lbUrl("1", "explore", "lb-radio", params = mapOf("prompt" to prompt, "mode" to "easy"))
        val body = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).apply {
                    token?.let { header("Authorization", "Token $it") }
                }.get().build()
                slowHttp.newCall(req).execute().use {
                    lbLog("lb-radio HTTP ${it.code} per $prompt")
                    if (it.isSuccessful) it.body.string() else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lbLog("lb-radio errore: ${e.message}")
            null
        } ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(body).jsonObject.obj("payload")?.obj("jspf")
                ?.obj("playlist")?.arr("track")
        }.getOrNull()?.mapNotNull { (it as? JsonObject)?.jspfTrack() }.orEmpty()
    }

    // --- Library: playlist ---

    override suspend fun loadLibraryFeed(): Feed<Shelf> =
        PagedData.Single{ librarySections() }.toFeed()

    private suspend fun librarySections(): List<Shelf> = coroutineScope {
        val user = currentUser?.id ?: return@coroutineScope emptyList()

        fun fetch(vararg tail: String) = async {
            lbGet(lbUrl("1", "user", user, *tail, params = mapOf("count" to "50")))
                ?.arr("playlists")
                ?.mapNotNull { (it as? JsonObject)?.jspfPlaylist() }
                .orEmpty()
        }
        val mine = fetch("playlists")
        val forYou = fetch("playlists", "createdfor")
        val collab = fetch("playlists", "collaborator")

        buildList {
            mine.await().takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Items(id = "pl_mine", title = "Playlists", list = it))
            }
            forYou.await().takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Items(id = "pl_for_you", title = "Created for you", list = it))
            }
            collab.await().takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Items(id = "pl_collab", title = "Collaborative playlists", list = it))
            }
        }
    }

    // --- Playlist ---

    private val playlistCache = ConcurrentHashMap<String, Pair<Long, JsonObject>>()

    private suspend fun playlistJson(id: String): JsonObject? {
        playlistCache[id]?.let { (t, o) ->
            if (System.currentTimeMillis() - t < 60_000) return o
        }
        val o = lbGet(lbUrl("1", "playlist", id))?.obj("playlist") ?: run {
            lbLog("playlist $id")
            return null
        }
        if (playlistCache.size > 20) playlistCache.clear()
        playlistCache[id] = System.currentTimeMillis() to o
        return o
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val pl = playlistJson(playlist.id) ?: return playlist
        val raw = pl.arr("track").mapNotNull { it as? JsonObject }
        val cover = raw.firstNotNullOfOrNull { it.jspfTrack()?.cover }
        val duration = raw.sumOf { (it["duration"] as? JsonPrimitive)?.longOrNull ?: 0L }
        return playlist.copy(
            trackCount = raw.size.toLong(),
            duration = duration.takeIf { it > 0 },
            cover = cover ?: playlist.cover,
            background = cover ?: playlist.background,
        )
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> =
        PagedData.Single {
            playlistJson(playlist.id)?.arr("track")
                ?.mapNotNull { (it as? JsonObject)?.jspfTrack() }
                .orEmpty()
        }.toFeed()

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null
}