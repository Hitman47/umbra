package io.github.mkdevtests.umbra.media

import io.github.mkdevtests.umbra.nas.RemoteFile
import kotlinx.serialization.Serializable
import java.io.IOException

enum class TrackKind { Video, Audio, Subtitle }

/** One track of a video file, as its container describes it. [language] is ISO 639-1 when known ("fr"). */
@Serializable
data class MediaTrack(
    val kind: TrackKind,
    val codec: String,
    val language: String? = null,
    val name: String? = null,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** "HDR10", "HLG", "Dolby Vision". */
    val hdr: String? = null,
    val forced: Boolean = false,
    val default: Boolean = false,
)

/** What a video file holds, read from its header (Matroska or MP4) without playing it. */
@Serializable
data class MediaInfo(val size: Long, val tracks: List<MediaTrack>, val duration: Double? = null) {
    val video get() = tracks.firstOrNull { it.kind == TrackKind.Video }
    val audio get() = tracks.filter { it.kind == TrackKind.Audio }
    val subtitles get() = tracks.filter { it.kind == TrackKind.Subtitle }
}

/** Reads the tracks of [file] ([name] tells the container); null for a format it doesn't know (AVI, TS). */
fun readMediaInfo(file: RemoteFile, name: String): MediaInfo? {
    val reader = RangeReader(file)
    val head = reader.read(0, 12)
    return when {
        head.size >= 4 && head.u32(0) == EBML_MAGIC -> Matroska(reader).read()
        head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES -> Mp4(reader).read()
        name.substringAfterLast('.').lowercase() in setOf("mp4", "m4v", "mov") -> Mp4(reader).read()
        else -> null
    }
}

private const val EBML_MAGIC = 0x1A45DFA3L
private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "free", "skip", "wide", "mdat", "pnot")

/** Reads ranges of a file, through a cached window: headers are read in small pieces. */
private class RangeReader(private val file: RemoteFile) {
    val size = file.size
    private var windowStart = -1L
    private var window = ByteArray(0)

    fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val wanted = minOf(length.toLong(), size - offset).toInt()
        if (windowStart >= 0 && offset >= windowStart && offset + wanted <= windowStart + window.size) {
            val from = (offset - windowStart).toInt()
            return window.copyOfRange(from, from + wanted)
        }
        val count = minOf(maxOf(wanted, WINDOW).toLong(), size - offset).toInt()
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = file.read(buffer, offset + filled, filled, count - filled)
            if (read <= 0) break
            filled += read
        }
        window = if (filled == count) buffer else buffer.copyOf(filled)
        windowStart = offset
        return window.copyOf(minOf(wanted, filled))
    }

    companion object {
        const val WINDOW = 256 * 1024
    }
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
private fun ByteArray.u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
private fun ByteArray.u64(at: Int) = (u32(at) shl 32) or u32(at + 4)
private fun ByteArray.fourCc(at: Int) = String(this, at, 4, Charsets.ISO_8859_1)

// --- Matroska ---

/** EBML elements: Segment holds Info (duration) and Tracks, usually before the first Cluster. */
private class Matroska(private val reader: RangeReader) {
    private var timestampScale = 1_000_000L
    private var duration: Double? = null
    private var tracks: List<MediaTrack>? = null

    fun read(): MediaInfo? {
        var position = 0L
        // EBML header, then the Segment.
        val header = element(position) ?: return null
        position = header.dataStart + header.size
        val segment = element(position)?.takeIf { it.id == SEGMENT } ?: return null
        val segmentEnd = if (segment.size < 0) reader.size else minOf(reader.size, segment.dataStart + segment.size)
        var tracksAt: Long? = null
        var infoAt: Long? = null
        position = segment.dataStart
        var steps = 0
        while (position < segmentEnd && steps++ < 64 && (tracks == null || duration == null)) {
            val child = element(position) ?: break
            when (child.id) {
                SEEK_HEAD -> seekHead(child).let { (tracksPos, infoPos) ->
                    tracksAt = tracksPos?.plus(segment.dataStart)
                    infoAt = infoPos?.plus(segment.dataStart)
                }
                INFO -> info(child)
                TRACKS -> tracks = tracks(child)
                CLUSTER -> break // the media itself: the header is over
            }
            if (child.size < 0) break
            position = child.dataStart + child.size
        }
        // Tracks or Info written after the clusters: the SeekHead says where.
        if (tracks == null) tracksAt?.let { at -> element(at)?.takeIf { it.id == TRACKS }?.let { tracks = tracks(it) } }
        if (duration == null) infoAt?.let { at -> element(at)?.takeIf { it.id == INFO }?.let(::info) }
        val found = tracks ?: return null
        return MediaInfo(reader.size, found, duration)
    }

