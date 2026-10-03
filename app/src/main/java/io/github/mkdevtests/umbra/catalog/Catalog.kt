package io.github.mkdevtests.umbra.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

/** A profile of the external catalogue: who, in which categories and folders, how much of it is on the NAS. */
@Serializable
data class CatalogPerson(
    val id: String,
    val name: String,
    val categories: List<String> = emptyList(),
    /** Folders of the catalogue's root ("A/B/Name"): the profile's videos are below them. */
    val folders: List<String> = emptyList(),
    val count: Int = 0,
    val size: Long = 0,
    val followed: Boolean = false,
    /** Changes when the picture does: part of its cache key. */
    val photo: Long = 0,
)

/** A video of the catalogue, by its path below the catalogue's root ("A/B/Name/file.mp4"), and the pictures next to it. */
@Serializable
data class CatalogVideo(val path: String, val size: Long = 0, val images: List<String> = emptyList())

/** What a sync brought: the profiles and every video. */
@Serializable
data class CatalogData(
    val people: List<CatalogPerson> = emptyList(),
    val videos: List<CatalogVideo> = emptyList(),
    val syncedAt: Long = 0,
    /** The NAS folder the catalogue's root is, as an app path ("Media\Divers"); null until found. */
    val root: String? = null,
)

/**
 * The catalogue as the screens use it: each profile's videos (a video
 * belongs to the profile owning the nearest of its parent folders), and the
 * rest, apart. Built once per sync.
 */
class CatalogIndex(val data: CatalogData) {
    val people: List<CatalogPerson> = data.people
    val byId: Map<String, CatalogPerson> = people.associateBy { it.id }
    private val byPerson: Map<String, List<CatalogVideo>>

    /** Videos of no profile ("Groupes"). */
    val ungrouped: List<CatalogVideo>

    /** Every category, by name. */
    val categories: List<String> = people.flatMap { it.categories }.distinct().sorted()

    init {
        val owner = HashMap<String, String>()
        people.forEach { person -> person.folders.forEach { owner.putIfAbsent(it.trim('/').lowercase(), person.id) } }
        val grouped = HashMap<String, MutableList<CatalogVideo>>()
        val rest = ArrayList<CatalogVideo>()
        data.videos.forEach { video ->
            val id = ownerOf(video.path, owner)
            if (id == null) rest += video else grouped.getOrPut(id) { ArrayList() } += video
        }
        byPerson = grouped
        ungrouped = rest
    }

    fun videosOf(id: String): List<CatalogVideo> = byPerson[id].orEmpty()

    /** The app path of [video] on the NAS; null while the root isn't found. */
    fun nasPath(video: CatalogVideo): String? = nasPath(video.path)

    fun nasPath(path: String): String? = data.root?.let { "$it\\${path.trim('/').replace('/', '\\')}" }

    private fun ownerOf(path: String, owner: Map<String, String>): String? {
        var folder = path.trim('/').lowercase().substringBeforeLast('/', "")
        while (folder.isNotEmpty()) {
            owner[folder]?.let { return it }
            folder = folder.substringBeforeLast('/', "")
        }
        return null
    }
}

/** What a profile's page shows beyond the card: other names, facts, a text. */
@Serializable
data class CatalogProfile(
    val aliases: List<String> = emptyList(),
    /** Label → value, in the order to show them. */
    val facts: List<Pair<String, String>> = emptyList(),
    val text: String? = null,
)

/** One page of profiles: the server's JSON → profiles, and the total. */
fun parsePeople(json: JsonObject, list: String): Pair<List<CatalogPerson>, Int> {
    val people = json[list]?.jsonArray.orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        CatalogPerson(
            id = o.text("id") ?: return@mapNotNull null,
            name = o.text("name").orEmpty(),
            categories = o.texts("categories"),
            folders = o.texts("folders"),
            count = o.text("video_count")?.toIntOrNull() ?: 0,
            size = o.text("size_bytes")?.toLongOrNull() ?: 0,
            followed = o.text("followed") == "true",
            photo = o.text("photo_version")?.toDoubleOrNull()?.toLong() ?: 0,
        )
    }
    return people to (json.text("total")?.toIntOrNull() ?: people.size)
}

/** One page of videos → videos, and the total. */
fun parseVideos(json: JsonObject): Pair<List<CatalogVideo>, Int> {
    val videos = json["videos"]?.jsonArray.orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        CatalogVideo(o.text("path") ?: return@mapNotNull null, o.text("size_bytes")?.toLongOrNull() ?: 0, o.texts("related_images"))
    }
    return videos to (json.text("total")?.toIntOrNull() ?: videos.size)
}

/** Keys of a profile's data that are not facts to read: ids, links, pictures, the name and the text themselves. */
private val TECHNICAL = setOf(
    "id", "_id", "slug", "name", "bio", "image", "thumbnail", "face", "url", "links", "poster", "posters", "media",
    "aliases", "disambiguation", "created_at", "updated_at", "parent", "is_parent", "site_id", "site", "gender",
)

/** Facts with a label of their own, in this order; the others follow under their own name. */
private val LABELS = linkedMapOf(
    "birthday" to "Naissance",
    "birthplace" to "Lieu de naissance",
    "nationality" to "Nationalité",
    "ethnicity" to "Origine",
    "hair_colour" to "Cheveux",
    "eye_colour" to "Yeux",
    "height" to "Taille",
    "weight" to "Poids",
    "career_start_year" to "Débuts",
    "career_end_year" to "Fin",
)

/**
 * A profile's page from the server's JSON: its other names, its text, and
 * every fact the catalogue has, shown as it gives them (nested groups
 * flattened), the usual ones first under a French label.
 */
fun parseProfile(json: JsonObject, today: LocalDate = LocalDate.now()): CatalogProfile {
    val data = (json["profile"] as? JsonObject)?.get("data") as? JsonObject ?: JsonObject(emptyMap())
    val flat = LinkedHashMap<String, String>()
    fun collect(o: JsonObject) {
        o.forEach { (key, value) ->
            when (value) {
                is JsonObject -> collect(value)
                is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { flat.putIfAbsent(key, it) }
                else -> Unit
            }
        }
    }
    collect(data)
    val known = LABELS.mapNotNull { (key, label) -> flat[key]?.let { label to (if (key == "birthday") birthday(it, today) else it) } }
    val others = flat.filterKeys { it !in LABELS && it !in TECHNICAL && !it.endsWith("_id") }
        .map { (key, value) -> humanize(key) to value }
        .sortedBy { it.first }
    return CatalogProfile(
        aliases = json.texts("aliases"),
        facts = known + others,
        text = flat["bio"] ?: (data["bio"] as? JsonPrimitive)?.contentOrNull,
    )
}

/** "1995-04-08" → "08/04/1995 · 31 ans". */
internal fun birthday(value: String, today: LocalDate): String = runCatching {
    val born = LocalDate.parse(value.take(10))
    "${born.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} · ${Period.between(born, today).years} ans"
}.getOrDefault(value)

/** "hair_colour" → "Hair colour". */
private fun humanize(key: String) = key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.texts(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()

