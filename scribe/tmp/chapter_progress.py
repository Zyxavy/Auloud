import sys, json
sys.path.insert(0, r"D:\Projects\Auloud\scribe")
from bundle.models import ChapterFile
from bundle.writer import VOICE_PITCH
from text.cast import read_cast
from tts.cache import cache_key, cache_path
import build as B

work = r"D:\Projects\Auloud\scribe\.scribe\937ed66f-4821-5c73-9636-eef370e16b9b"
cache = work + r"\cache"
engine_version = "kokoro-onnx 0.6.1 model=325505369:1793329240268191200 voices=28214398:1793329244317567000"

cast = read_cast(work + r"\cast.yaml")
narr = cast["narrator"]
data = json.load(open(work + r"\script.json", encoding="utf-8"))
chapters = {c["chapter"]: ChapterFile.from_dict(c) for c in data["chapters"]}

done = []
partial = []
missing = []
for idx in range(701, 801):
    ch = chapters[idx]
    plan = B.resolve_chapter(
        cast, ch,
        narrator_voice=narr["voice"], narrator_speed=float(narr.get("speed", 1.0)),
        narrator_engine=narr.get("engine", "kokoro"), emotion="speed",
    )
    total = have = 0
    for s in ch.sentences_in_order():
        rv = plan[s.sid]
        total += 1
        key = cache_key(s.text, rv.voice, rv.speed, VOICE_PITCH, engine_version)
        if cache_path(cache, key).is_file():
            have += 1
    if have == total:
        done.append(idx)
    elif have == 0:
        missing.append(idx)
    else:
        partial.append((idx, have, total))

print(f"chapters fully synthesized: {len(done)}", (done[0], done[-1]) if done else "")
print(f"chapters partially done: {len(partial)}")
for p in partial[:5]:
    print("   ch%d %d/%d" % p)
print(f"chapters not started: {len(missing)}")
print("sentence totals done: %d / %d" % (
    sum(1 for _ in done), len(done)))