    private class Element(val id: Long, val dataStart: Long, val size: Long)

    /** The element at [offset]: its ID, where its data starts and its size (-1: unknown). */
    private fun element(offset: Long): Element? {
        val bytes = reader.read(offset, 12)
        if (bytes.size < 2) return null
        val idLength = vintLength(bytes.u8(0)).takeIf { it in 1..4 } ?: return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or bytes.u8(i).toLong()
        if (bytes.size < idLength + 1) return null
        val sizeLength = vintLength(bytes.u8(idLength)).takeIf { it in 1..8 && idLength + it <= bytes.size } ?: return null
        var size = (bytes.u8(idLength) and (0xFF shr sizeLength)).toLong()
        var allOnes = size == (0xFF shr sizeLength).toLong()
        for (i in 1 until sizeLength) {
            val b = bytes.u8(idLength + i)
            if (b != 0xFF) allOnes = false
            size = (size shl 8) or b.toLong()
        }
        return Element(id, offset + idLength + sizeLength, if (allOnes) -1 else size)
    }

    private fun vintLength(first: Int): Int {
        if (first == 0) return 9
        var mask = 0x80
        var length = 1
        while (first and mask == 0) {
            mask = mask shr 1
            length++
        }
        return length
    }

    /** The children of [parent], read at once (headers are small). */
    private fun children(parent: Element, limit: Int = MAX_ELEMENT): List<Pair<Element, ByteArray>> {
        if (parent.size < 0 || parent.size > limit) return emptyList()
        val data = reader.read(parent.dataStart, parent.size.toInt())
        return parse(data, parent.dataStart)
    }

    private fun parse(data: ByteArray, base: Long): List<Pair<Element, ByteArray>> {
        val result = mutableListOf<Pair<Element, ByteArray>>()
        var at = 0
        while (at < data.size - 1) {
            val idLength = vintLength(data.u8(at)).takeIf { it in 1..4 } ?: break
            if (at + idLength >= data.size) break
            var id = 0L
            for (i in 0 until idLength) id = (id shl 8) or data.u8(at + i).toLong()
            val sizeLength = vintLength(data.u8(at + idLength)).takeIf { it in 1..8 } ?: break
            if (at + idLength + sizeLength > data.size) break
            var size = (data.u8(at + idLength) and (0xFF shr sizeLength)).toLong()
            for (i in 1 until sizeLength) size = (size shl 8) or data.u8(at + idLength + i).toLong()
            val start = at + idLength + sizeLength
            val end = minOf(data.size.toLong(), start + size).toInt()
            result += Element(id, base + start, size) to data.copyOfRange(start, end)
            at = end
        }
        return result
    }

    private fun seekHead(element: Element): Pair<Long?, Long?> {
        var tracksAt: Long? = null
        var infoAt: Long? = null
        children(element).filter { it.first.id == SEEK }.forEach { (seek, data) ->
            val fields = parse(data, seek.dataStart)
            val id = fields.firstOrNull { it.first.id == SEEK_ID }?.second?.let(::uint)
            val position = fields.firstOrNull { it.first.id == SEEK_POSITION }?.second?.let(::uint)
            when (id) {
                TRACKS -> tracksAt = position
                INFO -> infoAt = position
            }
        }
        return tracksAt to infoAt
    }

    private fun info(element: Element) {
        val fields = children(element)
        fields.firstOrNull { it.first.id == TIMESTAMP_SCALE }?.second?.let { timestampScale = uint(it) }
        fields.firstOrNull { it.first.id == DURATION }?.second?.let { duration = float(it) * timestampScale / 1e9 }
    }

