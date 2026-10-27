package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakesTest {
    private fun item(position: Long, length: Long, source: Int = 1, offset: Long = 0) =
        Item(Ids.next(), sourceId = source, position = position, offset = offset, length = length)

    /** An item at 1000, 4000 long, holding takes of sources 1, 2 and 3, the second playing. */
    private fun threeTakes(): Item = Takes.withTakes(
        item(1000, 4000),
        listOf(Take(1, 100), Take(2, 5000, 0.5f), Take(3, 9000)),
        active = 1,
    )

    private fun Takes.Recorded.summary() = listOf(position, length, padBefore, padAfter) to offsets

    // ---------------------------------------------------------------- the take an item plays

    @Test
    fun theTakePlayingIsMirroredInTheItem() {
        val item = threeTakes()
        assertEquals(2, item.sourceId)
        assertEquals(5000, item.offset)
        assertEquals(0.5f, item.gain)
        assertEquals(3, Takes.count(item))
        assertEquals(1, Takes.active(item))
        // An edit that moves the mirror is what the take list reports.
        assertEquals(Take(2, 5100, 0.5f), Takes.all(item.copy(offset = 5100))[1])
    }

    @Test
    fun anItemWithOneTakeKeepsNoList() {
        val plain = item(0, 1000, source = 4, offset = 20)
        assertEquals(listOf(Take(4, 20)), Takes.all(plain))
        assertEquals(1, Takes.count(plain))
        val one = Takes.withTakes(plain, listOf(Take(7, 30)), active = 0)
        assertTrue(one.takes.isEmpty())
        assertEquals(7, one.sourceId)
        assertEquals(30, one.offset)
    }

    @Test
    fun selectAndStepGoRound() {
        val item = threeTakes()
        assertEquals(1, Takes.select(item, 0).sourceId)
        assertEquals(3, Takes.step(item, 1).sourceId)
        assertEquals(1, Takes.step(Takes.step(item, 1), 1).sourceId)
        assertEquals(1, Takes.step(item, -1).sourceId)
        assertEquals(3, Takes.step(Takes.select(item, 0), -1).sourceId)
        // Picking another take leaves the others as they were.
        assertEquals(Takes.all(item), Takes.all(Takes.select(Takes.select(item, 2), 1)))
    }

    @Test
    fun deletingATakePlaysTheNextOne() {
        val left = Takes.deleteActive(threeTakes())!!
        assertEquals(listOf(Take(1, 100), Take(3, 9000)), Takes.all(left))
        assertEquals(3, left.sourceId)
        // The last take goes: the one before it plays.
        val last = Takes.deleteActive(Takes.select(threeTakes(), 2))!!
        assertEquals(2, last.sourceId)
        // Down to one take, the item is a plain item again; the only take cannot go.
        val single = Takes.deleteActive(left)!!
        assertTrue(single.takes.isEmpty())
        assertEquals(1, single.sourceId)
        assertNull(Takes.deleteActive(single))
    }

    @Test
    fun cropKeepsOnlyTheTakePlaying() {
        val cropped = Takes.crop(threeTakes())
        assertTrue(cropped.takes.isEmpty())
        assertEquals(listOf(Take(2, 5000, 0.5f)), Takes.all(cropped))
    }

    @Test
    fun normalizeChangesOnlyTheTakePlaying() {
        val louder = Takes.withGain(threeTakes(), 2f)
        assertEquals(listOf(1f, 2f, 1f), Takes.all(louder).map { it.gain })
        assertEquals(2f, louder.gain)
        assertEquals(3f, Takes.withGain(item(0, 100), 3f).gain)
    }

    // ---------------------------------------------------------------- editing keeps every take in step

    @Test
    fun splittingKeepsEveryTakeInBothHalves() {
        val (left, right) = Edits.split(threeTakes(), 3000)!!
        assertEquals(Takes.all(threeTakes()), Takes.all(left))
        assertEquals(listOf(Take(1, 2100), Take(2, 7000, 0.5f), Take(3, 11000)), Takes.all(right))
        assertEquals(7000, right.offset)
        assertEquals(1, Takes.active(right))
    }

    @Test
    fun trimmingLeftMovesEveryTakeAndStopsAtTheEarliestSourceStart() {
        val trimmed = Edits.trimLeft(threeTakes(), 0)
        // Take 1 starts 100 frames into its source, so the edge can only go 100 frames back.
        assertEquals(900, trimmed.position)
        assertEquals(4100, trimmed.length)
        assertEquals(listOf(0L, 4900L, 8900L), Takes.all(trimmed).map { it.offset })
        val shorter = Edits.trimLeft(threeTakes(), 2000)
        assertEquals(listOf(1100L, 6000L, 10000L), Takes.all(shorter).map { it.offset })
    }

    @Test
    fun duplicatesAndCopiesKeepTheirTakes() {
        val copy = Edits.duplicate(threeTakes())
        assertEquals(Takes.all(threeTakes()), Takes.all(copy))
        assertEquals(5000, copy.position)
    }

    // ---------------------------------------------------------------- where a recording goes

    @Test
    fun aRecordingWithoutTheLoopIsOneTake() {
        // 1000 frames of latency: the take starts that far into what was captured.
        val r = Takes.layout(frames = 10_000, start = 5_000, lead = 1_000, loop = null)!!
        assertEquals(listOf(5_000L, 9_000L, 0L, 0L) to listOf(1_000L), r.summary())
    }

    @Test
    fun aRecordingShorterThanTheLeadIsNothing() {
        assertNull(Takes.layout(frames = 1_100, start = 0, lead = 1_000, loop = null))
        // Stopped during a count-in of 96000 frames.
        assertNull(Takes.layout(frames = 50_000, start = 0, lead = 96_000, loop = null))
    }

    @Test
    fun stoppingBeforeTheLoopEndIsOneTake() {
        val r = Takes.layout(frames = 11_000, start = 4_000, lead = 1_000, loop = 0L to 20_000L)!!
        assertEquals(listOf(4_000L, 10_000L, 0L, 0L) to listOf(1_000L), r.summary())
        // Starting after the loop end the transport never goes round, as in REAPER.
        val after = Takes.layout(frames = 50_000, start = 30_000, lead = 0, loop = 0L to 20_000L)!!
        assertEquals(listOf(30_000L, 50_000L, 0L, 0L) to listOf(0L), after.summary())
    }

    @Test
    fun eachWholePassRoundTheLoopIsATake() {
        // Three whole passes from the loop start, then a quarter of one, dropped as in REAPER.
        val r = Takes.layout(frames = 500 + 3 * 20_000 + 5_000L, start = 10_000, lead = 500, loop = 10_000L to 30_000L)!!
        assertEquals(listOf(10_000L, 20_000L, 0L, 0L) to listOf(500L, 20_500L, 40_500L), r.summary())
    }

    @Test
    fun aFirstPassStartedInsideTheLoopIsDroppedWhenAWholeOneFollows() {
        val r = Takes.layout(frames = 5_000 + 20_000 + 3_000L, start = 15_000, lead = 0, loop = 0L to 20_000L)!!
        assertEquals(listOf(0L, 20_000L, 0L, 0L) to listOf(5_000L), r.summary())
    }

    @Test
    fun startingBeforeTheLoopMakesTheFirstPassWhole() {
        val r = Takes.layout(frames = 6_000 + 10_000 + 10_000 + 2_000L, start = 4_000, lead = 0, loop = 10_000L to 20_000L)!!
        assertEquals(listOf(10_000L, 10_000L, 0L, 0L) to listOf(6_000L, 16_000L), r.summary())
    }

    @Test
    fun withNoWholePassThePartsArePaddedWithSilence() {
        // From 15000 to the loop end, then 8000 frames into the second pass.
        val r = Takes.layout(frames = 13_000, start = 15_000, lead = 0, loop = 0L to 20_000L)!!
        assertEquals(listOf(0L, 20_000L, 15_000L, 12_000L) to listOf(0L, 20_000L), r.summary())
        // The padded source holds both passes whole: 15000 + 13000 + 12000 frames.
        assertEquals(r.offsets.last() + r.length, r.padBefore + 13_000 + r.padAfter)
    }

    @Test
    fun aSliverOfAFirstPassIsNoTake() {
        val r = Takes.layout(frames = 20_100, start = 19_900, lead = 0, loop = 0L to 20_000L)!!
        assertEquals(listOf(0L, 20_000L, 0L, 0L) to listOf(100L), r.summary())
    }

    @Test
    fun aRecordingOverNothingIsAnItemOfItsOwn() {
        val rec = item(1000, 5000, source = 9)
        val (items, holding) = Takes.place(emptyList(), rec, keepOld = true)
        assertEquals(listOf(rec), items)
        assertEquals(setOf(rec.id), holding)
    }

    @Test
    fun aRecordingInsideAnItemBecomesItsNewTake() {
        val old = item(0, 10_000, source = 1)
        val rec = item(2_000, 4_000, source = 9, offset = 500)
        val (items, holding) = Takes.place(listOf(old), rec, keepOld = true)
        assertEquals(listOf(0L to 2_000L, 2_000L to 6_000L, 6_000L to 10_000L), items.map { it.position to it.end })
        val middle = items[1]
        assertEquals(setOf(middle.id), holding)
        assertEquals(listOf(Take(1, 2_000), Take(9, 500)), Takes.all(middle))
        // The new take plays; the old recording is one tap away.
        assertEquals(9, middle.sourceId)
        assertEquals(1, items[0].sourceId)
        assertEquals(1, items[2].sourceId)
        assertEquals(6_000, items[2].offset)
    }

    @Test
    fun aRecordingPastAnItemsEndIsATakeOverItAndAnItemAfterIt() {
        val old = item(0, 4_000, source = 1)
        val rec = item(2_000, 6_000, source = 9)
        val (items, holding) = Takes.place(listOf(old), rec, keepOld = true)
        assertEquals(listOf(0L to 2_000L, 2_000L to 4_000L, 4_000L to 8_000L), items.map { it.position to it.end })
        assertEquals(setOf(items[1].id, items[2].id), holding)
        assertEquals(listOf(Take(1, 2_000), Take(9, 0)), Takes.all(items[1]))
        // The rest of the recording carries on where the take over the item stopped.
        assertEquals(listOf(Take(9, 2_000)), Takes.all(items[2]))
    }

    @Test
    fun aRecordingBeforeAnItemsStartLinesItsTakeUp() {
        val old = item(6_000, 4_000, source = 1)
        val rec = item(2_000, 6_000, source = 9, offset = 100)
        val (items, holding) = Takes.place(listOf(old), rec, keepOld = true)
        assertEquals(listOf(2_000L to 6_000L, 6_000L to 8_000L, 8_000L to 10_000L), items.map { it.position to it.end })
        assertEquals(setOf(items[0].id, items[1].id), holding)
        assertEquals(listOf(Take(9, 100)), Takes.all(items[0]))
        // 4000 frames into the recording: its take starts that much further into the source.
        assertEquals(listOf(Take(1, 0), Take(9, 4_100)), Takes.all(items[1]))
        assertEquals(listOf(Take(1, 2_000)), Takes.all(items[2]))
    }

    @Test
    fun loopTakesJoinTheTakesAlreadyThere() {
        val old = Takes.withTakes(item(0, 20_000), listOf(Take(1, 0), Take(2, 0)), active = 0)
        val rec = Takes.withTakes(item(0, 20_000, source = 9), listOf(Take(9, 500), Take(9, 20_500), Take(9, 40_500)), active = 2)
        val (items, holding) = Takes.place(listOf(old), rec, keepOld = true)
        val only = items.single()
        assertEquals(setOf(only.id), holding)
        assertEquals(listOf(Take(1, 0), Take(2, 0), Take(9, 500), Take(9, 20_500), Take(9, 40_500)), Takes.all(only))
        // The newest pass plays.
        assertEquals(4, Takes.active(only))
        assertEquals(40_500, only.offset)
    }

    @Test
    fun tapeModeReplacesWhatTheRecordingCovers() {
        val old = item(0, 10_000, source = 1)
        val rec = item(2_000, 4_000, source = 9)
        val (items, holding) = Takes.place(listOf(old), rec, keepOld = false)
        assertEquals(listOf(0L to 2_000L, 2_000L to 6_000L, 6_000L to 10_000L), items.map { it.position to it.end })
        assertEquals(listOf(1, 9, 1), items.map { it.sourceId })
        assertEquals(setOf(rec.id), holding)
        assertTrue(items.all { Takes.count(it) == 1 })
    }

    @Test
    fun aRecordingAcrossTwoItemsFillsTheGapBetweenThem() {
        val a = item(0, 3_000, source = 1)
        val b = item(5_000, 3_000, source = 2)
        val rec = item(1_000, 6_000, source = 9)
        val (items, holding) = Takes.place(listOf(a, b), rec, keepOld = true)
        assertEquals(
            listOf(0L to 1_000L, 1_000L to 3_000L, 3_000L to 5_000L, 5_000L to 7_000L, 7_000L to 8_000L),
            items.map { it.position to it.end },
        )
        assertEquals(setOf(items[1].id, items[2].id, items[3].id), holding)
        assertEquals(listOf(Take(1, 1_000), Take(9, 0)), Takes.all(items[1]))
        assertEquals(listOf(Take(9, 2_000)), Takes.all(items[2]))
        assertEquals(listOf(Take(2, 0), Take(9, 4_000)), Takes.all(items[3]))
    }
}
