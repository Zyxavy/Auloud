import sys, collections
sys.path.insert(0, r"D:\Projects\Auloud\scribe")
from extract.clean import extract_epub_chapters
from text.dialogue import split_chapter_dialogue
from text.speakers import build_quote_contexts
from text.attribution import attribute_quotes
from text.emotion import detect_emotion

book = extract_epub_chapters(r"C:\Users\kelps\OneDrive\Documents\Books\Strongest_Hammer_God.epub")
target = None
for c in book.chapters:
    flat = " ".join(s.text for b in c.blocks for s in (b.sentences or []))
    if "shouted" in flat or "shouts" in flat:
        target = c
        break
print("chapter", target.chapter)
dlg = split_chapter_dialogue(target)
atts = attribute_quotes(build_quote_contexts(dlg, book="b"))
cues = [(a.cue.verb_lemma, a.cue.adverbs, detect_emotion(a.cue)[0]) for a in atts if a.cue]
print("quotes", len(atts), "cues", len(cues))
print(cues[:15])
