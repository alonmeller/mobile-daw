package com.alonmeller.mobiledaw.data

import java.io.File
import java.util.Properties

/** A song in the list: its folder name, name, last change and length. */
data class ProjectInfo(val id: String, val name: String, val modified: Long, val seconds: Double)

/** Name and length of a song, kept in meta.properties so listing songs never opens a project. */
data class ProjectMeta(val name: String, val seconds: Double = 0.0) {
    companion object {
        private const val FILE = "meta.properties"

        @Synchronized
        fun read(dir: File): ProjectMeta? = runCatching {
            val file = File(dir, FILE)
            if (!file.exists()) return@runCatching null
            val props = Properties()
            file.reader(Charsets.UTF_8).use { props.load(it) }
            ProjectMeta(
                name = props.getProperty("name")?.takeIf { it.isNotBlank() } ?: ProjectLibrary.DEFAULT_NAME,
                seconds = props.getProperty("seconds")?.toDoubleOrNull() ?: 0.0,
            )
        }.getOrNull()

        @Synchronized
        fun write(dir: File, meta: ProjectMeta) {
            val props = Properties()
            props.setProperty("name", meta.name)
            props.setProperty("seconds", meta.seconds.toString())
            val tmp = File(dir, "$FILE.tmp")
            tmp.writer(Charsets.UTF_8).use { props.store(it, null) }
            tmp.renameTo(File(dir, FILE))
        }

        /** Changes the meta of the song in [dir], starting from a nameless one when there is none. */
        @Synchronized
        fun update(dir: File, change: (ProjectMeta) -> ProjectMeta) {
            write(dir, change(read(dir) ?: ProjectMeta(ProjectLibrary.DEFAULT_NAME)))
        }
    }
}

/**
 * The songs on the phone, like GarageBand's "My Songs": each is a folder
 * under projects/ holding its project.json, its WAV files and its meta.
 */
class ProjectLibrary(root: File) {
    val dir: File = File(root, "projects").apply { mkdirs() }
    private val currentFile = File(dir, "current.txt")

    /** Every song, the most recently changed first. */
    fun list(): List<ProjectInfo> =
        (dir.listFiles { f -> f.isDirectory && isValidId(f.name) } ?: emptyArray())
            .map { info(it) }
            .sortedByDescending { it.modified }

    private fun info(folder: File): ProjectInfo {
        val meta = ProjectMeta.read(folder)
        val modified = maxOf(File(folder, "project.json").lastModified(), File(folder, "meta.properties").lastModified())
        return ProjectInfo(
            id = folder.name,
            name = meta?.name ?: DEFAULT_NAME,
            modified = if (modified > 0) modified else folder.lastModified(),
            seconds = meta?.seconds ?: 0.0,
        )
    }

    /** Bytes the songs take on the phone. */
    fun usedBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Bytes still free where the songs are kept. */
    fun freeBytes(): Long = dir.usableSpace

    /** The song to open: the one open last time, else the newest, else a new one. */
    fun current(): String {
        val saved = runCatching { currentFile.readText().trim() }.getOrNull()
        if (saved != null && isValidId(saved) && File(dir, saved).isDirectory) return saved
        val id = list().firstOrNull()?.id ?: create(DEFAULT_NAME)
        setCurrent(id)
        return id
    }

    fun setCurrent(id: String) {
        currentFile.writeText(id)
    }

    fun folder(id: String): File {
        require(isValidId(id)) { "bad song id $id" }
        return File(dir, id)
    }

    /** Makes an empty song named [name] (or "name 2" etc. when taken) and returns its id. */
    fun create(name: String): String {
        // Named before its folder exists, so the new folder never counts as taking the name.
        val unique = uniqueName(name)
        val id = newId()
        val folder = File(dir, id).apply { mkdirs() }
        ProjectMeta.write(folder, ProjectMeta(unique))
        return id
    }

    fun rename(id: String, name: String) {
        ProjectMeta.update(folder(id)) { it.copy(name = name) }
    }

    /** Copies a song with all its audio; returns the copy's id, or null when the copy failed. */
    fun duplicate(id: String): String? {
        val source = folder(id)
        if (!source.isDirectory) return null
        val name = uniqueName((ProjectMeta.read(source)?.name ?: DEFAULT_NAME) + " copy")
        val copyId = newId()
        val target = File(dir, copyId)
        val copied = runCatching { source.copyRecursively(target) }.getOrDefault(false)
        if (!copied) {
            target.deleteRecursively()
            return null
        }
        ProjectMeta.update(target) { it.copy(name = name) }
        return copyId
    }

    /** Deletes a song and its audio for good. */
    fun delete(id: String) {
        if (isValidId(id)) File(dir, id).deleteRecursively()
    }

    /** [base], or "base 2", "base 3", ... whichever no song is called yet. */
    fun uniqueName(base: String): String {
        val taken = list().map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }

    private fun newId(): String {
        val stamp = System.currentTimeMillis()
        var id = "song_$stamp"
        var n = 1
        while (File(dir, id).exists()) id = "song_${stamp}_${n++}"
        return id
    }

    companion object {
        const val DEFAULT_NAME = "My Song"

        /** Song ids are folder names the app made: letters, digits, '_' and '-' only. */
        fun isValidId(id: String) =
            id.isNotEmpty() && id.length <= 64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    }
}
