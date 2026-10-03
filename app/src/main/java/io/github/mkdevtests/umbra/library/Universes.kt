package io.github.mkdevtests.umbra.library

/** Leads read per title: enough for the hero and the villain, not the cameos. */
private const val LEADS = 4

/** Characters that tell nothing of a story. */
private val GENERIC = setOf(
    "himself", "herself", "themselves", "self", "narrator", "voice", "additional voices", "host", "various", "cameo",
    "police officer", "policeman", "cop", "detective", "doctor", "nurse", "reporter", "news reporter", "bartender",
    "waiter", "waitress", "guard", "soldier", "man", "woman", "boy", "girl", "mother", "father", "mom", "dad", "kid",
    "driver", "priest", "teacher", "student", "the narrator", "lui-meme", "elle-meme", "narrateur",
)

/** First names alone: two films with a "John" share nothing. */
private val FIRST_NAMES = setOf(
    "john", "mary", "jack", "james", "david", "michael", "sarah", "anna", "tom", "paul", "peter", "mike", "max", "sam",
    "alex", "emma", "kate", "lucy", "joe", "ben", "nick", "dan", "chris", "mark", "tony", "jim", "bob", "bill", "frank",
    "george", "harry", "henry", "jane", "julia", "laura", "lisa", "maria", "marie", "martin", "matt", "rachel", "robert",
    "ryan", "steve", "susan", "will", "jean", "pierre", "marc", "sophie", "claire", "julie", "nicolas", "thomas", "louis",
    "lucas", "lea", "camille", "charlie", "danny", "eddie", "ellie", "jake", "jenny", "jessica", "josh", "katie", "leo",
    "luke", "mia", "molly", "nathan", "noah", "olivia", "rose", "sally", "simon", "tim", "walter",
)

private val PARENTHESES = Regex("""\([^)]*\)""")
private val AGE = Regex("""^(young|younger|old|older|little|adult|teen|teenage|baby|jeune|petit|petite)\s+""", RegexOption.IGNORE_CASE)

/** "batman", "bruce wayne": a character's name, compared without case or accents. */
fun characterKey(name: String) = normalizeTitle(name)

/**
 * The names worth linking titles with, from TMDB's characters of the leads:
 * "Bruce Wayne / Batman" → Batman, Bruce Wayne; "Himself", "Nurse", "John" left out.
 */
fun leadCharacters(characters: List<String?>): List<String> =
    characters.take(LEADS)
        // "Bruce Wayne / Batman": the name the story goes by first, it names the rows.
        .flatMap { raw -> raw.orEmpty().split('/', '|', ';').reversed() }
        .map { it.replace(PARENTHESES, "").trim().replace(AGE, "").trim().trim('"', '\'', '«', '»').trim() }
        .filter(::isTelling)
        .distinctBy(::characterKey)

private fun isTelling(name: String): Boolean {
    val key = characterKey(name)
    if (key.length < 3 || key in GENERIC || name.any { it.isDigit() }) return false
    return ' ' in key || key !in FIRST_NAMES
}

/** TMDB keywords that name a franchise: "dc extended universe (dceu)", "monsterverse"; not "parallel universe". */
fun franchiseKeywords(keywords: List<String>): List<String> = keywords.map { it.trim().lowercase() }.filter { keyword ->
    val franchise = "universe" in keyword || "franchise" in keyword || "monsterverse" in keyword ||
        "wizarding world" in keyword || "middle-earth" in keyword
    franchise && listOf("parallel", "alternate", "alternative", "multiverse", "pocket universe").none { it in keyword }
}.distinct()

/** "dc extended universe (dceu)" → "DC Extended Universe". */
fun universeLabel(keyword: String): String =
    keyword.replace(PARENTHESES, "").trim().split(' ').joinToString(" ") { word ->
        if (word.length <= 3 && word.all { it.isLetter() } && word in setOf("dc", "mcu", "dceu")) word.uppercase() else word.replaceFirstChar { it.uppercase() }
    }

/** A film or a show of the library, as the links see it. */
data class Linkable(
    val title: String,
    val year: Int?,
    val poster: String?,
    val rating: Double?,
    /** The film's file, or null for a show. */
    val movie: String?,
    /** The show's key, or null for a film. */
    val show: String?,
    val characters: List<String>,
    val universes: List<String>,
    val directors: List<String>,
    val saga: Int?,
) {
    val id get() = movie ?: show!!
    fun related() = Related(title, year, poster, movie = movie, show = show)
}

