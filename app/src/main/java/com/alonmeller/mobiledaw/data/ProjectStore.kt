package com.alonmeller.mobiledaw.data

import com.alonmeller.mobiledaw.model.Effect
import com.alonmeller.mobiledaw.model.EffectType
import com.alonmeller.mobiledaw.model.EnvPoint
import com.alonmeller.mobiledaw.model.Ids
import com.alonmeller.mobiledaw.model.Instrument
import com.alonmeller.mobiledaw.model.InstrumentType
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.Marker
import com.alonmeller.mobiledaw.model.Note
import com.alonmeller.mobiledaw.model.PAD_COUNT
import com.alonmeller.mobiledaw.model.Pad
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.Routing
import com.alonmeller.mobiledaw.model.Send
import com.alonmeller.mobiledaw.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings saved with the project but outside undo. */
data class ProjectSettings(
    val metronome: Boolean = false,
    val snap: Boolean = true,
    val gridDivision: Int = 4,
    /** Pulls notes played live onto the nearest 1/16 while recording. */
    val quantizeRecording: Boolean = true,
)

data class LoadedProject(val project: Project, val settings: ProjectSettings)

/**
 * Stores one song in [dir] as project.json plus one WAV file per audio
 * source. Items and drum pads refer to sources by file name on disk and by
 * engine source id in memory.
 */
class ProjectStore(val dir: File) {
    private val projectFile = File(dir, "project.json")

    init {
        dir.mkdirs()
    }

    fun audioFile(fileName: String) = File(dir, fileName)

