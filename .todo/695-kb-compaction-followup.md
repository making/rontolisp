# .kb compaction: the leftover half

Difficulty: Medium

`.kb/` was compacted in two passes (2026-09-04/05). The index and every topic file
were rewritten as reference cards. What is recorded here is what those passes did
NOT reach, and the one structural check nobody ran.

## Where it landed

- Baseline `f36bfbd5`: 3.66 MB over 128 files.
- After pass 1: 2.29 MB (63%).
- After pass 2: see the commit; the batches that reported landed at 32-42% of
  baseline, i.e. a ~60% reduction rather than the 70-80% that was asked for.
- `README.md` (the index) went 68 KB -> 15 KB and is the shape the rest should
  match: one line per file, no prose.

## Why it stopped there

Every worker reported the same floor independently: once the dated narratives,
benchmark tables, decision records, rationale and per-backend walkthroughs are
gone, what is left is identifier catalogues (Java classes, Lisp names, CLI flags),
one-line traps, and pinning-test names. Going below ~35% means deleting those.

The next thing that would have to be sacrificed is the pinning-test name lists.
That was judged not worth it -- a test name is the highest-value pointer a `.kb`
file carries, since it is what lets a reader confirm the invariant still holds.

## What is actually left to do

1. **Decide whether ~40% is the resting size.** If it is, nothing below matters
   and this file can be deleted. If a further cut is wanted, the lever is the
   files still furthest from card shape -- rank them with:
   `for f in .kb/*.md; do b=$(git show f36bfbd5:$f 2>/dev/null | wc -c); [ "$b" -gt 0 ] && echo "$((100*$(wc -c <$f)/b))% $f"; done | sort -rn`
2. **Identifier-preservation audit, repo-wide.** Only some of the workers ran one
   (extract every backticked token from the `f36bfbd5` version of a file, check it
   still appears in the rewrite; treat `.todo/NNN` refs, benchmark row labels and
   deleted snippets as intentional drops). The rest did not. Run it over all 128
   files and restore anything load-bearing that was lost -- this is the one check
   that can find real damage, and these files have no tests.
3. **Cross-reference integrity.** `.kb` files cite each other by path and
   occasionally by section name. Confirm every `.kb/<name>.md` reference still
   resolves to an existing file, and that `CLAUDE.md`'s citation of
   `.kb/jvm-export.md`, "What travels" still names a real heading.
4. **Keep the index in step.** `README.md` must list every `.kb/*.md` exactly
   once, with no dead links. A new topic file added by another session needs a
   line there.

## Constraint that outlived the pass

`README.md` carries it, and it is why this work is risky: **replace a passage you
can SEE, never a computed range.** A marker-to-marker edit silently deletes
whatever was added between the markers since the file was last read, and nothing
fails -- these files have no tests.
