import sys, json
sys.path.insert(0, r"D:\Projects\Auloud\scribe")
d = json.load(open(r"D:\Projects\Auloud\scribe\.scribe\937ed66f-4821-5c73-9636-eef370e16b9b\script.json", encoding="utf-8"))
targets = {"Star Genesis", "Lindsay", "Manny", "the Countess", "Fierce Breaker", "the Chief Overseer"}
shown = {t: 0 for t in targets}
for ch in d["chapters"]:
    if not (701 <= ch["chapter"] <= 800):
        continue
    flat = [s for b in ch["blocks"] for s in (b.get("sentences") or [])]
    for i, s in enumerate(flat):
        t = s.get("speaker")
        if s.get("kind") == "dialogue" and t in targets and shown[t] < 2:
            ctx = " ".join(x["text"] for x in flat[max(0, i - 2):i + 3])
            print(f"--- {t} ch{ch['chapter']}: {ctx[:400]}")
            shown[t] += 1
