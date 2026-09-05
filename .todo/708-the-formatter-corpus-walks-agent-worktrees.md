# 708. `LispFormatterTest` walks `.claude/worktrees/`, so the suite's test count is a property of the box

Difficulty: Low

Filed 2026-09-05 from the cross-box comparison `.todo/670`'s protocol depends on.
Dorian ran `./mvnw test` green at `6a6ebbac` and reported **26359 tests**; GB10 ran it
green at `4358af09` and reported **10607**. Same source, 231 report files on both sides,
zero failures on both. **The gap is not in the code. It is 25 stale agent worktrees on
dorian's disk.**

## The mechanism

`LispFormatterTest.repositoryLispSources()` is a `@MethodSource` that does
`Files.walk(Path.of("."))` and takes every `.lisp` / `.asd` under the working directory.
It filters two things and only two:

```java
.filter(path -> !path.toString().contains("/target/"))
.filter(path -> !path.toString().contains("/ansi-test/suite/"))
```

`.claude/worktrees/` is not filtered. Measured on dorian:

| | count |
| --- | --- |
| `git ls-files '*.lisp'` | 618 |
| on disk, excluding `target/` and worktrees | 628 |
| on disk, excluding `target/` only | **22098** |
| inside `.claude/worktrees/` | **21470**, across **25** worktrees |

`LispFormatterTest` alone ran **17014** of dorian's 26359 tests -- 65% of the suite,
formatting the same files 25 times over.

## Why it matters more than a slow suite

1. **The suite's test count is a property of the box, not of the commit.** Every
   cross-box comparison in `.todo/670` -- 9977 against 9976, 10000, 10607, 26359 -- was
   comparing numbers that include however many worktrees happened to exist. The protocol
   of reading an "accounted-for delta" cannot work while one term is unbounded.
2. **It is a live cross-lane interference channel.** The walk reads other agents' WORKING
   TREES. A lane mid-edit with a not-yet-formatted `.lisp` file makes the MAIN tree's
   `./mvnw test` fail, in a file the main tree does not contain, with no indication that
   the source is another worktree. Nobody has hit this yet; nothing prevents it.
3. It formats ~590 files 25 times for no coverage.

**The composition is the reason this matters, not the 17014 tests.** Two individually
harmless mechanisms meet: a rename can change formatting (682 shortened
`examples/llama2` to `examples/llm`, which changed javadoc line lengths in `LinalgBlas`
and `TokenizersLibraryTest` and failed the build until `spring-javaformat` was
re-applied -- a formatting consequence of a RENAME that nobody would predict), and the
walk reads other lanes' trees. Together: **a worktree is not private to the lane that
owns it.** An unformatted file sitting mid-edit in one lane's worktree fails the MAIN
tree's suite, in a file the main tree does not contain, and the failure names a path the
person reading it has never heard of. Nearly undiagnosable from the main tree.

**Third consequence of the same rename, found 2026-09-05 after this item was filed.**
`examples/llama2/.gitignore` listed `stories15M.bin` and `tokenizer.bin`;
`download-stories15M.sh` fetches them and `examples.yaml` documents the RUN legs as
skipping themselves when they are absent. 682 moved that .gitignore to
`examples/llm/.gitignore`, correctly. The two artefacts were UNTRACKED, so nothing moved
them, and they stayed behind with their rule one path away -- and the next `git add` on
another lane swept 61 MB onto develop inside a commit whose message says it files a todo
(`97b81823`; untracked again in `e34da35c`, still in history). **A directory-local ignore
rule protects by LOCATION, so moving the rule stops protecting whatever stayed -- and what
stayed is invisible to the rename precisely because being ignored is what kept it out of
the rename.** `git status` afterwards shows them as ordinary new untracked files, with no
history to suggest otherwise.

This is the same shape as the formatting leg and it strengthens the item's thesis rather
than adding a separate one: **the rename's consequences all landed outside the renamed
paths, so no review of the rename's own diff could have found any of them.** Three now,
by three different mechanisms -- line lengths in files that merely cite the path, a test
corpus that walks directories nobody listed, and an ignore rule that stopped covering the
files it was written for. Whatever pin comes out of the "Do" below should be read with
that in mind: the class is wider than the formatter.