    /**
     * Reads project.json. [loadSource] turns a WAV file name into an engine
     * source id (or null when the file is unreadable); frames are rescaled
     * when the saved sample rate differs from [sampleRate].
     */
    fun load(sampleRate: Int, loadSource: (String) -> Int?): LoadedProject? {
        if (!projectFile.exists()) return null
        return runCatching {
            val json = JSONObject(projectFile.readText())
            val savedRate = json.optInt("sampleRate", sampleRate)
            fun frames(value: Long) = if (savedRate == sampleRate) value else value * sampleRate / savedRate
            val sourceIds = mutableMapOf<String, Int?>()
            fun source(file: String) = sourceIds.getOrPut(file) { loadSource(file) }

            val arr = json.getJSONArray("tracks")
            // Track ids are new on every load, so sends name their track by its place in the list.
            val ids = List(arr.length()) { Ids.next() }
            val tracks = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val items = mutableListOf<Item>()
                val itemsJson = t.optJSONArray("items")
                if (itemsJson != null) {
                    for (j in 0 until itemsJson.length()) {
                        val it = itemsJson.getJSONObject(j)
                        if (it.optBoolean("midi", false)) {
                            items += Item(
                                id = Ids.next(),
                                sourceId = -1,
                                position = frames(it.getLong("position")),
                                offset = frames(it.optLong("offset", 0)),
                                length = frames(it.getLong("length")),
                                mute = it.optBoolean("mute", false),
                                notes = readNotes(it.optJSONArray("notes"), ::frames),
                                loopLength = frames(it.optLong("loop", 0)),
                            )
                            continue
                        }
                        val src = source(it.getString("file")) ?: continue
                        items += Item(
                            id = Ids.next(),
                            sourceId = src,
                            position = frames(it.getLong("position")),
                            offset = frames(it.optLong("offset", 0)),
                            length = frames(it.getLong("length")),
                            fadeIn = frames(it.optLong("fadeIn", 0)),
                            fadeOut = frames(it.optLong("fadeOut", 0)),
                            gain = it.optDouble("gain", 1.0).toFloat(),
                            mute = it.optBoolean("mute", false),
                        )
                    }
                } else if (t.has("file")) {
                    // Version 1: one clip per track.
                    val file = t.getString("file")
                    if (audioFile(file).exists()) {
                        source(file)?.let { src ->
                            items += Item(
                                id = Ids.next(),
                                sourceId = src,
                                position = frames(t.optLong("start", 0)),
                                offset = 0,
                                length = sourceLengthOf(src),
                            )
                        }
                    }
                }
                Track(
                    id = ids[i],
                    name = t.getString("name"),
                    colorIndex = t.optInt("color", i),
                    gain = t.optDouble("gain", 1.0).toFloat(),
                    pan = t.optDouble("pan", 0.0).toFloat(),
                    mute = t.optBoolean("mute", false),
                    solo = t.optBoolean("solo", false),
                    items = items,
                    fx = readEffects(t.optJSONArray("fx")),
                    volumeEnv = readEnvelope(t.optJSONArray("volumeEnv"), ::frames),
                    panEnv = readEnvelope(t.optJSONArray("panEnv"), ::frames),
                    instrument = t.optJSONObject("instrument")?.let { readInstrument(it, ::frames, ::source) },
                    indent = t.optInt("indent", 0),
                    collapsed = t.optBoolean("collapsed", false),
                    sends = readSends(t.optJSONArray("sends"), ids),
                )
            }
            val markers = json.optJSONArray("markers")?.let { m ->
                (0 until m.length()).map { k ->
                    val o = m.getJSONObject(k)
                    Marker(Ids.next(), frames(o.getLong("position")), o.optString("name", "${k + 1}"))
                }
            } ?: emptyList()
            val loop = json.optJSONObject("loop")
            val project = Routing.normalize(Project(
                tracks = tracks,
                markers = markers,
                tempo = json.optDouble("tempo", 120.0),
                loopStart = frames(loop?.optLong("start", 0) ?: 0L),
                loopEnd = frames(loop?.optLong("end", 0) ?: 0L),
                loopOn = loop?.optBoolean("on", false) ?: false,
                masterFx = readEffects(json.optJSONArray("masterFx")),
                masterGain = json.optDouble("masterGain", 1.0).toFloat(),
            ))
            val settings = ProjectSettings(
                metronome = json.optBoolean("metronome", false),
                snap = json.optBoolean("snap", true),
                gridDivision = json.optInt("grid", 4),
                quantizeRecording = json.optBoolean("quantize", true),
            )
            LoadedProject(project, settings)
        }.getOrNull()
    }

    /** Set by the caller so version-1 projects can size their single item. */
    var sourceLengthOf: (Int) -> Long = { 0L }

    @Synchronized
    fun save(project: Project, settings: ProjectSettings, sampleRate: Int, fileOf: (Int) -> String?) {
        val indexOf = project.tracks.withIndex().associate { it.value.id to it.index }
        val tracks = JSONArray()
        project.tracks.forEach { t ->
            val items = JSONArray()
            t.items.forEach { item ->
                if (item.isMidi) {
                    items.put(
                        JSONObject()
                            .put("midi", true)
                            .put("position", item.position)
                            .put("offset", item.offset)
                            .put("length", item.length)
                            .put("loop", item.loopLength)
                            .put("mute", item.mute)
                            .put("notes", writeNotes(item.notes ?: emptyList()))
                    )
                    return@forEach
                }
                val file = fileOf(item.sourceId) ?: return@forEach
                items.put(
                    JSONObject()
                        .put("file", file)
                        .put("position", item.position)
                        .put("offset", item.offset)
                        .put("length", item.length)
                        .put("fadeIn", item.fadeIn)
                        .put("fadeOut", item.fadeOut)
                        .put("gain", item.gain.toDouble())
                        .put("mute", item.mute)
                )
            }
            tracks.put(
                JSONObject()
                    .put("name", t.name)
                    .put("color", t.colorIndex)
                    .put("gain", t.gain.toDouble())
                    .put("pan", t.pan.toDouble())
                    .put("mute", t.mute)
                    .put("solo", t.solo)
                    .put("items", items)
                    .put("fx", writeEffects(t.fx))
                    .put("volumeEnv", writeEnvelope(t.volumeEnv))
                    .put("panEnv", writeEnvelope(t.panEnv))
                    .put("indent", t.indent)
                    .put("collapsed", t.collapsed)
                    .put("sends", writeSends(t.sends, indexOf))
                    .apply { t.instrument?.let { put("instrument", writeInstrument(it, fileOf)) } }
            )
        }
        val markers = JSONArray()
        project.markers.forEach { markers.put(JSONObject().put("position", it.position).put("name", it.name)) }
        val json = JSONObject()
            .put("version", 5)
            .put("tempo", project.tempo)
            .put("sampleRate", sampleRate)
            .put("metronome", settings.metronome)
            .put("snap", settings.snap)
            .put("grid", settings.gridDivision)
            .put("quantize", settings.quantizeRecording)
            .put("loop", JSONObject().put("start", project.loopStart).put("end", project.loopEnd).put("on", project.loopOn))
            .put("markers", markers)
            .put("masterGain", project.masterGain.toDouble())
            .put("masterFx", writeEffects(project.masterFx))
            .put("tracks", tracks)
        // A song deleted while this save was on its way stays deleted.
        if (!dir.isDirectory) return
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(projectFile)
        ProjectMeta.update(dir) { it.copy(seconds = project.endFrame.toDouble() / sampleRate) }
    }

    private fun readEffects(arr: JSONArray?): List<Effect> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = EffectType.fromCode(o.optInt("type", -1)) ?: return@mapNotNull null
            val saved = o.optJSONArray("values")
            // Parameters added in later versions fall back to their defaults.
            val values = type.params.mapIndexed { k, spec ->
                if (saved != null && k < saved.length()) saved.getDouble(k).toFloat() else spec.default
            }
            Effect(Ids.next(), type, values, o.optBoolean("bypass", false))
        }
    }

    private fun writeEffects(effects: List<Effect>): JSONArray {
        val arr = JSONArray()
        effects.forEach { e ->
            val values = JSONArray()
            e.values.forEach { values.put(it.toDouble()) }
            arr.put(JSONObject().put("type", e.type.code).put("values", values).put("bypass", e.bypass))
        }
        return arr
    }

    private fun readSends(arr: JSONArray?, ids: List<Long>): List<Send> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val target = ids.getOrNull(o.optInt("to", -1)) ?: return@mapNotNull null
            Send(
                id = Ids.next(),
                target = target,
                gain = o.optDouble("gain", 0.5).toFloat(),
                preFader = o.optBoolean("pre", false),
                mute = o.optBoolean("mute", false),
            )
        }
    }

    private fun writeSends(sends: List<Send>, indexOf: Map<Long, Int>): JSONArray {
        val arr = JSONArray()
        sends.forEach { s ->
            val to = indexOf[s.target] ?: return@forEach
            arr.put(JSONObject().put("to", to).put("gain", s.gain.toDouble()).put("pre", s.preFader).put("mute", s.mute))
        }
        return arr
    }

    private fun readEnvelope(arr: JSONArray?, frames: (Long) -> Long): List<EnvPoint> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            EnvPoint(frames(p.getLong(0)), p.getDouble(1).toFloat())
        }.sortedBy { it.frame }
    }

    private fun writeEnvelope(points: List<EnvPoint>): JSONArray {
        val arr = JSONArray()
        points.forEach { arr.put(JSONArray().put(it.frame).put(it.value.toDouble())) }
        return arr
    }

    private fun readNotes(arr: JSONArray?, frames: (Long) -> Long): List<Note> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val n = arr.getJSONArray(i)
            Note(frames(n.getLong(0)), frames(n.getLong(1)).coerceAtLeast(1), n.getInt(2), n.optDouble(3, 0.8).toFloat())
        }.sortedWith(compareBy({ it.start }, { it.pitch }))
    }

    private fun writeNotes(notes: List<Note>): JSONArray {
        val arr = JSONArray()
        notes.forEach { arr.put(JSONArray().put(it.start).put(it.length).put(it.pitch).put(it.velocity.toDouble())) }
        return arr
    }

    private fun readInstrument(o: JSONObject, frames: (Long) -> Long, source: (String) -> Int?): Instrument? {
        val type = InstrumentType.fromCode(o.optInt("type", -1)) ?: return null
        val saved = o.optJSONArray("values")
        val values = type.params.mapIndexed { k, spec ->
            if (saved != null && k < saved.length()) saved.getDouble(k).toFloat() else spec.default
        }
        val padsJson = o.optJSONArray("pads")
        val pads = if (type != InstrumentType.DRUMS) emptyList() else List(PAD_COUNT) { i ->
            val p = padsJson?.optJSONObject(i) ?: return@List Pad()
            val file = p.optString("file", "")
            val src = if (file.isEmpty() || !audioFile(file).exists()) null else source(file)
            Pad(
                sourceId = src ?: -1,
                name = if (src == null) "" else p.optString("name", ""),
                gain = p.optDouble("gain", 1.0).toFloat(),
                pitch = p.optDouble("pitch", 0.0).toFloat(),
                start = frames(p.optLong("start", 0)),
                end = frames(p.optLong("end", 0)),
                reverse = p.optBoolean("reverse", false),
                oneShot = p.optBoolean("oneShot", true),
                chokeGroup = p.optInt("choke", 0),
            )
        }
        return Instrument(Ids.next(), type, values, pads)
    }

    private fun writeInstrument(inst: Instrument, fileOf: (Int) -> String?): JSONObject {
        val values = JSONArray()
        inst.values.forEach { values.put(it.toDouble()) }
        val pads = JSONArray()
        inst.pads.forEach { pad ->
            pads.put(
                JSONObject()
                    .put("file", fileOf(pad.sourceId) ?: "")
                    .put("name", pad.name)
                    .put("gain", pad.gain.toDouble())
                    .put("pitch", pad.pitch.toDouble())
                    .put("start", pad.start)
                    .put("end", pad.end)
                    .put("reverse", pad.reverse)
                    .put("oneShot", pad.oneShot)
                    .put("choke", pad.chokeGroup)
            )
        }
        return JSONObject().put("type", inst.type.code).put("values", values).put("pads", pads)
    }

    /** Deletes WAV files that no saved item or pad refers to any more. */
    fun deleteUnused(used: Set<String>) {
        dir.listFiles { f -> f.extension == "wav" && f.name !in used }?.forEach { it.delete() }
    }
}
