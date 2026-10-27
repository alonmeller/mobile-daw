// Host-side checks of the effect DSP; built and run by CI with the system compiler.
#include "Effects.h"
#include <cmath>
#include <cstdio>
#include <vector>
#include <algorithm>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s\n", msg); ++failures; } else std::printf("ok: %s\n", msg); } while (0)

static const int SR = 48000;

std::shared_ptr<Effect> make(EffectKind k, std::vector<float> p) {
    auto e = Effect::create(k, SR);
    for (size_t i = 0; i < p.size(); ++i) e->setParam((int)i, p[i]);
    return e;
}

void run(Effect &e, std::vector<float> &l, std::vector<float> &r) {
    for (size_t i = 0; i < l.size(); i += 256) {
        int n = (int)std::min<size_t>(256, l.size() - i);
        e.process(l.data() + i, r.data() + i, n);
    }
}

std::vector<float> sine(float f, float amp, int n) {
    std::vector<float> v(n);
    for (int i = 0; i < n; ++i) v[i] = amp * std::sin(2 * 3.14159265f * f * i / SR);
    return v;
}

float peak(const std::vector<float> &v, size_t from = 0, size_t to = SIZE_MAX) {
    float p = 0; to = std::min(to, v.size());
    for (size_t i = from; i < to; ++i) p = std::max(p, std::fabs(v[i]));
    return p;
}

bool finite(const std::vector<float> &v) { for (float x : v) if (!std::isfinite(x)) return false; return true; }