fun Library.linkables(): List<Linkable> =
    movies.map { Linkable(it.title, it.year, it.poster, it.rating, it.file, null, it.characters, it.universes, it.directors, it.sagaId) } +
        shows.map { Linkable(it.title, it.year, it.poster, it.rating, null, it.key, it.characters, it.universes, it.directors, null) }

/** A row of a title's page: its name ("Aussi avec Batman") and the library's titles in it, the newest first. */
data class LinkRow(val title: String, val items: List<Related>)

/**
 * The library's titles sharing a lead character or a franchise with [self]
 * (the saga's films aside: they have their own row), named after the
 * character most of them share.
 */
fun characterRow(self: Linkable, all: List<Linkable>): LinkRow? {
    val mine = self.characters.associateBy(::characterKey)
    val others = all.filter { it.id != self.id && (self.saga == null || it.saga != self.saga) }
    val matches = others.mapNotNull { other ->
        val shared = other.characters.map(::characterKey).filter { it in mine }
        val franchise = other.universes.any { it in self.universes }
        if (shared.isEmpty() && !franchise) null else other to shared
    }
    if (matches.isEmpty()) return null
    // The character the most titles share; ties: the first billed.
    val counts = matches.flatMap { it.second }.groupingBy { it }.eachCount()
    val order = mine.keys.toList()
    val lead = counts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { order.indexOf(it.key) })?.key
    val name = lead?.let { "Aussi avec ${mine.getValue(it)}" }
        ?: self.universes.firstOrNull()?.let { "Dans l'univers ${universeLabel(it)}" }
        ?: "Dans le même univers"
    return LinkRow(name, matches.map { it.first }.sortedByDescending { it.year ?: 0 }.map { it.related() })
}

/** The library's other titles by the same director (a show: creator), [excluded] aside. */
fun directorRow(self: Linkable, all: List<Linkable>, excluded: Set<String>): LinkRow? {
    if (self.directors.isEmpty()) return null
    val mine = self.directors.mapTo(HashSet()) { it.lowercase() }
    val items = all.filter { other ->
        other.id != self.id && other.id !in excluded && (self.saga == null || other.saga != self.saga) && other.directors.any { it.lowercase() in mine }
    }
    if (items.isEmpty()) return null
    val name = if (self.movie != null) "Du même réalisateur" else "Des mêmes créateurs"
    return LinkRow(name, items.sortedByDescending { it.year ?: 0 }.map { it.related() })
}

/** Titles tied by their characters or a franchise ("Batman", 9 titles), in release order. */
data class Universe(val name: String, val titles: List<Linkable>) {
    /** The best rated title's poster. */
    val poster get() = titles.filter { it.poster != null }.maxByOrNull { it.rating ?: 0.0 }?.poster
}

/**
 * The library's universes: titles joined when they share a lead character or
 * a franchise keyword, at least [min] of them, named after what most of them
 * share. A universe that is only one saga is left to the sagas.
 */
fun universesOf(library: Library, min: Int = 3): List<Universe> {
    val titles = library.linkables()
    val parent = IntArray(titles.size) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    fun join(members: List<Int>) = members.drop(1).forEach { parent[find(it)] = find(members.first()) }
    titles.withIndex().flatMap { (i, t) -> t.characters.map { characterKey(it) to i } }.groupBy({ it.first }, { it.second }).values.forEach(::join)
    titles.withIndex().flatMap { (i, t) -> t.universes.map { it to i } }.groupBy({ it.first }, { it.second }).values.forEach(::join)

    return titles.indices.groupBy(::find).values
        .filter { it.size >= min }
        .map { members -> members.map { titles[it] } }
        .filterNot { members -> members.all { it.movie != null && it.saga != null && it.saga == members.first().saga } }
        .map { members ->
            val characters = members.flatMap { title -> title.characters.map { characterKey(it) to it } }
            val topCharacter = characters.groupBy({ it.first }, { it.second }).maxByOrNull { it.value.size }
            val topUniverse = members.flatMap { it.universes }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            val name = when {
                topUniverse != null && topUniverse.value > (topCharacter?.value?.size ?: 0) -> universeLabel(topUniverse.key)
                topCharacter != null -> topCharacter.value.first()
                else -> members.first().title
            }
            Universe(name, members.sortedBy { it.year ?: Int.MAX_VALUE })
        }
        .sortedByDescending { it.titles.size }
}
