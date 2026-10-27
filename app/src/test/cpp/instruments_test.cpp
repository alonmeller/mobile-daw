// Host-side checks of the synth and drum sampler; built and run by CI with the system compiler.
#include "Instruments.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s\n", msg); ++failures; } else std::printf("ok: %s\n", msg); } while (0)

static const int SR = 48000;

std::vector<float> render(Instrument &inst, int frames) {
    std::vector<float> out(static_cast<size_t>(frames), 0.0f);
    for (int i = 0; i < frames; i += 256) inst.render(out.data() + i, std::min(256, frames - i));
    return out;
}

float peak(const std::vector<float> &v, size_t from = 0, size_t to = SIZE_MAX) {
    float p = 0;
    to = std::min(to, v.size());
    for (size_t i = from; i < to; ++i) p = std::max(p, std::fabs(v[i]));
    return p;
}

bool finite(const std::vector<float> &v) {
    for (float x : v) if (!std::isfinite(x)) return false;
    return true;
}

int zeroCrossings(const std::vector<float> &v, size_t from, size_t to) {
    int c = 0;
    for (size_t i = from + 1; i < to; ++i) if ((v[i - 1] < 0) != (v[i] < 0)) ++c;
    return c;
}

std::shared_ptr<Instrument> sineSynth() {
    auto s = Instrument::create(InstrumentKind::Synth, SR);
    s->setParam(0, 0);      // sine
    s->setParam(1, 1);      // attack 1 ms
    s->setParam(3, 1);      // sustain full
    s->setParam(4, 50);     // release 50 ms
    s->setParam(5, 18000);  // filter open
    s->setParam(6, 0);
    s->setParam(7, 0);
    s->setParam(8, 0);      // no detune
    return s;
}

