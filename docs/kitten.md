# KittenTTS findings (Slice 14, KT0)

Status: desk research done 2026-10-10; device findings TBD (staging, spike bench, listening confirmation).

## Pinned runtime (verified locally)

- sherpa-onnx 1.13.8 (the pinned version) ships `OfflineTtsKittenModelConfig` in its Java API with fields `model`, `voices`, `tokens`, `dataDir`, `lengthScale` (confirmed via `javap` on the Gradle-cached AAR), and `armeabi-v7a` native libs (`libsherpa-onnx-c-api.so`, `-cxx-api.so`, `-jni.so`). The API surface exists on 32-bit ARM; only the runtime load and bench remain device work.

## Variants staged (device TBD)

| Variant | Source URL | Archive size | Extracted size | Date staged |
| --- | --- | --- | --- | --- |
| nano int8 | TBD (`kitten-nano-en-v0_8-int8.tar.bz2` on the sherpa-onnx `tts-models` release page) | TBD | TBD | TBD |
| micro | TBD (`kitten-micro-en-v0_8.tar.bz2`, same page) | TBD | TBD | TBD |
| nano fp32 (comparison) | TBD (`kitten-nano-en-v0_8-fp32.tar.bz2`, same page) | TBD | TBD | TBD |

## 32-bit gate (spike bench, device TBD)

- Load on `armeabi-v7a`: TBD (record the exact error if it fails)
- One-sentence synth: load time TBD, RTF TBD
- Second device (64-bit, if available): TBD

## Voice mapping (derived, needs the listening cross-check)

`voices.bin` order follows the `speakers` list in sherpa-onnx `scripts/kitten-tts/*/generate_voices_bin.py` (sid N = Nth entry): `expr-voice-2-m, -2-f, -3-m, -3-f, -4-m, -4-f, -5-m, -5-f`. Legacy names come only from KittenML's `config.json` `voice_aliases` (0.8-era repos). Joined and derived (not single-source documented):

| sid | Name | Gender | Evidence |
| --- | --- | --- | --- |
| 0 | Jasper | male | derived join, 2026-10-10; needs listening confirm |
| 1 | Bella | female | derived join, 2026-10-10; needs listening confirm |
| 2 | Bruno | male | derived join, 2026-10-10; needs listening confirm |
| 3 | Luna | female | derived join, 2026-10-10; needs listening confirm |
| 4 | Hugo | male | derived join, 2026-10-10; needs listening confirm |
| 5 | Rosie | female | derived join, 2026-10-10; needs listening confirm |
| 6 | Leo | male | derived join, 2026-10-10; needs listening confirm |
| 7 | Kiki | female | derived join, 2026-10-10; needs listening confirm |

Genders corroborated by KittenML `docs/voices-and-expression.md` plus the `-f`/`-m` suffixes. Cross-check: same fixed sentence per sid via sherpa-onnx versus per named voice via the Python `kittenml` package, compared by ear (use identical sentence length on both sides: v0.8 upstream picks the style row by length).

## License check (decision 7 input)

All 7 legacy weight repos report `license: apache-2.0` via the HuggingFace API (checked 2026-10-10): `KittenML/kitten-tts-nano-0.1`, `-nano-0.2`, `-mini-0.1`, `-nano-0.8-fp32`, `-nano-0.8-int8`, `-micro-0.8`, `-mini-0.8`. Apache-2.0 permits redistribution with attribution (have legal confirm; this doc is not legal advice). Caveats: the KittenML repo warns models are licensed separately (the concrete case is KittenTTS 2 under the Stellon Labs Community License, not Apache-2.0), so every future model is re-checked individually; espeak-ng data is GPL-3.0, which keeps the engine in `full` regardless.

| Variant | Weights terms | Redistribution | espeak-ng data note | Verified date |
| --- | --- | --- | --- | --- |
| nano int8 (0.8) | Apache-2.0 (HF API) | allowed with attribution | GPL-3.0, `full`-only | 2026-10-10 (desk; confirm on download) |
| micro (0.8) | Apache-2.0 (HF API) | allowed with attribution | GPL-3.0, `full`-only | 2026-10-10 (desk; confirm on download) |
| nano fp32 (0.8) | Apache-2.0 (HF API) | allowed with attribution | GPL-3.0, `full`-only | 2026-10-10 (desk; confirm on download) |

## Pack detection rules (decision 2 input)

Per-variant tarball contents (sherpa-onnx `tts-models` release page, asset list checked 2026-10-10; inner paths per sherpa-onnx docs `run.sh` examples):

| Tarball | Model file | Sidecars in the same dir |
| --- | --- | --- |
| `kitten-nano-en-v0_1-fp16.tar.bz2` | `model.fp16.onnx` | `voices.bin`, `tokens.txt`, `espeak-ng-data/` |
| `kitten-nano-en-v0_2-fp16.tar.bz2` | `model.fp16.onnx` | `voices.bin`, `tokens.txt`, `espeak-ng-data/` |
| `kitten-mini-en-v0_1-fp16.tar.bz2` | `model.fp16.onnx` (inferred from the asset name; not extracted) | `voices.bin`, `tokens.txt`, `espeak-ng-data/` (same caveat) |
| `kitten-nano-en-v0_8-fp32.tar.bz2` | `model.fp32.onnx` | `voices.bin`, `tokens.txt`, `espeak-ng-data/` |
| `kitten-nano-en-v0_8-int8.tar.bz2` | `model.int8.onnx` | `voices.bin`, `tokens.txt`, `espeak-ng-data/` |
| `kitten-micro-en-v0_8.tar.bz2` | `model.onnx` | `voices.bin`, `tokens.txt`, `espeak-ng-data/` |
| `kitten-mini-en-v0_8.tar.bz2` | `model.onnx` | `voices.bin`, `tokens.txt`, `espeak-ng-data/` |

Detection rule for KT2: match `model*.onnx` (name pattern, not a fixed name) plus `voices.bin`, `tokens.txt`, `espeak-ng-data/`; read the variant from the folder name (for example `kitten-nano-en-v0_8-int8`); optional `pack.json` carries verified names, genders and sids once the cross-check lands.
