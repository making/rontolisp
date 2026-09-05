# 711. What a directory rename breaks outside its own diff

Difficulty: Medium

`.todo/682` renamed `examples/llama2` to `examples/llm` on 2026-09-05. The rename itself
was correct. It then broke four separate things, **every one of them outside the renamed
paths, every one silent, and none visible in the rename's own diff** -- so no review of
that diff could have found any of them.

**The full account is in `.todo/708`. This item does not restate it** (standing rule 9);
it exists because the account is currently the incident, and what the tree needs is the
check that survives the incident. 708's own fix is one filter line in
`LispFormatterTest`; that is B-1 and is not this.

## The shape, in one sentence

A rename moves paths. **What breaks is the machinery AROUND the paths** -- code that reads
the tree by walking it, by citing it in prose, by ignoring parts of it, or by looking a
fixture up in it -- and none of that machinery appears in the diff that moved them.

The four, with 708 holding the evidence for each:

| # | machinery | what it did | how it was found |
| --- | --- | --- | --- |
| 1 | a test that WALKS the tree | `LispFormatterTest` ran 17014 of 26359 tests over `.claude/worktrees/` | counting the corpus |
| 2 | prose that CITES the path | javadoc line lengths shifted in `LinalgBlas` and `TokenizersLibraryTest`; the build failed until `spring-javaformat` was re-applied | the build |
| 3 | a `.gitignore` that IGNORES by location | the rule moved, the untracked files stayed, `git add` swept 61 MB onto develop | a blocked `git pull` on another box |
| 4 | a fixture LOOKUP | the `stories15M` legs skip with the files present on disk at the old path | comparing two skip counts days apart |

Note the third column. **Not one was found by a test failing.** Two were found by a number
that was read against a second number, one by a build, one by a pull that could not
proceed. #4 in particular reported `39 / 0 / 0` -- equal to its own pre-rename baseline --
while three legs silently stopped running.

## Do

1. **Write the `.kb` card**, from 708's account, as a checklist to run when a directory
   moves. It has to be mechanical, because every item on it is invisible to judgement:
   - `git status --porcelain` at the OLD path before the next `git add` (standing rule 12,
     and the tree has 18 directory-local `.gitignore` files -- enumerate them in the card
     rather than saying "several", several of which cover whole build trees).
   - `git grep` the old path AND `git ls-files` it: grep finds citations, `ls-files` finds
     members, and a rename needs both (`.todo/709`).
   - Re-apply the formatter, because a shortened path changes wrapped line lengths.
   - Move the untracked fixtures the workdir expects, not only the tracked files.
   - Run the affected suite slice and **read its SKIP COUNT against the prior run's**.
2. **Verify each line against the tree before writing it**, rather than copying it out of
   708. The 18 `.gitignore` files and the citation-grep patterns are claims about the
   repository as it stands, and the whole lesson of this item is that a fact recorded for
   one incident decays.
3. Decide where it lives: this is not the formatter's topic and not `git`'s, so it is
   probably its own card indexed from `.kb/README.md`.

**Out of scope on purpose.** The general reading disciplines this run produced -- diff the
lists rather than reasoning about which terms ought to differ; a sum that closes is not
evidence about its terms; relay a census from the file with its total AND its class count
-- are process, they are not specific to renames, and `.todo/709` is where both
orchestrators either co-sign or cut them. Do not smuggle them in here.

## Testing

The card is prose; the check is whether following it on the 682 diff would have caught all
four, which is answerable by re-reading 708 and does not need a run.

## Done means

A `.kb` card exists that a person following it during a directory rename would have caught
all four, with every claim in it verified against the current tree rather than inherited
from the incident.
