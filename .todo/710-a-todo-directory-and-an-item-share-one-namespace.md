# 710. A closed item's artefacts and an open item share one namespace

Difficulty: Medium (the rename is mechanical; the link check is the part that needs judgement)

Filed 2026-09-05. `.todo/.history.md` now WARNS that a `.todo/NNN-.../` directory says
nothing about whether an item is open -- only a `.todo/NNN-*.md` file does. That warning
was written because three readers took `.todo/672`'s artefact directory for an open item
hours after it closed, and one nearly held a rename waiting for it. **A warning is not a
fix**: it relies on being read, and the trap fires at `ls .todo/`, which is where nobody
is reading prose.

Measured on the day: **13 of the 15 directories under `.todo/` belong to CLOSED items**
(`123`, `471`, `487`, `488`, `512`, `517`, `527`, `528`, `529`, `534`, `535`, `649`, `672`);
only `480` and `482` are open. So a listing is misleading far more often than not.

The root cause is that one namespace carries two things with different lifetimes: an item,
which is deleted when it closes, and its measurement artefacts, which are kept forever
precisely because the numbers outlive the item. Nothing in the name distinguishes them.

## Do

**1. Stop overloading the namespace.** On close, move the artefacts somewhere a listing
   can tell apart -- a suffix (`.todo/672-....closed/`) or a shared root
   (`.todo/artefacts/672-.../`). Either works; pick one and apply it to all 13 at once.
   The ongoing cost is one `git mv` per close, and at close time nothing cites the new
   path yet, so the recurring cost is nil.

**2. Add a path-citation link check**, and do this FIRST -- it is what makes step 1 safe
   and it is worth having on its own. A test that every repo-relative path cited in
   `.kb/**`, `.todo/**` and `doc/**` actually exists. Measured cost of step 1 without it:
   ~48 citing lines, of which the real external ones are ~30 across `.kb`, `.todo` and two
   comments in `src/main/java/am/ik/gpu/MetalGemm.java`.

   Judgement needed: prose contains illustrative paths that are not meant to resolve, so a
   naive implementation is noisy. Decide the rule (only paths in backticks? only those
   matching a `.todo/`, `.kb/`, `src/`, `doc/`, `examples/` prefix?) and state it in the
   test.

## Why the link check earns its place independently of step 1

Two renames on 2026-09-05 each left citations behind that no grep found, for two DIFFERENT
reasons, and neither would have been caught by the suite:

- `examples/llama2` -> `examples/llm` (`.todo/682`): `examples/examples.yaml` cites the
  directory by BARE RELATIVE path (`- path: llama2/...`, `workDir: llama2`, 14 lines).
  `ExamplesE2eTest` resolves them at run time and `./mvnw test` SKIPS that suite, so a
  missed line leaves a tree that is clean and green and breaks for whoever next runs
  `-Drontolisp.examples=true`.
- The same rename's `examples/llama2/.gitignore` appeared in NO content grep, because its
  name is in the renamed path and its contents mention nothing. Only `git mv` found it.

`.todo/709` Part 1 records the discipline those produced -- **grep finds citations,
`git ls-files` finds members, and a rename needs both**. A link check is the machine half
of the same idea: it catches the citation that a human's grep missed, on every push,
without anyone having to suspect it.

## Not in scope

Renaming `.todo/NNN-*.md` FILES or their history rows. A filename is an item's identity
and a history row names the path inside the commit that removed it
(`.todo/.history.md`, "Reading a deleted item"), so those cannot move. This item touches
only the artefact DIRECTORIES of items already closed.

## Verify

- `ls .todo/` distinguishes open items from closed artefacts without opening anything.
- The link check fails when a cited path is deleted or moved, and is green on develop.
- `.todo/.history.md`'s warning section is reduced to a pointer at whatever step 1 chose,
  rather than restating it (`.todo/670` rule 9).