    private fun tracks(element: Element): List<MediaTrack> = children(element, MAX_TRACKS).filter { it.first.id == TRACK_ENTRY }.mapNotNull { (entry, data) ->
        val fields = parse(data, entry.dataStart).associate { it.first.id to it.second }
        val kind = when (fields[TRACK_TYPE]?.let(::uint)) {
            1L -> TrackKind.Video
            2L -> TrackKind.Audio
            17L -> TrackKind.Subtitle
            else -> return@mapNotNull null
        }
        val codecId = fields[CODEC_ID]?.let(::text).orEmpty()
        val name = fields[NAME]?.let(::text)?.ifBlank { null }
        val language = (fields[LANGUAGE_BCP47]?.let(::text) ?: fields[LANGUAGE]?.let(::text) ?: "eng").let(::isoLanguage)
        val video = fields[VIDEO]?.let { parse(it, 0).associate { field -> field.first.id to field.second } }
        val audio = fields[AUDIO]?.let { parse(it, 0).associate { field -> field.first.id to field.second } }
        val transfer = video?.get(COLOUR)?.let { colour -> parse(colour, 0).firstOrNull { it.first.id == TRANSFER }?.second?.let(::uint) }
        val dolbyVision = fields[BLOCK_ADDITION_MAPPING]?.let { mapping ->
            parse(mapping, 0).firstOrNull { it.first.id == BLOCK_ADD_ID_TYPE }?.second?.let(::uint) in setOf(DVCC, DVVC)
        } == true || codecId.contains("DOVI", ignoreCase = true)
        MediaTrack(
            kind = kind,
            codec = codecName(codecId, name),
            language = language,
            name = name,
            channels = audio?.get(CHANNELS)?.let(::uint)?.toInt(),
            width = video?.get(PIXEL_WIDTH)?.let(::uint)?.toInt(),
            height = video?.get(PIXEL_HEIGHT)?.let(::uint)?.toInt(),
            hdr = if (kind != TrackKind.Video) null else when {
                dolbyVision -> "Dolby Vision"
                transfer == 16L -> "HDR10"
                transfer == 18L -> "HLG"
                else -> null
            },
            forced = fields[FLAG_FORCED]?.let(::uint) == 1L || name?.contains("forc", ignoreCase = true) == true,
            default = (fields[FLAG_DEFAULT]?.let(::uint) ?: 1L) == 1L,
        )
    }

    private fun uint(data: ByteArray): Long = data.take(8).fold(0L) { value, b -> (value shl 8) or (b.toLong() and 0xFF) }

    private fun float(data: ByteArray): Double = when (data.size) {
        4 -> Float.fromBits(uint(data).toInt()).toDouble()
        8 -> Double.fromBits(uint(data))
        else -> 0.0
    }

    private fun text(data: ByteArray) = String(data, Charsets.UTF_8).trimEnd('\u0000').trim()

    private companion object {
        const val SEGMENT = 0x18538067L
        const val SEEK_HEAD = 0x114D9B74L
        const val SEEK = 0x4DBBL
        const val SEEK_ID = 0x53ABL
        const val SEEK_POSITION = 0x53ACL
        const val INFO = 0x1549A966L
        const val TIMESTAMP_SCALE = 0x2AD7B1L
        const val DURATION = 0x4489L
        const val TRACKS = 0x1654AE6BL
        const val CLUSTER = 0x1F43B675L
        const val TRACK_ENTRY = 0xAEL
        const val TRACK_TYPE = 0x83L
        const val CODEC_ID = 0x86L
        const val NAME = 0x536EL
        const val LANGUAGE = 0x22B59CL
        const val LANGUAGE_BCP47 = 0x22B59DL
        const val FLAG_DEFAULT = 0x88L
        const val FLAG_FORCED = 0x55AAL
        const val VIDEO = 0xE0L
        const val PIXEL_WIDTH = 0xB0L
        const val PIXEL_HEIGHT = 0xBAL
        const val COLOUR = 0x55B0L
        const val TRANSFER = 0x55BAL
        const val AUDIO = 0xE1L
        const val CHANNELS = 0x9FL
        const val BLOCK_ADDITION_MAPPING = 0x41E4L
        const val BLOCK_ADD_ID_TYPE = 0x41E7L
        const val DVCC = 0x64766343L
        const val DVVC = 0x64767643L
        const val MAX_ELEMENT = 4 * 1024 * 1024
        const val MAX_TRACKS = 8 * 1024 * 1024
    }
}

// --- MP4 ---

/** ISO boxes: the "moov" box describes the tracks; it may be at the end of the file. */
private class Mp4(private val reader: RangeReader) {

