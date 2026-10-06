package io.github.mkdevtests.umbra.requests

/** What a release name's tags tell. */
enum class TagKind { Resolution, Hdr, Source, Codec, Language, Audio }

data class ReleaseTag(val label: String, val kind: TagKind)

/** A release name taken apart: a readable title, year, season / episode, technical tags. */
data class ParsedTitle(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val tags: List<ReleaseTag> = emptyList(),
) {
    /** "Dune (2021)", "Cosmos · S01E03". */
    val heading: String
        get() = buildString {
            append(title)
            year?.let { append(" ($it)") }
            season?.let { s ->
                append(" · S").append(s.toString().padStart(2, '0'))
                episode?.let { e -> append("E").append(e.toString().padStart(2, '0')) }
            }
        }

    fun tag(kind: TagKind) = tags.firstOrNull { it.kind == kind }?.label
}

/**
 * "Title.2023.MULTI.1080p.WEB-DL.x265-GROUP" taken apart: the title stops at
 * the first token recognized (year, SxxEyy, a tag); without any, the whole
 * name is the title. From Prowlarr Explorer.
 */
object ReleaseTitle {
    private val token = Regex("""[^ ._\-\[\]()]+""")
    private val year = Regex("""^(19|20)\d{2}$""")
    private val season = Regex("""^S(\d{1,2})(?:E(\d{1,3}))?$""", RegexOption.IGNORE_CASE)
    private val seasonWord = Regex("""^(?:Saison|Season)$""", RegexOption.IGNORE_CASE)

    private val rules: List<Pair<Regex, TagKind>> = listOf(
        Regex("""^(2160p|1080p|1080i|720p|480p|4K|UHD)$""", RegexOption.IGNORE_CASE) to TagKind.Resolution,
        Regex("""^(HDR|HDR10|HDR10Plus|DV|DoVi|DolbyVision|SDR)$""", RegexOption.IGNORE_CASE) to TagKind.Hdr,
        Regex("""^(BluRay|BDRip|BRRip|Remux|WEBDL|WEBRip|WEB|HDTV|DVDRip|DVD|HDRip|HDLight|CAM|TS)$""", RegexOption.IGNORE_CASE) to TagKind.Source,
        Regex("""^(x265|x264|HEVC|H264|H265|AV1|XviD|DivX)$""", RegexOption.IGNORE_CASE) to TagKind.Codec,
        Regex("""^(MULTI|FRENCH|TRUEFRENCH|VFF|VFQ|VF2|VFI|VF|VOSTFR|VOST|SUBFRENCH|ENGLISH|GERMAN|ITALIAN|SPANISH|NORDIC)$""", RegexOption.IGNORE_CASE) to TagKind.Language,
        Regex("""^(Atmos|TrueHD|DTSHD|DTS|DDP71|DDP51|DD51|DDP|EAC3|AC3|AAC|FLAC|MP3|Opus)$""", RegexOption.IGNORE_CASE) to TagKind.Audio,
    )

    private val canonical = mapOf(
        "WEBDL" to "WEB-DL", "H264" to "H.264", "H265" to "H.265", "DDP51" to "DDP 5.1", "DD51" to "DD 5.1",
        "DDP71" to "DDP 7.1", "DTSHD" to "DTS-HD", "DOLBYVISION" to "DV", "DOVI" to "DV", "HDR10PLUS" to "HDR10+",
        "4K" to "2160p", "UHD" to "2160p",
    )

    fun parse(raw: String): ParsedTitle {
        // WEB-DL, H.264, DDP5.1: glued back before cutting into tokens.
        val norm = raw
            .replace(Regex("""(?i)WEB-DL"""), "WEBDL")
            .replace(Regex("""(?i)\bH\.26([45])"""), "H26$1")
            .replace(Regex("""(?i)DTS-HD"""), "DTSHD")
            .replace(Regex("""(?i)HDR10\+"""), "HDR10Plus")
            .replace(Regex("""(?<!\d)([257])\.([01])(?!\d)"""), "$1$2")
        val tokens = token.findAll(norm).toList()
        var foundYear: Int? = null
        var foundSeason: Int? = null
        var foundEpisode: Int? = null
        val tags = LinkedHashMap<String, ReleaseTag>()
        var stop = -1
        tokens.forEachIndexed { i, m ->
            val t = m.value
            var hit = false
            if (i > 0 && foundYear == null && year.matches(t)) {
                foundYear = t.toInt()
                hit = true
            }
            season.find(t)?.let { s ->
                if (foundSeason == null) {
                    foundSeason = s.groupValues[1].toInt()
                    foundEpisode = s.groupValues[2].toIntOrNull()
                }
                hit = true
            }
            if (seasonWord.matches(t) && i + 1 < tokens.size) {
                tokens[i + 1].value.toIntOrNull()?.let {
                    if (foundSeason == null) foundSeason = it
                    hit = true
                }
            }
            rules.firstOrNull { (re, _) -> re.matches(t) }?.let { (_, kind) ->
                val label = canonical[t.uppercase()] ?: t.uppercase().takeIf { kind == TagKind.Language } ?: t
                tags.putIfAbsent(label.uppercase(), ReleaseTag(label, kind))
                hit = true
            }
            if (hit && stop < 0 && i > 0) stop = m.range.first
        }
        val title = (if (stop > 0) norm.substring(0, stop) else norm)
            .replace(Regex("""[._]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '[', '(', ')', ']')
            .ifBlank { raw.take(60) }
        return ParsedTitle(title, foundYear, foundSeason, foundEpisode, tags.values.sortedBy { it.kind.ordinal }.take(7))
    }
}
