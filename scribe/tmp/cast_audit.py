import sys, json, collections
sys.path.insert(0, r"D:\Projects\Auloud\scribe")
from text.cast import read_cast, resolve_speaker
from text.speakers import gender_hint_for

work = r"D:\Projects\Auloud\scribe\.scribe\937ed66f-4821-5c73-9636-eef370e16b9b"
cast = read_cast(work + r"\cast.yaml")
data = json.load(open(work + r"\script.json", encoding="utf-8"))
counts = collections.Counter()
hints = collections.defaultdict(collections.Counter)
voices = collections.Counter()
for ch in data["chapters"]:
    if not (701 <= ch["chapter"] <= 800):
        continue
    for b in ch["blocks"]:
        for s in b.get("sentences") or []:
            if s.get("kind") != "dialogue":
                continue
            raw = s["speaker"]
            counts[raw] += 1
            hints[raw][gender_hint_for(raw, s["text"])] += 1
            q = s.get("quote") or {}
            r = resolve_speaker(
                cast, raw_speaker=raw, gender=gender_hint_for(raw, s["text"]),
                chapter=q.get("chapter", ch["chapter"]), block=q.get("block"),
                quote=q.get("quote"), text=s["text"],
            )
            voices[(raw, r.character, r.voice)] += 1

print("lines by surface:")
for raw, n in counts.most_common(40):
    h = ",".join(f"{k}:{v}" for k, v in hints[raw].most_common())
    vs = sorted({(c, v) for (r, c, v), _ in voices.items() if r == raw})
    print(f"{n:5d}  {raw!r:40s} hint={h:24s} -> {vs}")
