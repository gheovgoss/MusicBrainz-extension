package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.*

fun JsonObject.listenToTrack(): Track? {
    val tm = obj("track_metadata") ?: return null
    val info = tm.obj("additional_info")
    val map = tm.obj("mbid_mapping")

    val title = tm["track_name"].str() ?: return null
    val artistName = tm["artist_name"].str().orEmpty()

    val mbid = map?.get("recording_mbid").str() ?: info?.get("recording_mbid").str()
    val releaseMbid = map?.get("release_mbid").str() ?: info?.get("release_mbid").str()
    val rgMbid = info?.get("release_group_mbid").str()

    val mapped = map?.arr("artists")?.mapNotNull { a ->
        val o = a as? JsonObject ?: return@mapNotNull null
        val id = o["artist_mbid"].str() ?: return@mapNotNull null
        Artist(
            id = id,
            name = o["artist_credit_name"].str() ?: artistName,
            extras = mapOf("mbid" to id),
            isRadioSupported = true,
            isFollowable = false,
            isSaveable = false,
        )
    }.orEmpty()

    val artists = when {
        mapped.isNotEmpty() -> mapped
        artistName.isNotBlank() -> listOf(
            Artist(id = artistName, name = artistName,
                isRadioSupported = true, isFollowable = false, isSaveable = false)
        )
        else -> emptyList()
    }

    val cover = releaseMbid?.let { cover("release/$it/front-250") }
    val releaseName = tm["release_name"].str()

    val album = if (rgMbid != null && releaseName != null) {
        Album(
            id = rgMbid,
            title = releaseName,
            cover = cover,
            artists = artists,
            extras = mapOf("mbid" to rgMbid, "kind" to "release-group"),
            isRadioSupported = true,
            isSaveable = false,
        )
    } else null

    return Track(
        id = mbid ?: this["recording_msid"].str() ?: "$artistName - $title",
        title = title,
        cover = cover,
        artists = artists,
        album = album,
        duration = (info?.get("duration_ms") as? JsonPrimitive)?.longOrNull,
        extras = if (mbid != null) mapOf("mbid" to mbid) else emptyMap(),
        isRadioSupported = true,
        isSaveable = false,
        isHideable = false,
        isLikeable = false,
    )
}

private fun creditOf(name: String?, mbids: JsonArray): List<Artist> {
    val id = mbids.firstOrNull().str() ?: return emptyList()
    return listOf(
        Artist(
            id = id,
            name = name ?: "?",
            extras = mapOf("mbid" to id),
            isRadioSupported = true,
            isFollowable = false,
            isSaveable = false,
        )
    )
}

fun JsonObject.statArtist(): Artist? {
    val id = this["artist_mbid"].str() ?: return null
    val name = this["artist_name"].str() ?: return null
    return Artist(
        id = id,
        name = name,
        subtitle = this["listen_count"].str()?.let { "$it listens" },
        extras = mapOf("mbid" to id),
        isRadioSupported = true,
        isFollowable = false,
        isSaveable = false,
    )
}

fun JsonObject.statAlbum(): Album? {
    val id = this["release_group_mbid"].str() ?: return null
    val title = this["release_group_name"].str() ?: return null
    return Album(
        id = id,
        title = title,
        cover = cover("release-group/$id/front-250"),
        artists = creditOf(this["artist_name"].str(), arr("artist_mbids")),
        subtitle = this["listen_count"].str()?.let { "$it ascolti" },
        extras = mapOf("mbid" to id, "kind" to "release-group"),
        isRadioSupported = true,
        isSaveable = false,
    )
}

fun JsonObject.freshAlbum(): Album? {
    val id = this["release_group_mbid"].str() ?: return null
    val title = this["release_name"].str() ?: return null
    return Album(
        id = id,
        title = title,
        cover = cover("release-group/$id/front-250"),
        artists = creditOf(this["artist_credit_name"].str(), arr("artist_mbids")),
        subtitle = listOfNotNull(this["release_group_primary_type"].str(), this["release_date"].str())
            .joinToString(" • ").ifEmpty { null },
        extras = mapOf("mbid" to id, "kind" to "release-group"),
        isRadioSupported = true,
        isSaveable = false,
    )
}

fun JsonObject.popularTrack(fallback: Artist): Track? {
    val id = this["recording_mbid"].str() ?: return null
    val title = this["recording_name"].str() ?: return null
    val artists = creditOf(this["artist_name"].str(), arr("artist_mbids")).ifEmpty { listOf(fallback) }
    val rel = this["release_mbid"].str() ?: this["caa_release_mbid"].str()
    return Track(
        id = id,
        title = title,
        cover = rel?.let { cover("release/$it/front-250") },
        artists = artists,
        extras = mapOf("mbid" to id),
        isRadioSupported = true,
        isSaveable = false,
        isHideable = false,
        isLikeable = false,
    )
}

fun JsonObject.jspfTrack(): Track? {
    val ident = (this["identifier"] as? JsonArray)?.firstOrNull().str() ?: this["identifier"].str()
    val mbid = ident?.substringAfterLast('/')?.takeIf { it.length == 36 } ?: return null
    val title = this["title"].str() ?: return null
    val ext = obj("extension")?.obj("https://musicbrainz.org/doc/jspf#track")
    val artistMbid = (ext?.get("artist_identifiers") as? JsonArray)?.firstOrNull().str()
        ?.substringAfterLast('/')
    val name = this["creator"].str() ?: "?"
    val release = ext?.get("release_identifier").str()?.substringAfterLast('/')

    return Track(
        id = mbid,
        title = title,
        cover = release?.let { cover("release/$it/front-250") },
        artists = listOf(
            Artist(
                id = artistMbid ?: name,
                name = name,
                extras = artistMbid?.let { mapOf("mbid" to it) } ?: emptyMap(),
                isRadioSupported = artistMbid != null,
                isFollowable = false,
                isSaveable = false,
            )
        ),
        duration = (this["duration"] as? JsonPrimitive)?.longOrNull,
        extras = mapOf("mbid" to mbid),
        isRadioSupported = true,
        isSaveable = false,
        isHideable = false,
        isLikeable = false,
    )
}