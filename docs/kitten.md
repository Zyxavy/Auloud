# KittenTTS findings (Slice 14, KT0)

Status: preparation. Device findings land here as KT0 runs; nothing below is measured yet except where a source is named.

## Variants staged

| Variant | Source URL | Archive size | Extracted size | Date staged |
| --- | --- | --- | --- | --- |
| nano int8 | TBD | TBD | TBD | TBD |
| micro | TBD | TBD | TBD | TBD |
| nano fp32 (comparison) | TBD | TBD | TBD | TBD |

## 32-bit gate (spike bench)

- Load on `armeabi-v7a` (pinned sherpa-onnx 1.13.8): TBD (exact error if it fails)
- One-sentence synth: load time TBD, RTF TBD
- Second device (64-bit, if available): TBD

## Voice mapping (sid to name and gender)

- `voices.bin` order per the sherpa-onnx conversion script: TBD
- PC cross-check (same sentence per sid versus per named voice): TBD
- Table (labels stay "Kitten voice 0..N" until this is verified):

| sid | Name | Gender | Evidence |
| --- | --- | --- | --- |
| TBD | TBD | TBD | TBD |

## License check (decision 7 input)

| Variant | Weights terms | Redistribution | espeak-ng data note | Verified date |
| --- | --- | --- | --- | --- |
| TBD | TBD (legacy weights marked Apache-2.0; KittenML repo warns models are licensed separately) | TBD | GPL-3.0, `full` flavor only | TBD |

## Pack detection rules (decision 2 input)

- Model filename pattern across variants: TBD
- Required files: model file, `voices.bin`, `tokens.txt`, `espeak-ng-data/`
- Variant read from folder name (for example `kitten-nano-en-v0_8-int8`): TBD
- Optional `pack.json` (verified names, genders, sids): TBD
