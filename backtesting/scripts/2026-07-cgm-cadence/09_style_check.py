#!/usr/bin/env python3
"""House-style gate on the generated report.

Calm and factual British prose. No em-dashes, no bold, no rhetorical triplets, no
sensationalist intensifiers.
"""
import re, sys, os
p = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                  "..", "..", "reports", "2026-07_cgm_cadence_report.md"))
text = open(p).read()
lines_all = text.split("\n")
body, in_code = [], False
for l in lines_all:
    if l.strip().startswith("```"): in_code = not in_code; continue
    if in_code or l.strip().startswith("|"): continue
    body.append(l)
fails = []
def flag(name, pattern, lines=None, flags=0):
    hits = []
    for i, l in enumerate(lines if lines is not None else body, 1):
        for m in re.finditer(pattern, l, flags):
            hits.append((i, l.strip()[:100]))
    if hits: fails.append((name, hits))

flag("em-dash or en-dash used as punctuation", r"[—–]", text.split("\n"))
flag("bold markup", r"\*\*")
flag("sensationalist intensifier",
     r"\b(dramatic\w*|striking\w*|remarkabl\w*|stunning\w*|huge|massive|decisive\w*|"
     r"settles it|beautiful\w*|crucial\w*|vastly|enormous\w*)\b", flags=re.I)
# Rhetorical triplets only. Numeric enumerations ("5, 10, 15, 30 and 45 minutes") and
# parenthetical clauses ("at every lag, including the shortest, and its height...") are
# legitimate and must not be flagged.
CLAUSE_OPENERS = {"including", "such", "except", "with", "so", "and", "or", "which", "where",
                  "whether", "since", "because", "though", "although", "if", "then", "as",
                  "in", "on", "for", "to", "at", "by", "from", "of", "that", "this", "these"}
def triplet_hits(lines):
    hits = []
    pat = re.compile(r"\b([A-Za-z]+(?:\s+[A-Za-z]+){0,2}), ([A-Za-z]+(?:\s+[A-Za-z]+){0,2}),?"
                     r" and ([A-Za-z]+(?:\s+[A-Za-z]+){0,2})\b")
    for i, l in enumerate(lines, 1):
        for m in pat.finditer(l):
            first_word = m.group(2).split()[0].lower()
            if first_word in CLAUSE_OPENERS:      # parenthetical, not a list
                continue
            if any(ch.isdigit() for ch in m.group(0)):
                continue
            hits.append((i, m.group(0)[:100]))
    return hits
_t = triplet_hits(body)
if _t: fails.append(("rhetorical triplet (X, Y and Z)", _t))
flag("American spelling", r"\b(normalize\w*|analyze\w*|behavior\w*|favor\w*|color\w*)\b", flags=re.I)

if fails:
    print("STYLE CHECK FAILED\n")
    for name, hits in fails:
        print(f"  {name}: {len(hits)}")
        for i, l in hits[:6]: print(f"    line {i}: {l}")
        print()
    sys.exit(1)
print(f"STYLE CHECK PASSED  ({len(text.split(chr(10)))} lines, "
      f"{len(text.split())} words)")