It also gives the umbrella's "do not compare test totals across boxes until 708 lands" a
second, independent reason. While those binaries were tracked, a fresh clone HAD
`stories15M.bin`, so the llm RUN legs that `examples.yaml` describes as self-skipping
would have run. Any total taken from a clone made between `97b81823` and `e34da35c` is
not comparable to one taken outside that window.

**The fix is free only on the box that makes it.** Untracking a file DELETES it for every
puller who had it, because to them it arrives as an ordinary tracked deletion. The other
orchestrator's box had both artefacts tracked at `examples/llama2/` with nothing having
moved them, so its `git merge origin/develop` removed 61 MB from its working tree -- not
untracked, deleted -- and the loss reaches the report only as a larger skip count, because
the RUN legs self-skip when the fixtures are absent. **`examples.yaml`'s skip, designed so a
developer without the download is not failed by it, reports the download being destroyed in
exactly the same integer.** It recovered from a
pre-rename worktree. The general form: a fix that moves content to safety on the box that
authors it is a deletion everywhere else, and silence is the designed behaviour on both
sides of that line.

**The class is 18, not one.** `git ls-files '*/.gitignore'` returns 18 directory-local
ignore files -- `ansi-test/`, `bench-report/`, `size-report/`, seven under `examples/wit/`,
`examples/{browser,cloudflare-workers,count-vowels,deep-learning-from-scratch,gae,llm,wasmcloud}/`,
and two vendored suites under `src/test/resources/`. Every one is one directory rename away
from this, and several ignore whole build trees (`/target/`, `node_modules/`, `dist/`) where
the swept-in payload would be far larger than 61 MB. The check is mechanical and belongs on
the rename procedure, not in anyone's memory: **after moving a directory that contains a
`.gitignore`, run `git status --porcelain` for untracked files at the OLD path before the
next `git add`** -- that is the only moment they are visible.

Three worktrees on the A box (`agent-a1beecb937abffbc6`, `agent-a436eebef9812f005`,
`agent-a59d84ba6c788cb33`) still hold untracked copies at `examples/llama2/`, currently
ignored by their own pre-rename `.gitignore`. They are armed, not firing: the rule leaves
only when that tree merges develop, and they are finished agents' trees that nothing will
merge. Disarm by deleting the copies once the content is verified identical to
`examples/llm/`. This is item 3's cleanup arriving with a second reason.

**A fourth consequence, and it needs no tracking at all.** The other orchestrator's
`ExamplesE2eTest` 39/0/0 was taken at `4358af09`, BEFORE the rename, when `examples.yaml`
said `workDir: llama2` and its box had the fixtures there -- so the three `stories15M` legs
RAN. After the rename the same box's files sit at `llama2/` while the leg looks in `llm/`,
so they SKIP, with the files physically present. **The rename alone changed which legs
execute on any box that had the fixtures.** That is independent
of the clone-side reason above and points the same way: no examples total spanning
`d4225aa5` is comparable to one taken before it. Three unrelated mechanisms now say do not
compare suite totals across boxes or across today.

**The count prints; the reason does not -- and the proof was in 670 before either
orchestrator wrote about it.** Two certification rows sit in the same file: pre-rename GB10
examples slice `39 / 0 / 0` (line 186), post-rename dorian `only=llm/` `39 / 0 / 0, 3
skipped` (line 159). Same total, same zero failures. **The whole difference between "the
three `stories15M` legs ran" and "they did not" is a skip count in an adjacent cell that
both orchestrators wrote down and read past.** So do not say the suite was silent; that
invites the fix "make it print", and it already prints.

The accurate form: **a skip count is only a signal against a prior skip count for the same
slice.** `3 skipped` is byte-identical whether the developer never ran
`download-stories15M.sh` -- designed, harmless, exactly what `examples.yaml` promises -- or
has the fixtures on disk at the pre-rename path, in which case a leg someone believes they
certified did not run. Its designed meaning and its defect meaning produce the same integer,
so in isolation it is unreadable by construction. It took two tables in one file, from two
boxes days apart, to make the delta visible, and it was visible for hours before anyone read
it as a delta.

**Dorian's three were the relocation case, not the designed one.** Determined 2026-09-05
from this session's own evidence rather than a fresh run: when the pull that hit the tracked
binaries was blocked, the fixtures were found on disk at `examples/llama2/stories15M.bin`
and `tokenizer.bin` in the MAIN tree, while `examples/llm/` held only `stories260K.bin` and
`tok512.bin`. So at `6a319b6a` the files were present and the leg looked one directory away.

