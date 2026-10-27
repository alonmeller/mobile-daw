package com.alonmeller.mobiledaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun library() = ProjectLibrary(tmp.root)

    @Test
    fun aNewPhoneStartsWithOneSong() {
        val lib = library()
        val id = lib.current()
        assertEquals(listOf(ProjectLibrary.DEFAULT_NAME), lib.list().map { it.name })
        assertEquals(id, lib.list().single().id)
        // The same song opens next time.
        assertEquals(id, library().current())
    }

    @Test
    fun songsFromBeforeTheSongListStillOpen() {
        // Earlier versions kept the one song in projects/default, with no meta.
        val old = File(tmp.root, "projects/default").apply { mkdirs() }
        File(old, "project.json").writeText("{}")
        val lib = library()
        assertEquals("default", lib.current())
        assertEquals(ProjectLibrary.DEFAULT_NAME, lib.list().single().name)
    }

    @Test
    fun namesStayUnique() {
        val lib = library()
        lib.create("Demo")
        lib.create("demo")
        lib.create("Demo")
        assertEquals(setOf("Demo", "demo 2", "Demo 3"), lib.list().map { it.name }.toSet())
    }

    @Test
    fun renameDuplicateAndDelete() {
        val lib = library()
        val id = lib.create("Demo")
        File(lib.folder(id), "project.json").writeText("{\"tempo\": 90}")
        File(lib.folder(id), "rec_1.wav").writeBytes(ByteArray(100) { it.toByte() })

        lib.rename(id, "Summer")
        assertEquals("Summer", lib.list().single { it.id == id }.name)

        val copy = lib.duplicate(id)
        assertNotNull(copy)
        assertNotEquals(id, copy)
        val copied = lib.list().single { it.id == copy }
        assertEquals("Summer copy", copied.name)
        assertEquals("{\"tempo\": 90}", File(lib.folder(copy!!), "project.json").readText())
        assertEquals(100L, File(lib.folder(copy), "rec_1.wav").length())

        lib.delete(id)
        assertFalse(lib.folder(id).exists())
        assertEquals(listOf(copy), lib.list().map { it.id })
    }

    @Test
    fun theNewestSongComesFirst() {
        val lib = library()
        val a = lib.create("A")
        val b = lib.create("B")
        File(lib.folder(a), "meta.properties").setLastModified(2_000_000_000_000)
        File(lib.folder(b), "meta.properties").setLastModified(1_000_000_000_000)
        assertEquals(listOf(a, b), lib.list().map { it.id })
        // With nothing saved last time, the newest one opens.
        File(lib.dir, "current.txt").delete()
        assertEquals(a, lib.current())
    }

    @Test
    fun theOpenSongIsRemembered() {
        val lib = library()
        val a = lib.create("A")
        val b = lib.create("B")
        lib.setCurrent(a)
        assertEquals(a, library().current())
        lib.setCurrent(b)
        assertEquals(b, library().current())
        // A song that is gone, or a name that is not a song folder, is not opened.
        lib.delete(b)
        assertEquals(a, library().current())
        File(lib.dir, "current.txt").writeText("../escape")
        assertEquals(a, library().current())
    }

    @Test
    fun onlyFolderNamesTheAppMadeAreSongs() {
        assertTrue(ProjectLibrary.isValidId("song_1700000000000"))
        assertTrue(ProjectLibrary.isValidId("default"))
        assertFalse(ProjectLibrary.isValidId(""))
        assertFalse(ProjectLibrary.isValidId(".."))
        assertFalse(ProjectLibrary.isValidId("a/b"))
        assertFalse(ProjectLibrary.isValidId("x".repeat(65)))
        val lib = library()
        File(lib.dir, "not a song").mkdirs()
        assertTrue(lib.list().none { it.id == "not a song" })
        assertTrue(runCatching { lib.folder("../x") }.isFailure)
    }

    @Test
    fun metaKeepsNameAndLength() {
        val dir = tmp.newFolder("song")
        ProjectMeta.update(dir) { it.copy(seconds = 61.5) }
        assertEquals(ProjectMeta(ProjectLibrary.DEFAULT_NAME, 61.5), ProjectMeta.read(dir))
        ProjectMeta.update(dir) { it.copy(name = "שיר = חדש: \"1\"") }
        assertEquals(ProjectMeta("שיר = חדש: \"1\"", 61.5), ProjectMeta.read(dir))
    }
}
