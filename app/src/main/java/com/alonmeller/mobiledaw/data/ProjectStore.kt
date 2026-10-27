package com.alonmeller.mobiledaw.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved description of one track; its audio lives in [fileName] next to project.json. */
data class SavedTrack(
    val fileName: String,
    val name: String,
    val colorIndex: Int,
    val startFrame: Long,
    val gain: Float,
    val pan: Float,
    val mute: Boolean,
    val solo: Boolean,
)

data class SavedProject(
    val tempo: Double,
    val metronome: Boolean,
    val sampleRate: Int,
    val tracks: List<SavedTrack>,
)

/** Stores the current project as project.json plus one WAV file per track. */
class ProjectStore(root: File) {
    val dir: File = File(root, "projects/default").apply { mkdirs() }
    private val projectFile = File(dir, "project.json")

    fun audioFile(fileName: String) = File(dir, fileName)

    fun load(): SavedProject? {
        if (!projectFile.exists()) return null
        return runCatching {
            val json = JSONObject(projectFile.readText())
            val arr = json.getJSONArray("tracks")
            val tracks = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                SavedTrack(
                    fileName = t.getString("file"),
                    name = t.getString("name"),
                    colorIndex = t.optInt("color", i),
                    startFrame = t.optLong("start", 0),
                    gain = t.optDouble("gain", 1.0).toFloat(),
                    pan = t.optDouble("pan", 0.0).toFloat(),
                    mute = t.optBoolean("mute", false),
                    solo = t.optBoolean("solo", false),
                )
            }
            SavedProject(
                tempo = json.optDouble("tempo", 120.0),
                metronome = json.optBoolean("metronome", false),
                sampleRate = json.optInt("sampleRate", 48000),
                tracks = tracks,
            )
        }.getOrNull()
    }

    fun save(project: SavedProject) {
        val arr = JSONArray()
        project.tracks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("file", t.fileName)
                    .put("name", t.name)
                    .put("color", t.colorIndex)
                    .put("start", t.startFrame)
                    .put("gain", t.gain.toDouble())
                    .put("pan", t.pan.toDouble())
                    .put("mute", t.mute)
                    .put("solo", t.solo)
            )
        }
        val json = JSONObject()
            .put("version", 1)
            .put("tempo", project.tempo)
            .put("metronome", project.metronome)
            .put("sampleRate", project.sampleRate)
            .put("tracks", arr)
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(projectFile)
    }
}
