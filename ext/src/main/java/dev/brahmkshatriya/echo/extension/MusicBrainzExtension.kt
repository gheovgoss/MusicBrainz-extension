package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditPrivacyClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.*
import kotlinx.coroutines.Dispatchers
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
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private fun lbLog(msg: String) = println("MbExt: $msg")

class MusicBrainzExtension : LoginClient.CustomInput, ExtensionClient, SearchFeedClient,
    AlbumClient, ArtistClient, TrackClient, HomeFeedClient, RadioClient, LibraryFeedClient,
    PlaylistClient, PlaylistEditPrivacyClient, LikeClient, HideClient {

    companion object {
        const val KEY_API_URL = "api_url"
        const val DEFAULT_API_URL = "https://api.listenbrainz.org"
        const val KEY_CONTACT = "contact"
        const val STATS_RANGE = "month"
        const val KEY_MOCK = "mock_home"

        const val USE_POPULAR = "use_popular"

    }

    private lateinit var settings: Settings
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private var currentUser: User? = null
    private var token: String? = null

    // --- Settings ---

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingTextInput("API URL", KEY_API_URL, "Change only for compatible servers (self-hosted, Libre.fm...)", DEFAULT_API_URL),

        SettingTextInput("Contact (email or URL)", KEY_CONTACT, "Sent to MusicBrainz in the User-Agent, as they require", ""),

        SettingSwitch(
            "Use Popular Tracks", USE_POPULAR,
            "Use popular tracks if the radio fails to fetch similar songs", true
        ),
        SettingSwitch(
            "Mock data", KEY_MOCK,
            "Developer setting for MusicBrainz, leave this to false", false
        ),
    )

    override fun setSettings(settings: Settings) { this.settings = settings }

    private fun apiRoot() = settings.getString(KEY_API_URL)?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_API_URL

    private fun mockEnabled() = settings.getBoolean(KEY_MOCK) ?: false

    private fun usePopularTracks() = true


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
    private suspend fun popularTracks(artist: Artist, limit: Int = 30): List<Track> {
        val mbid = artist.extras["mbid"] ?: run {
            lbLog("popular: artista senza mbid (${artist.id})")
            return emptyList()
        }
        if (token == null) lbLog("popular: no login, server can send 401")
        val root = lbGet(lbUrl("1", "popularity", "top-recordings-for-artist", mbid))
        lbLog("popular: keys=${root?.keys}")
        val all = root?.arr("items")?.mapNotNull { (it as? JsonObject)?.popularTrack(artist) }.orEmpty()
        return all.withoutHated().take(limit)
    }

    // --- Track ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track =
        mb.loadTrackDetails(track)

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media = error("No stream audio from MB")

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // --- Like / Hide---

    private val fbMutex = Mutex()
    private val fbSets: Map<Int, MutableSet<String>> = mapOf(1 to mutableSetOf(), -1 to mutableSetOf())
    private val fbAt = mutableMapOf(1 to 0L, -1 to 0L)
    private var fbFor: String? = null

    private suspend fun feedbackSet(score: Int, force: Boolean = false): Set<String> = fbMutex.withLock {
        val user = currentUser?.id ?: return@withLock emptySet()
        if (fbFor != user) {
            fbSets.values.forEach { it.clear() }
            fbAt[1] = 0L; fbAt[-1] = 0L
            fbFor = user
        }
        val set = fbSets.getValue(score)
        val fresh = !force && System.currentTimeMillis() - (fbAt[score] ?: 0L) < 300_000
        if (!fresh) {
            val all = mutableSetOf<String>()
            var offset = 0
            var ok = true
            while (offset < 2000) {
                val page = lbGet(
                    lbUrl("1", "feedback", "user", user, "get-feedback",
                        params = mapOf("score" to score.toString(), "count" to "100", "offset" to offset.toString()))
                )
                if (page == null) { ok = false; break }
                val items = page.arr("feedback")
                items.forEach { (it as? JsonObject)?.get("recording_mbid").str()?.let(all::add) }
                val total = (page["total_count"] as? JsonPrimitive)?.longOrNull ?: 0L
                offset += 100
                if (items.isEmpty() || offset >= total) break
            }
            if (ok) { set.clear(); set.addAll(all); fbAt[score] = System.currentTimeMillis() }
        }
        set.toSet()
    }

    private suspend fun setLocal(mbid: String, score: Int) = fbMutex.withLock {
        fbSets.values.forEach { it.remove(mbid) }
        if (score != 0) fbSets[score]?.add(mbid)
    }

    private suspend fun sendFeedback(mbid: String, score: Int) {
        lbPost(
            lbUrl("1", "feedback", "recording-feedback"),
            buildJsonObject { put("recording_mbid", mbid); put("score", score) }
        )
        setLocal(mbid, score)
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        val mbid = (item as? Track)?.let { quickMbid(it) } ?: return false
        return mbid in feedbackSet(1)
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track ?: error("Only tracks can be loved on ListenBrainz")
        val mbid = resolveMbid(track) ?: error("No MusicBrainz recording found for this track")
        if (!shouldLike && mbid !in feedbackSet(1, force = true)) return
        sendFeedback(mbid, if (shouldLike) 1 else 0)
    }

    override suspend fun isItemHidden(item: EchoMediaItem): Boolean {
        val mbid = (item as? Track)?.let { quickMbid(it) } ?: return false
        return mbid in feedbackSet(-1)
    }

    override suspend fun hideItem(item: EchoMediaItem, shouldHide: Boolean) {
        val track = item as? Track ?: error("Only tracks can be hidden on ListenBrainz")
        val mbid = resolveMbid(track) ?: error("No MusicBrainz recording found for this track")
        if (!shouldHide && mbid !in feedbackSet(-1, force = true)) return
        sendFeedback(mbid, if (shouldHide) -1 else 0)
    }

    private suspend fun List<Track>.withoutHated(): List<Track> {
        val hated = feedbackSet(-1)
        if (hated.isEmpty()) return this
        return filter { quickMbid(it) !in hated }
    }

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
            mb.recordingsByMbids(mbids).withoutHated()
        }

        fun tracks(id: String, l: List<Track>) {
            if (l.isNotEmpty()) real[id] = Shelf.Lists.Tracks(id = id, title = HOME_TITLES.getValue(id), list = l)
        }

        val forYou = async {
            withCovers(userPlaylists(user, false, "playlists", "createdfor").take(10))
        }

        tracks("recent", recent.await())
        tracks("recs", recs.await())

        forYou.await().takeIf { it.isNotEmpty() }?.let {
            real["for_you"] = Shelf.Lists.Items(id = "for_you", title = HOME_TITLES.getValue("for_you"), list = it)
        }

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
            val t = lbRadio("artist:($key)").withoutHated()
            if (t.isNotEmpty()) return t
        }
        if (mbid != null && usePopularTracks()) {
            val seed = Artist(id = mbid, name = name ?: "?", extras = mapOf("mbid" to mbid))
            return popularTracks(seed).shuffled()
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
                    val text = it.body.string()
                    lbLog("lb-radio HTTP ${it.code} - $prompt" + if (it.isSuccessful) "" else ": ${text.take(200)}")
                    if (it.isSuccessful) text else null
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

        fun fetch(editable: Boolean, vararg tail: String) = async { userPlaylists(user, editable, *tail) }
        val mine = fetch(true, "playlists")
        val forYou = fetch(false, "playlists", "createdfor")
        val collab = fetch(true, "playlists", "collaborator")

        val mineList = withCovers(mine.await())
        val forYouList = withCovers(forYou.await())
        val collabList = withCovers(collab.await())

        buildList {
            add(
                Shelf.Category(
                    id = "taste", title = "Taste", subtitle = "Loved and hated tracks",
                    feed = PagedData.Single { tasteShelves(user) }.toFeed(),
                )
            )
            mineList.takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Items(id = "pl_mine", title = "Your playlists", list = it))
            }
            forYouList.takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Items(id = "pl_for_you", title = "Created for you", list = it))
            }
            collabList.takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Items(id = "pl_collab", title = "Collaborative playlists", list = it))
            }
        }
    }

    private suspend fun feedbackTracks(user: String, score: Int): List<Track> {
        val items = lbGet(
            lbUrl("1", "feedback", "user", user, "get-feedback",
                params = mapOf("score" to score.toString(), "count" to "100", "metadata" to "true"))
        )?.arr("feedback")?.mapNotNull { it as? JsonObject }.orEmpty()

        val direct = items.map { it.listenToTrack() }
        val missing = items.filterIndexed { i, _ -> direct[i] == null }
            .mapNotNull { it["recording_mbid"].str() }.take(25)
        val fallback = if (missing.isEmpty()) emptyMap()
        else mb.recordingsByMbids(missing).associateBy { it.id }

        lbLog("feedback score=$score: ${items.size} voci, ${missing.size} da MB")
        return items.mapIndexedNotNull { i, o -> direct[i] ?: fallback[o["recording_mbid"].str()] }
    }

    private suspend fun tasteShelves(user: String): List<Shelf> = coroutineScope {
        val loved = async { feedbackTracks(user, 1) }
        val hated = async { feedbackTracks(user, -1) }
        buildList {
            loved.await().takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Tracks(id = "loved", title = "Loved tracks", list = it))
            }
            hated.await().takeIf { it.isNotEmpty() }?.let {
                add(Shelf.Lists.Tracks(id = "hated", title = "Hated tracks", list = it))
            }
        }
    }

    private val coverGate = Semaphore(4)

    private suspend fun withCovers(list: List<Playlist>): List<Playlist> = coroutineScope {
        val head = list.take(20).map { p ->
            async {
                val tracks = coverGate.withPermit { playlistJson(p.id)?.arr("track") }
                val cover: ImageHolder? = tracks?.firstNotNullOfOrNull { (it as? JsonObject)?.jspfTrack()?.cover }
                p.copy(
                    cover = cover ?: p.cover,
                    background = cover ?: p.background,
                    trackCount = tracks?.size?.toLong() ?: p.trackCount,
                )
            }
        }.awaitAll()
        head + list.drop(20)
    }

    // --- Playlist ---

    private val playlistCache = ConcurrentHashMap<String, Pair<Long, JsonObject>>()

    private suspend fun playlistJson(id: String, fresh: Boolean = false): JsonObject? {
        if (!fresh) playlistCache[id]?.let { (t, o) ->
            if (System.currentTimeMillis() - t < 600_000) return o
        }
        val o = lbGet(lbUrl("1", "playlist", id))?.obj("playlist") ?: run {
            lbLog("playlist $id not loaded")
            return null
        }
        if (playlistCache.size > 60) playlistCache.clear()
        playlistCache[id] = System.currentTimeMillis() to o
        return o
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val pl = playlistJson(playlist.id) ?: return playlist
        val raw = pl.arr("track").mapNotNull { it as? JsonObject }
        var cover: ImageHolder? = raw.firstNotNullOfOrNull { it.jspfTrack()?.cover }
        if (cover == null) {
            val ids = raw.mapNotNull { it.jspfTrack()?.id }.take(3)
            cover = mb.recordingsByMbids(ids).firstNotNullOfOrNull { it.cover }
        }
        val duration = raw.sumOf { (it["duration"] as? JsonPrimitive)?.longOrNull ?: 0L }
        val ext = pl.obj("extension")?.obj(JSPF_PL)
        return playlist.copy(
            title = pl["title"].str() ?: playlist.title,
            description = pl["annotation"].str()?.replace(Regex("<[^>]*>"), "")?.trim()?.ifEmpty { null },
            isPrivate = !((ext?.get("public") as? JsonPrimitive)?.booleanOrNull ?: false),
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

    private suspend fun lbPost(url: HttpUrl, body: JsonObject?): JsonObject? {
        val tk = token ?: error("Log in to ListenBrainz first")
        val req = Request.Builder().url(url)
            .header("Authorization", "Token $tk")
            .post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                val text = r.body.string()
                lbLog("POST ${url.encodedPath} body=${body?.toString()?.take(300)} -> HTTP ${r.code} ${text.take(200)}")
                check(r.isSuccessful) { "ListenBrainz HTTP ${r.code}: ${text.take(200)}" }
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            }
        }
    }

    private val uuidre = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private fun quickMbid(t: Track): String? = t.extras["mbid"]?.takeIf { uuidre.matches(it) }

    private suspend fun resolveMbid(t: Track): String? {
        quickMbid(t)?.let { return it }
        t.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            (mb.get("isrc/$isrc")?.arr("recordings")?.firstOrNull() as? JsonObject)
                ?.get("id").str()?.let { return it }
        }
        val artist = t.artists.joinToString(", ") { it.name }.trim()
        if (artist.isEmpty() || t.title.isBlank()) return null
        return lbGet(
            lbUrl("1", "metadata", "lookup", "",
                params = mapOf("artist_name" to artist, "recording_name" to t.title))
        )?.get("recording_mbid").str()
    }

    private data class PlMeta(
        val title: String, val annotation: String, val public: Boolean, val collaborators: List<String>,
    )

    private suspend fun currentMeta(id: String): PlMeta {
        val pl = playlistJson(id, fresh = true) ?: error("Playlist not found")
        val ext = pl.obj("extension")?.obj(JSPF_PL)
        return PlMeta(
            title = pl["title"].str().orEmpty(),
            annotation = pl["annotation"].str().orEmpty(),
            public = (ext?.get("public") as? JsonPrimitive)?.booleanOrNull ?: false,
            collaborators = ext?.arr("collaborators")?.mapNotNull { it.str() }.orEmpty(),
        )
    }

    private suspend fun updatePlaylist(id: String, change: (PlMeta) -> PlMeta) {
        val m = change(currentMeta(id))
        lbLog("CALL edit id=$id title='${m.title}' annotation='${m.annotation}' public=${m.public}")
        val body = buildJsonObject {
            putJsonObject("playlist") {
                put("title", m.title)
                put("annotation", m.annotation)
                putJsonObject("extension") {
                    putJsonObject(JSPF_PL) {
                        put("public", m.public)
                        put("collaborators", JsonArray(m.collaborators.map { JsonPrimitive(it) }))
                    }
                }
            }
        }
        lbPost(lbUrl("1", "playlist", "edit", id), body)
        playlistCache.remove(id)
    }

    private fun recordingsBody(mbids: List<String>) = buildJsonObject {
        putJsonObject("playlist") {
            put("track", JsonArray(mbids.map {
                buildJsonObject { put("identifier", "https://musicbrainz.org/recording/$it") }
            }))
        }
    }

    private fun JsonObject.hasRecording(mbid: String): Boolean = arr("track").any { t ->
        val o = t as? JsonObject ?: return@any false
        val ident = (o["identifier"] as? JsonArray)?.firstOrNull().str() ?: o["identifier"].str()
        ident?.endsWith(mbid) == true
    }

    private suspend fun userPlaylists(user: String, editable: Boolean, vararg tail: String): List<Playlist> =
        lbGet(lbUrl("1", "user", user, *tail, params = mapOf("count" to "50")))
            ?.arr("playlists")?.mapNotNull { (it as? JsonObject)?.jspfPlaylist(editable) }.orEmpty()

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val res = lbPost(
            lbUrl("1", "playlist", "create"),
            buildJsonObject {
                putJsonObject("playlist") {
                    put("title", title)
                    putJsonObject("extension") {
                        putJsonObject(JSPF_PL) { put("public", false) }
                    }
                }
            }
        )
        val id = res?.get("playlist_mbid").str() ?: error("ListenBrainz did not return the playlist id")
        runCatching {
            updatePlaylist(id) { it.copy(title = title, annotation = description.orEmpty(), public = false) }
        }.onFailure { lbLog("create: modifica successiva fallita: ${it.message}") }
        return Playlist(
            id = id, title = title, isEditable = true, isPrivate = true, description = description,
            extras = mapOf("mbid" to id),
            isRadioSupported = false, isSaveable = false, isShareable = false,
        )
    }

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) =
        updatePlaylist(playlist.id) { it.copy(title = title, annotation = description.orEmpty()) }

    override suspend fun setPrivacy(playlist: Playlist, isPrivate: Boolean) =
        updatePlaylist(playlist.id) { lbLog("CALL privacy id=${playlist.id} private=$isPrivate"); it.copy(public = !isPrivate) }

    override suspend fun deletePlaylist(playlist: Playlist) {
        lbPost(lbUrl("1", "playlist", playlist.id, "delete"), null)
        playlistCache.remove(playlist.id)
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>,
    ) {
        val mbids = new.mapNotNull { resolveMbid(it) }
        check(mbids.isNotEmpty()) { "No MusicBrainz recording found for the selected tracks" }
        if (mbids.size < new.size) lbLog("add: ${new.size - mbids.size} tracce senza MBID saltate")

        var offset = index
        for (chunk in mbids.chunked(50)) {
            val url = if (index in 0 until tracks.size)
                lbUrl("1", "playlist", playlist.id, "item", "add", offset.toString())
            else lbUrl("1", "playlist", playlist.id, "item", "add")
            lbPost(url, recordingsBody(chunk))
            offset += chunk.size
        }
        playlistCache.remove(playlist.id)
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int,
    ) {
        val mbid = tracks.getOrNull(fromIndex)?.let { quickMbid(it) }
            ?: error("Cannot move: track without a MusicBrainz id")
        lbPost(
            lbUrl("1", "playlist", playlist.id, "item", "move"),
            buildJsonObject { put("mbid", mbid); put("from", fromIndex); put("to", toIndex); put("count", 1) }
        )
        playlistCache.remove(playlist.id)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>,
    ) {
        val runs = mutableListOf<IntRange>()
        lbLog("CALL remove id=${playlist.id} indexes=$indexes tracks=${tracks.size}")
        for (i in indexes.distinct().sorted()) {
            val last = runs.lastOrNull()
            if (last != null && i == last.last + 1) runs[runs.lastIndex] = last.first..i else runs.add(i..i)
        }
        for (r in runs.asReversed()) {
            lbPost(
                lbUrl("1", "playlist", playlist.id, "item", "delete"),
                buildJsonObject { put("index", r.first); put("count", r.last - r.first + 1) }
            )
        }
        playlistCache.remove(playlist.id)
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> = coroutineScope {
        val user = currentUser?.id ?: return@coroutineScope emptyList()
        val own = async { userPlaylists(user, true, "playlists") }
        val collab = async { userPlaylists(user, true, "playlists", "collaborator") }
        val all = (own.await() + collab.await()).distinctBy { it.id }

        val mbid = track?.let { quickMbid(it) }
        all.map { p ->
            async {
                val has = mbid != null &&
                        coverGate.withPermit { playlistJson(p.id) }?.hasRecording(mbid) == true
                p to has
            }
        }.awaitAll()
    }
}