Which lands on the acceptance run itself: **`.todo/682`'s acceptance was read as "39/0/0,
identical to the pre-rename baseline", and the equality held while the composition did
not.** The three legs that did not run are the ones that load the renamed example's real
60 MB checkpoint -- the legs most specific to the change being accepted. This is 709 Part
3's "a step that reports success is not evidence until someone has watched it report
failure", reached by a different road: nobody needed to watch it fail, only to compare its
skip count against the baseline's, and the baseline was two tables up in the same file.

**And the equality was not a coincidence that hid the change -- it was CAUSED by the
change.** But by a narrower mechanism than "the legs contributed nothing to a passing
total", which was the first form written here and is wrong. **Surefire counts a SKIPPED test
in `Tests run`.** Measured on both boxes, from report files rather than reasoned from the
counting rule:

```
Tests run: 57, Failures: 0, Errors: 0, Skipped: 57 -- in am.ik.gpu.GpuTest
Tests run: 54, Failures: 0, Errors: 0, Skipped: 54 -- in am.ik.gpu.MetalGpuTest
```

A class that executes nothing contributes its full count to `Tests run`. So there are two
invariances and only one of them is dangerous:

- **Failures and errors** are invariant under the removal of any test that passed -- on a
  green run, the whole population. True of deletion and skipping alike.
- **`Tests run`** is invariant under SKIPPING only. Deletion moves it.

Had 682 deleted the three legs the row would have read `36`, and the delta would have been
in the number everyone actually reads. It read `39` because they were skipped. **Skipping
preserves the headline count while removing the coverage; deletion removes both** -- so
skipping is strictly the more dangerous mode, and it is the one the harness chose for an
absent fixture. That choice is right for the developer it was written for and hides a
composition change from every reader of the total.

Which is why the failure does not read HIGH when the instrument breaks. It reads
**unchanged**, and unchanged is worse: a high reading invites a second look, an unchanged
one is what the reader was hoping for. `39 = 39` was received as confirmation. `36` would
have been received as a question. **An acceptance slice whose composition can shrink
silently reports equality most convincingly exactly when it has stopped testing the thing.**

**How the cause was recovered is not a method and must not be read as one.** `6a319b6a` is
gone from the working tree, and the question was never "does the leg skip today" but "what
did the box hold on the day the row was written". A run today would have measured a
different day and looked authoritative doing it. The answer survived only because the
blocked pull happened to expose the old path's disk state inside the same session. **A skip
count's meaning is a property of a box at a moment, and nothing in the record captures the
box.** Two tables gave the delta; only a live session held the cause. Anything built on this
should either record the fixture state beside the count or accept that the count is
unreadable after the box moves on. **The cheap version must not be lost behind the thorough
one**: the skip count is ALREADY the signal -- it moved 0 -> 3 while the total held -- and
what is missing is only that nobody compares it against the prior run of the same slice.
Recording fixture state is the thorough version and needs new plumbing; the comparison needs
none and would have caught this one.

## Dorian's skip census, 2026-09-05 -- the input the 87 accounting needs

Recorded here rather than in `.todo/670` because rule 9 puts the fact in the child. The
umbrella's 87 accounting should point at this table.

`target/surefire-reports/` from the 08:02-08:17 run at `b87aed25`: **26359 run, 0 failures,
0 errors, 276 skipped, 231 reports.** The 26359 is the contaminated total this item is
about; the skip count is not, and that is itself a check -- **dorian read 276 skipped on
2026-09-03 as well, so the contamination did not move it.** Extra worktree `.lisp` files RUN;
they do not skip.

**So dorian's 2026-09-05 skip total is 276, not 261, and the 15 is a genuine unexplained
residual. It stays open.** The date-mismatch hypothesis is refused by this number.

Complete per-class list, summing to exactly 276 (no class omitted):

