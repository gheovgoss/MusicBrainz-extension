package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Shelf
import kotlinx.serialization.json.JsonObject

val HOME_ORDER = listOf("recent", "recs", "top_artists", "top_albums", "fresh")

val HOME_TITLES = mapOf(
    "recent" to "Recent Playing",
    "recs" to "Recommendations",
    "top_artists" to "Your month's artist",
    "top_albums" to "Your month's album",
    "fresh" to "New music",
)

private val MOCK_ARTISTS = listOf(
    "Radiohead", "Daft Punk", "Queen", "Nirvana", "Björk",
    "Massive Attack", "Pink Floyd", "Fleetwood Mac", "Portishead", "Blur",
)
private val MOCK_ALBUMS = listOf(
    "OK Computer", "Discovery", "A Night at the Opera", "Nevermind", "Dummy",
    "Mezzanine", "The Dark Side of the Moon", "Rumours", "Homogenic", "Parklife",
)
private val MOCK_TRACKS = listOf(
    "Paranoid Android", "One More Time", "Bohemian Rhapsody", "Smells Like Teen Spirit",
    "Glory Box", "Teardrop", "Time", "Dreams", "Hunter", "Song 2",
)

private fun anyOf(field: String, names: List<String>) =
    "$field:(" + names.joinToString(" OR ") { "\"$it\"" } + ")"

suspend fun MBClient.mockHomeShelves(): Map<String, Shelf> {
    val artists = get("artist", mapOf("query" to anyOf("artist", MOCK_ARTISTS), "limit" to "30"))
        ?.arr("artists")?.mapNotNull { (it as? JsonObject)?.toArtist() }.orEmpty()
        .distinctBy { it.name }.take(10)
        .mapIndexed { i, a -> a.copy(subtitle = "${120 - i * 9} ascolti") }

    val albums = get(
        "release-group",
        mapOf("query" to anyOf("releasegroup", MOCK_ALBUMS) + " AND primarytype:album", "limit" to "30")
    )?.arr("release-groups")?.mapNotNull { (it as? JsonObject)?.toAlbum() }.orEmpty()
        .distinctBy { it.title }.take(10)

    val tracks = get("recording", mapOf("query" to anyOf("recording", MOCK_TRACKS), "limit" to "40"))
        ?.arr("recordings")?.mapNotNull { (it as? JsonObject)?.toTrack() }.orEmpty()
        .distinctBy { it.title }.take(10)

    return buildMap {
        if (tracks.isNotEmpty()) {
            put("recent", Shelf.Lists.Tracks(id = "recent", title = HOME_TITLES.getValue("recent"), list = tracks.take(5)))
            put("recs", Shelf.Lists.Tracks(id = "recs", title = HOME_TITLES.getValue("recs"), list = tracks.drop(5)))
        }
        if (artists.isNotEmpty())
            put("top_artists", Shelf.Lists.Items(id = "top_artists", title = HOME_TITLES.getValue("top_artists"), list = artists))
        if (albums.isNotEmpty()) {
            put("top_albums", Shelf.Lists.Items(
                id = "top_albums", title = HOME_TITLES.getValue("top_albums"),
                list = albums.mapIndexed { i, a -> a.copy(subtitle = "${80 - i * 6} ascolti") }
            ))
            put("fresh", Shelf.Lists.Items(
                id = "fresh", title = HOME_TITLES.getValue("fresh"), list = albums.reversed().take(6)
            ))
        }
    }
}