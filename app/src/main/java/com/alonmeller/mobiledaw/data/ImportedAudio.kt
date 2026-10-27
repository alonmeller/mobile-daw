package com.alonmeller.mobiledaw.data

/** An audio file decoded to mono, ready to become a track item or a pad sample. */
class ImportedAudio(val name: String, val samples: FloatArray, val sampleRate: Int)