    fun read(): MediaInfo? {
        var position = 0L
        var steps = 0
        while (position < reader.size && steps++ < 32) {
            val header = reader.read(position, 16)
            if (header.size < 8) return null
            var size = header.u32(0)
            val type = header.fourCc(4)
            var headerSize = 8
            if (size == 1L) {
                if (header.size < 16) return null
                size = header.u64(8)
                headerSize = 16
            } else if (size == 0L) {
                size = reader.size - position
            }
            if (size < headerSize) return null
            if (type == "moov") {
                if (size > MAX_MOOV) throw IOException("moov trop grand : $size")
                val moov = reader.read(position + headerSize, (size - headerSize).toInt())
                return parseMoov(moov)
            }
            position += size
        }
        return null
    }

    private class Box(val type: String, val data: ByteArray)

    private fun boxes(data: ByteArray, from: Int = 0, to: Int = data.size): List<Box> {
        val result = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = data.u32(at)
            val type = data.fourCc(at + 4)
            var header = 8
            if (size == 1L && at + 16 <= to) {
                size = data.u64(at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header || at + size > to) break
            result += Box(type, data.copyOfRange(at + header, (at + size).toInt()))
            at += size.toInt()
        }
        return result
    }

    private fun List<Box>.child(type: String) = firstOrNull { it.type == type }

    private fun parseMoov(moov: ByteArray): MediaInfo {
        val top = boxes(moov)
        val duration = top.child("mvhd")?.data?.let { mvhd ->
            if (mvhd.u8(0) == 1) mvhd.u64(24).toDouble() / mvhd.u32(20).coerceAtLeast(1) else mvhd.u32(16).toDouble() / mvhd.u32(12).coerceAtLeast(1)
        }
        val tracks = top.filter { it.type == "trak" }.mapNotNull(::track)
        return MediaInfo(reader.size, tracks, duration?.takeIf { it > 0 })
    }

    private fun track(trak: Box): MediaTrack? {
        val trakBoxes = boxes(trak.data)
        val mdia = boxes(trakBoxes.child("mdia")?.data ?: return null)
        val handler = mdia.child("hdlr")?.data?.takeIf { it.size >= 12 }?.fourCc(8) ?: return null
        val kind = when (handler) {
            "vide" -> TrackKind.Video
            "soun" -> TrackKind.Audio
            "sbtl", "subt", "text", "clcp" -> TrackKind.Subtitle
            else -> return null
        }
        val language = mdia.child("mdhd")?.data?.let { mdhd ->
            val at = if (mdhd.u8(0) == 1) 32 else 20
            if (mdhd.size < at + 2) return@let null
            val packed = mdhd.u16(at)
            listOf((packed shr 10) and 31, (packed shr 5) and 31, packed and 31).map { (it + 0x60).toChar() }.joinToString("")
        }?.let(::isoLanguage)
        val stbl = boxes(boxes(mdia.child("minf")?.data ?: return null).child("stbl")?.data ?: return null)
        val stsd = stbl.child("stsd")?.data?.takeIf { it.size >= 16 } ?: return null
        val entry = stsd.copyOfRange(8, stsd.size)
        val format = entry.fourCc(4)
        val tkhd = trakBoxes.child("tkhd")?.data
        val flags = tkhd?.let { it.u8(3) } ?: 1
        var width: Int? = null
        var height: Int? = null
        var channels: Int? = null
        var hdr: String? = null
        when (kind) {
            TrackKind.Video -> if (entry.size >= 36) {
                width = entry.u16(32)
                height = entry.u16(34)
                val inner = if (entry.size > 86) boxes(entry, 86, minOf(entry.size, entry.u32(0).toInt())) else emptyList()
                val transfer = inner.child("colr")?.data?.takeIf { it.size >= 8 && it.fourCc(0) == "nclx" }?.u16(6)
                hdr = when {
                    format in setOf("dvh1", "dvhe", "dva1", "dvav") || inner.any { it.type == "dvcC" || it.type == "dvvC" } -> "Dolby Vision"
                    transfer == 16 -> "HDR10"
                    transfer == 18 -> "HLG"
                    else -> null
                }
            }
            TrackKind.Audio -> if (entry.size >= 26) channels = entry.u16(24)
            TrackKind.Subtitle -> Unit
        }
        return MediaTrack(
            kind = kind,
            codec = codecName(format, null),
            language = language,
            channels = channels,
            width = width,
            height = height,
            hdr = hdr,
            default = flags and 1 == 1,
        )
    }

    private companion object {
        const val MAX_MOOV = 64L * 1024 * 1024
    }
}