int main() {
    // EQ flat = identity
    { auto e = make(EffectKind::Eq, {0, 100, 0, 1000, 1, 0, 8000, 20, 20000});
      auto l = sine(440, 0.5f, SR), r = l, l0 = l; run(*e, l, r);
      float maxdiff = 0; for (int i = 0; i < SR; ++i) maxdiff = std::max(maxdiff, std::fabs(l[i] - l0[i]));
      CHECK(maxdiff < 1e-6f, "EQ flat is transparent"); }
    // EQ +12 dB mid at 1 kHz boosts a 1 kHz sine by ~4x
    { auto e = make(EffectKind::Eq, {0, 100, 12, 1000, 1, 0, 8000, 20, 20000});
      auto l = sine(1000, 0.1f, SR), r = l; run(*e, l, r);
      float p = peak(l, SR / 2); std::printf("  mid boost peak %.3f\n", p);
      CHECK(p > 0.35f && p < 0.45f, "EQ +12 dB peak boosts by ~4x"); }
    // EQ low cut at 500 Hz attenuates 50 Hz strongly
    { auto e = make(EffectKind::Eq, {0, 100, 0, 1000, 1, 0, 8000, 500, 20000});
      auto l = sine(50, 0.5f, SR), r = l; run(*e, l, r);
      float p = peak(l, SR / 2); std::printf("  low cut peak %.4f\n", p);
      CHECK(p < 0.01f, "EQ low cut removes 50 Hz"); }
    // Low shelf +6 dB at 50 Hz -> ~2x
    { auto e = make(EffectKind::Eq, {6, 200, 0, 1000, 1, 0, 8000, 20, 20000});
      auto l = sine(50, 0.1f, SR), r = l; run(*e, l, r);
      float p = peak(l, SR / 2); std::printf("  low shelf peak %.3f\n", p);
      CHECK(p > 0.18f && p < 0.22f, "EQ low shelf +6 dB"); }
    // High shelf -12 dB at 12 kHz -> ~0.25x
    { auto e = make(EffectKind::Eq, {0, 100, 0, 1000, 1, -12, 3000, 20, 20000});
      auto l = sine(12000, 0.4f, SR), r = l; run(*e, l, r);
      float p = peak(l, SR / 2); std::printf("  high shelf peak %.3f\n", p);
      CHECK(p > 0.08f && p < 0.12f, "EQ high shelf -12 dB"); }
    // Compressor: -6 dBFS sine, threshold -18, ratio 4 -> output ~ -15 dBFS (0.178)
    { auto e = make(EffectKind::Compressor, {-18, 4, 5, 100, 0, 0});
      auto l = sine(200, 0.5f, SR * 2), r = l; run(*e, l, r);
      float p = peak(l, SR); std::printf("  comp peak %.3f\n", p);
      CHECK(p > 0.15f && p < 0.21f, "Compressor 4:1 above threshold"); }
    // Compressor below threshold leaves signal alone
    { auto e = make(EffectKind::Compressor, {-18, 4, 5, 100, 0, 0});
      auto l = sine(200, 0.05f, SR), r = l; run(*e, l, r);
      CHECK(std::fabs(peak(l, SR / 2) - 0.05f) < 0.002f, "Compressor below threshold transparent"); }
    // Limiter respects ceiling
    { auto e = make(EffectKind::Limiter, {12, -1, 50});
      auto l = sine(100, 0.9f, SR), r = l; run(*e, l, r);
      float p = peak(l); std::printf("  limiter peak %.4f (ceiling %.4f)\n", p, std::pow(10.f, -1/20.f));
      CHECK(p <= std::pow(10.f, -1 / 20.f) + 1e-5f, "Limiter never exceeds ceiling"); }
    // Gate closes on quiet signal
    { auto e = make(EffectKind::Gate, {-30, 1, 10, 20, -80});
      auto l = sine(200, 0.005f, SR), r = l; run(*e, l, r);
      CHECK(peak(l, SR / 2) < 1e-4f, "Gate mutes signal below threshold");
      auto l2 = sine(200, 0.5f, SR), r2 = l2; run(*e, l2, r2);
      CHECK(peak(l2, SR / 2) > 0.45f, "Gate opens above threshold"); }
    // Delay: impulse produces echo at 100 ms
    { auto e = make(EffectKind::Delay, {100, 0, 1, 20000, 0});
      std::vector<float> l(SR, 0.f), r(SR, 0.f); l[0] = r[0] = 1.f; run(*e, l, r);
      int at = 0; float best = 0; for (int i = 1; i < SR; ++i) if (std::fabs(l[i]) > best) { best = std::fabs(l[i]); at = i; }
      std::printf("  echo at %d samples (%.1f ms), %.3f\n", at, at * 1000.0 / SR, best);
      CHECK(std::abs(at - 4800) <= 2 && best > 0.9f, "Delay echo at 100 ms"); }
    // Delay on a bus: with no dry signal only the echo comes out
    { auto e = make(EffectKind::Delay, {100, 0, 1, 20000, 0, 0});
      std::vector<float> l(SR, 0.f), r(SR, 0.f); l[0] = r[0] = 1.f; run(*e, l, r);
      CHECK(l[0] == 0.f && std::fabs(l[4800]) > 0.9f, "Delay with dry 0 gives only the echo");
      auto older = make(EffectKind::Delay, {100, 0, 1, 20000, 0});
      std::vector<float> l2(64, 0.f), r2(64, 0.f); l2[0] = r2[0] = 1.f; run(*older, l2, r2);
      CHECK(l2[0] == 1.f, "Delay set without a dry level keeps the dry signal"); }
    // Delay with feedback stays finite and decays
    { auto e = make(EffectKind::Delay, {50, 0.9f, 1, 5000, 1});
      auto l = sine(300, 0.5f, SR), r = l; l.resize(SR * 6, 0.f); r.resize(SR * 6, 0.f); run(*e, l, r);
      CHECK(finite(l) && finite(r), "Delay feedback finite");
      CHECK(peak(l, SR * 5) < peak(l, SR, SR * 2), "Delay feedback decays"); }
    // Reverb: impulse tail decays and stays finite
    { auto e = make(EffectKind::Reverb, {0.8f, 0.5f, 1, 0.3f, 1});
      std::vector<float> l(SR * 8, 0.f), r(SR * 8, 0.f); l[0] = r[0] = 1.f; run(*e, l, r);
      float early = peak(l, 2000, SR), late = peak(l, SR * 7);
      std::printf("  reverb early %.4f late %.6f\n", early, late);
      CHECK(finite(l) && finite(r), "Reverb finite");
      CHECK(early > 0.005f && late < early * 0.01f, "Reverb tail decays"); }
    // Chorus, distortion: finite, bounded
    { auto e = make(EffectKind::Chorus, {0.8f, 3, 12, 0.5f});
      auto l = sine(440, 0.5f, SR), r = l; run(*e, l, r);
      CHECK(finite(l) && peak(l) < 0.75f, "Chorus finite and bounded"); }
    { auto e = make(EffectKind::Distortion, {24, 5000, 1, -6});
      auto l = sine(440, 0.5f, SR), r = l; run(*e, l, r);
      std::printf("  distortion peak %.3f\n", peak(l));
      CHECK(finite(l) && peak(l) <= 0.51f, "Distortion bounded by output gain"); }
    // Bypass flag
    { auto e = make(EffectKind::Reverb, {0.8f, 0.5f, 1, 0.3f, 1}); e->setBypass(true);
      CHECK(e->bypassed(), "Bypass flag"); }
    std::printf("%d failures\n", failures);
    return failures ? 1 : 0;
}
