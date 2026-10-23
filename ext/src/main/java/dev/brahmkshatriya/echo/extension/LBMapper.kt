package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

fun JsonObject.listenToTrack(): Track? {
    val tm = obj("track_metadata") ?: return null
    val info = tm.obj("additional_info")
    val map = tm.obj("mbid_mapping")

    val title = tm["track_name"].str() ?: return null
    val artistName = tm["artist_name"].str().orEmpty()

    // Il mapping di LB è più affidabile di quanto inviato dal player
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
            isRadioSupported = false,
            isFollowable = false,
            isSaveable = false,
        )
    }.orEmpty()

    val artists = when {
        mapped.isNotEmpty() -> mapped
        artistName.isNotBlank() -> listOf(
            // Ascolto non mappato: niente mbid, quindi l'artista non è navigabile
            Artist(id = artistName, name = artistName,
                isRadioSupported = false, isFollowable = false, isSaveable = false)
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
            isRadioSupported = false,
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
        isRadioSupported = false,
        isSaveable = false,
        isHideable = false,
        isLikeable = false,
    )
}