import sys, collections
sys.path.insert(0, r"D:\Projects\Auloud\scribe")
from extract.clean import extract_epub_chapters
from text.dialogue import split_chapter_dialogue
from text.speakers import build_quote_contexts
from text.attribution import attribute_quotes
from text.emotion import detect_emotion

book = extract_epub_chapters(r"C:\Users\kelps\OneDrive\Documents\Books\Strongest_Hammer_God.epub")
ch = [c for c in book.chapters if c.chapter == 701][0]
dlg = split_chapter_dialogue(ch)
atts = attribute_quotes(build_quote_contexts(dlg, book="b"))
print("quotes", len(atts))
cues = [a.cue for a in atts if a.cue is not None]
print("with cue", len(cues))
verbs = collections.Counter((c.verb_lemma or "") for c in cues)
print(verbs.most_common(10))
print([(c.verb_lemma, c.adverbs, detect_emotion(c)[0]) for c in cues[:10]])
