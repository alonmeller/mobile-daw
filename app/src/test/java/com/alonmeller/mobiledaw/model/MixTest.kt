package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MixTest {
    @Test
    fun envelopeInterpolates() {
        val env = listOf(EnvPoint(0, 0f), EnvPoint(100, 1f))
        assertEquals(0.5f, Envelopes.valueAt(env, 50, 9f), 1e-6f)
        assertEquals(0f, Envelopes.valueAt(env, -10, 9f), 1e-6f)
        assertEquals(1f, Envelopes.valueAt(env, 500, 9f), 1e-6f)
        assertEquals(9f, Envelopes.valueAt(emptyList(), 5, 9f), 1e-6f)
    }

    @Test
    fun envelopeAddKeepsOrderAndReplaces() {
        var env = Envelopes.add(emptyList(), EnvPoint(200, 1f))
        env = Envelopes.add(env, EnvPoint(100, 0.5f))
        env = Envelopes.add(env, EnvPoint(200, 0.2f))
        assertEquals(listOf(EnvPoint(100, 0.5f), EnvPoint(200, 0.2f)), env)
    }

    @Test
    fun envelopeMoveStaysBetweenNeighbours() {
        val env = listOf(EnvPoint(0, 0f), EnvPoint(100, 1f), EnvPoint(200, 0f))
        val moved = Envelopes.move(env, 1, 500, 0.3f)
        assertEquals(EnvPoint(199, 0.3f), moved[1])
        assertEquals(EnvPoint(1, 0.3f), Envelopes.move(env, 1, -50, 0.3f)[1])
    }

    @Test
    fun fxChainForTrackAndMaster() {
        val reverb = Effect(1, EffectType.REVERB)
        val eq = Effect(2, EffectType.EQ)
        val p = Project(tracks = listOf(Track(10, "A", 0)))
            .withFxChain(10, listOf(eq))
            .withFxChain(MASTER, listOf(reverb))
        assertEquals(listOf(eq), p.fxChain(10))
        assertEquals(listOf(reverb), p.fxChain(MASTER))
        assertEquals(2, p.allEffects.size)
    }

    @Test
    fun logParamRoundTrips() {
        val spec = EffectType.DELAY.params[0]
        val pos = spec.toPosition(375f)
        assertEquals(375f, spec.fromPosition(pos), 0.01f)
        assertEquals(spec.min, spec.fromPosition(0f), 1e-4f)
        assertEquals(spec.max, spec.fromPosition(1f), 0.01f)
    }

    @Test
    fun faderScaleRoundTrips() {
        assertEquals(1f, Scales.positionToGain(Scales.gainToPosition(1f)), 1e-4f)
        assertEquals(0f, Scales.positionToGain(0f), 0f)
        assertEquals(-1f, Scales.positionToPan(Scales.panToPosition(-1f)), 1e-6f)
    }

    @Test
    fun effectCodesAreUnique() {
        assertEquals(EffectType.entries.size, EffectType.entries.map { it.code }.toSet().size)
        EffectType.entries.forEach { assertEquals(it, EffectType.fromCode(it.code)) }
    }
}