| class | run | skipped |
|---|---|---|
| `am.ik.gpu.GpuTest` | 57 | 57 |
| `am.ik.gpu.MetalGpuTest` | 54 | 54 |
| `am.ik.rontolisp.eval.LinalgGpuTest` | 40 | 40 |
| `am.ik.rontolisp.codegen.jvm.JvmLinalgGpuAccelCompilerTest` | 37 | 22 |
| `am.ik.rontolisp.eval.SceneOffscreenRenderTest` | 18 | 18 |
| `am.ik.rontolisp.e2e.PostmodernE2eTest` | 14 | 14 |
| `am.ik.rontolisp.e2e.LackEcosystemE2eTest` | 10 | 10 |
| `am.ik.rontolisp.e2e.ClackE2eTest` | 9 | 9 |
| `am.ik.rontolisp.e2e.MitoE2eTest` | 9 | 9 |
| `am.ik.rontolisp.codegen.jvm.JvmObjcInteropCompilerTest` | 12 | 8 |
| `am.ik.rontolisp.eval.ObjcInteropTest` | 8 | 6 |
| `am.ik.rontolisp.e2e.LackEcosystemWasmE2eTest` | 6 | 6 |
| `am.ik.rontolisp.e2e.WarE2eTest` | 5 | 5 |
| `am.ik.rontolisp.codegen.wasm.WasmLispCompilerIntegrationTest` | 1483 | 5 |
| `am.ik.rontolisp.e2e.NingleE2eTest` | 4 | 4 |
| `am.ik.objc.ObjcNativeImageForeignConfigTest` | 3 | 1 |
| `am.ik.rontolisp.codegen.jvm.GpuOfferDifferentialTest` | 2 | 1 |
| `am.ik.rontolisp.compiler.GlImportObjectTest` | 2 | 1 |
| `am.ik.rontolisp.compiler.HostGlueEmitterTest` | 17 | 1 |
| `am.ik.rontolisp.DocExamplesTest` | 2127 | 1 |
| `am.ik.rontolisp.e2e.JvmLibraryMethodSizeTest` | 2 | 1 |
| `am.ik.rontolisp.e2e.JarMavenConsumerE2eTest` | 1 | 1 |
| `am.ik.rontolisp.e2e.ServeConditionCatchComponentE2eTest` | 1 | 1 |
| `am.ik.rontolisp.e2e.ServeMethodCaseComponentE2eTest` | 1 | 1 |

Closing the 15 needs GB10's equivalent list, not more of dorian's. The named breakdown so far
(+119 dorian, -17 GB10) uses three dorian classes and two GB10 ones; this table has 24
skipping classes, and `MetalGpuTest`'s 54 is the largest term nobody has placed -- it should
cancel if GB10 also skips it, and if it does not, it is bigger than the residual. **Diff the
two lists class by class rather than reasoning about which classes ought to differ.**

A caution earned today: **an explanation that fits an ADJACENT quantity is the most expensive
kind of wrong, because it closes the item.** The 87 is a difference of SKIP counts, so the
worktree contamination -- which changes what RUNS -- cannot reach it, and neither can an
argument about what `Tests run` includes. An unexplained 15 stays visible and irritating
until someone resolves it; a 15 explained by a real mechanism that describes a different
measurement is finished, filed and unrecoverable. Such a mechanism is always available,
because quantities in one report are correlated by construction.

## Do

1. Add a filter for `/.claude/` -- one line, beside the two that exist. Prefer excluding
   the whole `.claude/` directory rather than `worktrees` specifically, since anything the
   harness puts there is equally foreign. The existing `ansi-test/suite/` comment already
   states the principle: *"a developer who ran `ansi-test/fetch.sh` must not get a
   different verdict from `./mvnw test` than one who did not"*. A developer who has agent
   worktrees must not either.
2. Pin it: assert the corpus size is within a small factor of `git ls-files '*.lisp'`, or
   assert no selected path contains `/.claude/`. Without a pin this returns the first time
   someone adds a directory the walk does not know about -- the same shape the filter list
   already has twice.
3. **Separately, clean up the 25 stale worktrees** -- they are finished agents' trees from
   earlier sessions, ~21470 Lisp files and whatever else. Not part of the fix; the fix must
   work with them present.

## Not in scope

- Whether the suite should format the repo's own Lisp at all. It should; that is
  `.kb/formatter.md`'s invariant and it works. The defect is the corpus boundary.
- Re-running `.todo/670`'s certified numbers. They are green either way -- **zero failures
  on both boxes, 231 report files on both** -- and only the COUNTS are incomparable. Say so
  in 670 rather than re-running anything.
