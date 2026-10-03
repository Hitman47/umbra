package io.github.mkdevtests.umbra.library

import kotlinx.serialization.Serializable

/** Prefix of an artwork path that is an image on the NAS, not on TMDB: "nas:Films\Dune\folder.jpg". */
const val LOCAL_ART = "nas:"

/**
 * Images found next to the videos, Infuse style: by folder (folder.jpg,
 * poster.jpg, cover.jpg, or the folder's only image) and by video
 * ("Dune.jpg", "Dune-poster.jpg"); backdrops from fanart.jpg or "Dune-fanart.jpg".
 * Keys are NAS paths of folders or videos, values NAS paths of images.
 */
@Serializable
data class LocalArt(val posters: Map<String, String> = emptyMap(), val backdrops: Map<String, String> = emptyMap()) {
    operator fun plus(other: LocalArt) = LocalArt(posters + other.posters, backdrops + other.backdrops)

    /** Only what is under [roots] (the shares that didn't answer: their art is kept as it was). */
    fun under(roots: Set<String>) = LocalArt(
        posters.filterKeys { it.substringBefore('\\') in roots },
        backdrops.filterKeys { it.substringBefore('\\') in roots },
    )
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
private val POSTER_NAMES = listOf("folder", "poster", "cover", "default", "show", "movie")
private val BACKDROP_NAMES = listOf("fanart", "backdrop", "background", "art")
/** Images that are no poster, even alone in their folder. */
private val OTHER_NAMES = setOf("banner", "logo", "clearlogo", "clearart", "disc", "discart", "landscape", "thumb", "characterart")

fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** The art of one folder ([folder], its [images] and [videos], as NAS paths). */
fun folderArt(folder: String, images: List<String>, videos: List<String>): LocalArt {
    if (images.isEmpty()) return LocalArt()
    fun base(path: String) = path.substringAfterLast('\\').substringBeforeLast('.').lowercase()
    val byBase = images.associateBy(::base)
    val posters = HashMap<String, String>()
    val backdrops = HashMap<String, String>()

    // The folder's own: by name first, else its only image when nothing says it is something else.
    val named = POSTER_NAMES.firstNotNullOfOrNull { byBase[it] }
    val alone = images.singleOrNull()?.takeIf { image ->
        val name = base(image)
        name !in OTHER_NAMES && name !in BACKDROP_NAMES && OTHER_NAMES.none { name.endsWith("-$it") } && BACKDROP_NAMES.none { name.endsWith("-$it") }
    }
    (named ?: alone)?.let { posters[folder] = it }
    BACKDROP_NAMES.firstNotNullOfOrNull { byBase[it] }?.let { backdrops[folder] = it }

    // A video's own: "Dune.jpg", "Dune-poster.jpg", "Dune-fanart.jpg" ("Dune-thumb.jpg" for an episode).
    videos.forEach { video ->
        val name = base(video)
        (byBase["$name-poster"] ?: byBase[name] ?: byBase["$name-thumb"])?.let { posters[video] = it }
        (byBase["$name-fanart"] ?: byBase["$name-backdrop"])?.let { backdrops[video] = it }
    }
    return LocalArt(posters, backdrops)
}

/**
 * [this] library with the NAS images in place of TMDB's: a film takes its
 * video's image, else its folder's; a show its folder's, each season its
 * "Saison N" folder's, each episode its video's.
 */
fun Library.withLocalArt(art: LocalArt): Library {
    if (art.posters.isEmpty() && art.backdrops.isEmpty()) return this
    fun local(path: String?) = path?.let { LOCAL_ART + it }
    return copy(
        movies = movies.map { movie ->
            val poster = art.posters[movie.file] ?: movie.folder?.let(art.posters::get)
            val backdrop = art.backdrops[movie.file] ?: movie.folder?.let(art.backdrops::get)
            if (poster == null && backdrop == null) movie else movie.copy(poster = local(poster) ?: movie.poster, backdrop = local(backdrop) ?: movie.backdrop)
        },
        shows = shows.map { show ->
            val folders = show.folders.sortedBy { it.length }
            val poster = folders.firstNotNullOfOrNull(art.posters::get)
            val backdrop = folders.firstNotNullOfOrNull(art.backdrops::get)
            show.copy(
                poster = local(poster) ?: show.poster,
                backdrop = local(backdrop) ?: show.backdrop,
                seasons = show.seasons.map { season ->
                    val parent = season.episodes.map { it.file.substringBeforeLast('\\') }.distinct().singleOrNull()
                    val seasonPoster = parent?.takeIf { it !in folders }?.let(art.posters::get)
                    season.copy(
                        poster = local(seasonPoster) ?: season.poster,
                        episodes = season.episodes.map { episode -> art.posters[episode.file]?.let { episode.copy(still = local(it)) } ?: episode },
                    )
                },
            )
        },
    )
}
