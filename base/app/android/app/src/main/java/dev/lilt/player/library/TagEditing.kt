package dev.lilt.player.library

import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.TagOverrideEntity

/**
 * Applies a Room tag override to a scanned song. Blank text fields and zero
 * numbers fall back to the MediaStore value so a partial override can never
 * hide a real tag.
 */
fun SongEntity.withTagOverride(override: TagOverrideEntity?): SongEntity {
    if (override == null) return this
    return copy(
        title = override.title.ifBlank { title },
        artist = override.artist.ifBlank { artist },
        album = override.album.ifBlank { album },
        year = if (override.year > 0) override.year else year,
        track = if (override.track > 0) override.track else track,
    )
}

/** Applies a media-id keyed override map to a list without touching scan data. */
fun List<SongEntity>.withTagOverrides(
    overrides: Map<Long, TagOverrideEntity>,
): List<SongEntity> =
    if (overrides.isEmpty()) this else map { song ->
        song.withTagOverride(overrides[song.mediaId])
    }

/**
 * Parses a year or track field. Blank means zero/unspecified; negative and
 * non-numeric values are invalid and return null.
 */
fun parseTagNumber(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.toIntOrNull()?.takeIf { it >= 0 }
}
