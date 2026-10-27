package com.alonmeller.mobiledaw.data

import com.alonmeller.mobiledaw.model.Ids
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.Marker
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings saved with the project but outside undo. */
data class ProjectSettings(
    val metronome: Boolean = false,
    val snap: Boolean = true,
    val gridDivision: Int = 4,
)

data class LoadedProject(val project: Project, val settings: ProjectSettings)

/**
 * Stores the project as project.json plus one WAV file per audio source.
 * Items refer to sources by file name on disk and by engine source id in memory.
 */
class ProjectStore(root: File) {
    val dir: File = File(root, "projects/default").apply { mkdirs() }
    private val projectFile = File(dir, "project.json")

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
            val tracks = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val items = mutableListOf<Item>()
                val itemsJson = t.optJSONArray("items")
                if (itemsJson != null) {
                    for (j in 0 until itemsJson.length()) {
                        val it = itemsJson.getJSONObject(j)
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
                    id = Ids.next(),
                    name = t.getString("name"),
                    colorIndex = t.optInt("color", i),
                    gain = t.optDouble("gain", 1.0).toFloat(),
                    pan = t.optDouble("pan", 0.0).toFloat(),
                    mute = t.optBoolean("mute", false),
                    solo = t.optBoolean("solo", false),
                    items = items,
                )
            }
            val markers = json.optJSONArray("markers")?.let { m ->
                (0 until m.length()).map { k ->
                    val o = m.getJSONObject(k)
                    Marker(Ids.next(), frames(o.getLong("position")), o.optString("name", "${k + 1}"))
                }
            } ?: emptyList()
            val loop = json.optJSONObject("loop")
            val project = Project(
                tracks = tracks,
                markers = markers,
                tempo = json.optDouble("tempo", 120.0),
                loopStart = frames(loop?.optLong("start", 0) ?: 0L),
                loopEnd = frames(loop?.optLong("end", 0) ?: 0L),
                loopOn = loop?.optBoolean("on", false) ?: false,
            )
            val settings = ProjectSettings(
                metronome = json.optBoolean("metronome", false),
                snap = json.optBoolean("snap", true),
                gridDivision = json.optInt("grid", 4),
            )
            LoadedProject(project, settings)
        }.getOrNull()
    }

    /** Set by the caller so version-1 projects can size their single item. */
    var sourceLengthOf: (Int) -> Long = { 0L }

    @Synchronized
    fun save(project: Project, settings: ProjectSettings, sampleRate: Int, fileOf: (Int) -> String?) {
        val tracks = JSONArray()
        project.tracks.forEach { t ->
            val items = JSONArray()
            t.items.forEach { item ->
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
            )
        }
        val markers = JSONArray()
        project.markers.forEach { markers.put(JSONObject().put("position", it.position).put("name", it.name)) }
        val json = JSONObject()
            .put("version", 2)
            .put("tempo", project.tempo)
            .put("sampleRate", sampleRate)
            .put("metronome", settings.metronome)
            .put("snap", settings.snap)
            .put("grid", settings.gridDivision)
            .put("loop", JSONObject().put("start", project.loopStart).put("end", project.loopEnd).put("on", project.loopOn))
            .put("markers", markers)
            .put("tracks", tracks)
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(projectFile)
    }

    /** Deletes WAV files that no saved item refers to any more. */
    fun deleteUnused(used: Set<String>) {
        dir.listFiles { f -> f.extension == "wav" && f.name !in used }?.forEach { it.delete() }
    }
}