// --- Names ---

/** A container's codec ID in words: "HEVC", "E-AC3", "DTS-HD MA", "PGS". */
internal fun codecName(id: String, trackName: String?): String {
    val name = trackName.orEmpty()
    val atmos = if (name.contains("atmos", ignoreCase = true)) " Atmos" else ""
    return when {
        id.startsWith("V_MPEGH/ISO/HEVC") || id in setOf("hvc1", "hev1", "dvh1", "dvhe") -> "HEVC"
        id.startsWith("V_MPEG4/ISO/AVC") || id in setOf("avc1", "avc3", "dva1", "dvav") -> "H.264"
        id == "V_AV1" || id == "av01" -> "AV1"
        id.startsWith("V_VP9") || id == "vp09" -> "VP9"
        id.startsWith("V_VP8") -> "VP8"
        id.startsWith("V_MPEG2") -> "MPEG-2"
        id.startsWith("V_MPEG4") || id == "mp4v" || id.startsWith("V_MS/VFW") -> "MPEG-4"
        id.startsWith("A_AAC") || id == "mp4a" -> "AAC"
        id == "A_AC3" || id == "ac-3" -> "AC3"
        id == "A_EAC3" || id == "ec-3" -> "E-AC3$atmos"
        id == "A_TRUEHD" || id == "mlpa" -> "TrueHD$atmos"
        id.startsWith("A_DTS") || id.startsWith("dts") -> when {
            Regex("""\bMA\b""").containsMatchIn(name) || id == "A_DTS/LOSSLESS" -> "DTS-HD MA"
            name.contains("DTS-HD", ignoreCase = true) -> "DTS-HD"
            name.contains("DTS:X", ignoreCase = true) -> "DTS:X"
            else -> "DTS"
        }
        id == "A_FLAC" || id == "fLaC" -> "FLAC"
        id == "A_OPUS" || id == "Opus" -> "Opus"
        id == "A_VORBIS" -> "Vorbis"
        id.startsWith("A_MPEG/L3") || id == ".mp3" -> "MP3"
        id.startsWith("A_MPEG") -> "MP2"
        id.startsWith("A_PCM") || id in setOf("lpcm", "sowt", "twos", "ipcm") -> "PCM"
        id == "S_TEXT/UTF8" -> "SRT"
        id == "S_TEXT/ASS" || id == "S_TEXT/SSA" || id == "S_ASS" || id == "S_SSA" -> "ASS"
        id == "S_HDMV/PGS" -> "PGS"
        id == "S_VOBSUB" -> "VobSub"
        id == "S_DVBSUB" -> "DVB"
        id == "S_TEXT/WEBVTT" || id == "wvtt" -> "WebVTT"
        id == "tx3g" || id == "text" -> "Texte"
        else -> id.substringAfter('_').substringBefore('/').ifEmpty { id }
    }
}

/** ISO 639-1 code of a Matroska or MP4 language ("fre", "fr-FR" → "fr"); null when undetermined. */
internal fun isoLanguage(code: String): String? {
    val base = code.trim().substringBefore('-').substringBefore('_').lowercase()
    if (base.isEmpty() || base == "und" || base == "zxx" || base == "mul") return null
    return if (base.length == 2) base else ISO_639_2[base] ?: base
}

private val ISO_639_2 = mapOf(
    "fre" to "fr", "fra" to "fr", "eng" to "en", "jpn" to "ja", "ger" to "de", "deu" to "de", "spa" to "es", "ita" to "it",
    "por" to "pt", "kor" to "ko", "chi" to "zh", "zho" to "zh", "rus" to "ru", "ara" to "ar", "dut" to "nl", "nld" to "nl",
    "pol" to "pl", "swe" to "sv", "nor" to "no", "nob" to "no", "dan" to "da", "fin" to "fi", "tur" to "tr", "hin" to "hi",
    "heb" to "he", "cze" to "cs", "ces" to "cs", "gre" to "el", "ell" to "el", "hun" to "hu", "tha" to "th", "ukr" to "uk",
    "vie" to "vi", "ind" to "id", "may" to "ms", "msa" to "ms", "rum" to "ro", "ron" to "ro", "cat" to "ca", "bul" to "bg",
    "hrv" to "hr", "srp" to "sr", "slv" to "sl", "slo" to "sk", "slk" to "sk", "ice" to "is", "isl" to "is", "per" to "fa", "fas" to "fa",
)