int main() {
    const std::vector<PadConfig> noPads;

    // Synth plays A4 at 440 Hz for the note's length, then releases to silence.
    { auto s = sineSynth();
      s->noteOn(noPads, 69, 1.0f, 0, SR / 2);
      auto out = render(*s, SR);
      int zc = zeroCrossings(out, SR / 10, SR / 10 + SR / 4);  // a quarter second
      std::printf("  zero crossings in 0.25 s: %d\n", zc);
      CHECK(zc >= 216 && zc <= 224, "synth A4 is 440 Hz");
      CHECK(peak(out, 0, SR / 2) > 0.05f, "synth is audible while held");
      CHECK(peak(out, SR / 2 + SR / 5) < 1e-3f, "synth releases after the note length");
      CHECK(!s->active(), "synth voice frees after release");
      CHECK(finite(out), "synth output finite"); }

    // A note offset starts exactly that many frames into the block.
    { auto s = sineSynth();
      s->noteOn(noPads, 60, 1.0f, 100, SR);
      std::vector<float> out(256, 0.0f);
      s->render(out.data(), 256);
      CHECK(peak(out, 0, 100) == 0.0f, "nothing before the note offset");
      CHECK(peak(out, 101, 256) > 0.0f, "sound right after the note offset"); }

    // An offset longer than the block carries over to the next one.
    { auto s = sineSynth();
      s->noteOn(noPads, 60, 1.0f, 300, SR);
      auto out = render(*s, 512);
      CHECK(peak(out, 0, 300) == 0.0f && peak(out, 301, 512) > 0.0f, "note offset across blocks"); }

    // Held notes stop at note off; all notes off silences quickly.
    { auto s = sineSynth();
      s->noteOn(noPads, 64, 0.8f, 0, kHeld);
      render(*s, SR / 4);
      CHECK(s->active(), "held note keeps sounding");
      s->noteOff(64);
      render(*s, SR / 2);
      CHECK(!s->active(), "note off releases a held note");
      s->noteOn(noPads, 64, 0.8f, 0, kHeld);
      s->noteOn(noPads, 67, 0.8f, 0, kHeld);
      render(*s, SR / 10);
      s->allNotesOff(true);
      auto out = render(*s, SR / 20);
      CHECK(peak(out, SR / 40) < 1e-3f && !s->active(), "all notes off silences within 25 ms");
      s->noteOn(noPads, 64, 0.8f, 0, kHeld);
      render(*s, SR / 10);
      s->allNotesOff(false);
      auto soft = render(*s, SR / 2);
      CHECK(peak(soft, 0, SR / 100) > 0.01f && !s->active(), "a soft all notes off uses the release"); }

    // More notes than voices steals the oldest without blowing up.
    { auto s = Instrument::create(InstrumentKind::Synth, SR);
      for (int n = 0; n < 40; ++n) s->noteOn(noPads, 40 + n, 1.0f, 0, kHeld);
      auto out = render(*s, SR / 2);
      CHECK(finite(out) && peak(out) < 4.0f, "voice stealing stays bounded");
      for (int w = 0; w < 4; ++w) {
          s->setParam(0, static_cast<float>(w));
          s->setParam(6, 1.0f);
          auto o = render(*s, SR / 10);
          CHECK(finite(o), "every waveform with full resonance is finite");
      } }

    // Drum pads: a ramp sample makes playback easy to verify.
    std::vector<PadConfig> pads(kNumPads);
    auto ramp = std::make_shared<Samples>(4800);
    for (size_t i = 0; i < ramp->size(); ++i) (*ramp)[i] = 0.5f * static_cast<float>(i) / 4800.0f;
    pads[0].sample = ramp;

    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      d->noteOn(pads, kFirstPadNote, 1.0f, 0, 0);
      auto out = render(*d, 9600);
      CHECK(std::fabs(out[2400] - (*ramp)[2400]) < 1e-4f, "pad plays its sample unchanged");
      CHECK(peak(out, 4800) == 0.0f && !d->active(), "pad stops at the end of its sample"); }

    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      auto p = pads;
      p[0].pitch = 12.0f;
      d->noteOn(p, kFirstPadNote, 1.0f, 0, 0);
      auto out = render(*d, 4800);
      CHECK(std::fabs(out[1200] - (*ramp)[2400]) < 1e-3f, "pad pitched up an octave plays twice as fast");
      CHECK(peak(out, 2400) == 0.0f, "pitched pad ends in half the time"); }

    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      auto p = pads;
      p[0].reverse = true;
      d->noteOn(p, kFirstPadNote, 1.0f, 0, 0);
      auto out = render(*d, 4800);
      CHECK(std::fabs(out[1000] - (*ramp)[4800 - 1 - 1000]) < 1e-4f, "reversed pad plays backwards"); }

    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      auto p = pads;
      p[0].start = 1000;
      p[0].end = 2000;
      d->noteOn(p, kFirstPadNote, 1.0f, 0, 0);
      auto out = render(*d, 4800);
      CHECK(std::fabs(out[500] - (*ramp)[1500]) < 1e-4f, "pad plays from its start point");
      CHECK(peak(out, 1000) == 0.0f, "pad stops at its end point"); }

    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      auto p = pads;
      p[0].oneShot = false;
      d->noteOn(p, kFirstPadNote, 1.0f, 0, 960);
      auto out = render(*d, 4800);
      CHECK(peak(out, 960 + 2400) < 1e-3f, "gated pad stops after the note length"); }

    // Choke group: the closed hi-hat cuts the open one off.
    { auto noise = std::make_shared<Samples>(SR);
      for (size_t i = 0; i < noise->size(); ++i) (*noise)[i] = (i % 2) ? 0.5f : -0.5f;
      auto p = pads;
      p[3].sample = noise; p[3].chokeGroup = 1;
      p[4].sample = noise; p[4].chokeGroup = 1; p[4].gain = 0.0f;
      auto d = Instrument::create(InstrumentKind::Drums, SR);
      d->noteOn(p, kFirstPadNote + 3, 1.0f, 0, 0);
      render(*d, 2400);
      d->noteOn(p, kFirstPadNote + 4, 1.0f, 0, 0);  // silent pad in the same group
      auto out = render(*d, 4800);
      CHECK(peak(out, 2400) < 1e-3f, "choke group cuts the other pad off");
      d->noteOn(p, kFirstPadNote + 3, 1.0f, 0, 0);
      d->noteOn(p, kFirstPadNote + 3, 1.0f, 0, 0);
      auto twice = render(*d, 4800);
      CHECK(peak(twice, 2400) <= 0.5f + 1e-3f, "retriggering a pad replaces its voice"); }

    // At a loop jump one-shot pads ring on, pending and gated notes stop.
    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      auto p = pads;
      p[1].sample = ramp;
      p[1].oneShot = false;
      p[2].sample = ramp;
      d->noteOn(p, kFirstPadNote, 1.0f, 0, 0);
      d->noteOn(p, kFirstPadNote + 1, 1.0f, 0, 4800);
      d->noteOn(p, kFirstPadNote + 2, 1.0f, 2000, 0);  // not started yet: dropped
      render(*d, 1000);
      d->allNotesOff(false);
      auto out = render(*d, 3800);
      // By 60 ms the gated pad has released and only the one-shot is left.
      CHECK(std::fabs(out[2900] - (*ramp)[3900]) < 1e-3f, "one-shot pad rings on through a loop jump");
      d->allNotesOff(true);
      auto after = render(*d, 1000);
      CHECK(peak(after, 300) == 0.0f && !d->active(), "muting stops every pad"); }

    // Empty pads and notes outside the pad range are ignored.
    { auto d = Instrument::create(InstrumentKind::Drums, SR);
      d->noteOn(pads, kFirstPadNote + 5, 1.0f, 0, 0);
      d->noteOn(pads, 20, 1.0f, 0, 0);
      d->noteOn(pads, 100, 1.0f, 0, 0);
      CHECK(!d->active(), "empty or out-of-range pads do nothing"); }

    std::printf("%d failures\n", failures);
    return failures == 0 ? 0 : 1;
}
