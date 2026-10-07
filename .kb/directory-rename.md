# Moving a Directory: what breaks outside the rename's own diff

**Invariant: a rename moves paths, and what breaks is the machinery AROUND the paths** --
code that reads the tree by walking it, prose that cites it, an ignore rule that protects
by location, a fixture looked up under it. None of that appears in the diff that moved the
paths, so no review of that diff can find any of it, however careful.

`.todo/682` moved `examples/llama2` to `examples/llm` on 2026-09-05 (`3506e227`). The
rename was correct. It broke four separate things, every one outside the renamed paths.
**Not one was found by a test failing:** two by reading a number against a second number,
one by the build, one by a `git pull` that could not proceed. Full account, with the
evidence for each: `.todo/708`, closed 2026-09-06 --
`git show 97c85518~:.todo/708-the-formatter-corpus-walks-agent-worktrees.md`.

This is a standing property, not one incident. Four ignore-bearing directories have moved
in this tree: `examples/wasm-size` -> `size-report` (`3e2540cf`), `examples/wit-world` ->
`examples/wit/world`, `examples/hiragana` -> `examples/browser/hiragana`, and
`examples/llama2` -> `examples/llm`.

**Every count below is a command, not a number.** The one enumerable count 708 wrote down
-- "the tree has 18 directory-local `.gitignore` files" -- was already wrong on the day it
was written; see "What decays" at the end.

## The checklist

Mechanical on purpose. Each item is invisible to judgement, so none of them can be
replaced by paying attention.

### 1. Move the WHOLE directory, or account for what stayed

`git mv <old> <new>` on the **directory** renames it on the filesystem, so untracked and
ignored files travel with it. A per-file rename -- `for f in $(git ls-files old); do git mv
"$f" new/...; done`, or any script driven off `git ls-files` -- moves only the tracked
half and **leaves the untracked files behind at a path whose ignore rule has just left**.

That is what 682 did. `examples/llama2/.gitignore` listed `stories15M.bin` and
`tokenizer.bin`; the rule moved to `examples/llm/.gitignore`, the two untracked 60 MB
artefacts did not, and the next `git add -A` on another lane staged 61 MB onto develop
inside a commit whose message says it files a todo (`97b81823`; untracked again in
`e34da35c`, still in history). **A directory-local ignore rule protects by LOCATION, so
moving the rule stops protecting whatever stayed -- and what stayed is invisible to the
rename precisely because being ignored is what kept it out of the rename.** Afterwards
`git status` shows the files as ordinary new untracked files, with nothing to suggest
otherwise.

**The check, at the one moment they are visible -- after the move, before the next
`git add`:**

```bash
git status --porcelain -uall <old-path>
```

**`-uall` is load-bearing.** Reproduced in a scratch repository: after a per-file rename,
plain `git status --porcelain` collapses the residue to a single line

```
?? old/
```

-- no filename, no count, no size, and it sits under the `R` lines of the rename itself,
which is what makes it read past. With `-uall` the same state reports `?? old/big.bin`. The
subsequent `git add -A` stages it as `A old/big.bin`.

Which directories can do this:

```bash
git ls-files '*/.gitignore'          # 17 today; the root .gitignore is NOT one of them
```

The payloads are large by construction, because size is why the rule exists: 60 MB
checkpoints (`examples/llm`), ~55 MB of MNIST idx dumps
(`examples/deep-learning-from-scratch`), an ~80 MB dataset cache
(`examples/browser/hiragana/tools/k49`), the ANSI suite checkout (`ansi-test`), and whole
build trees -- `node_modules/`, `dist/`, `/target/`, `rust-*/target/`, `out/` -- under
`examples/{browser/wit-component,cloudflare-workers,count-vowels,gae,wasmcloud}`,
`examples/wit/{lisp-calls-rust,pipeline,rust-calls-lisp,world}`, `bench-report`,
`size-report` and the two vendored suites under `src/test/resources/`.

**Undoing it is free only on the box that makes it.** Untracking a file DELETES it for
every puller who had it, because to them it arrives as an ordinary tracked deletion.
`e34da35c` removed 61 MB from the other box's working tree on its next merge -- not
untracked, deleted -- and the loss surfaced there only as a larger skip count (item 4
below). If the untrack is the right call, say so where the pullers will read it before
they pull.

### 2. Citations and members are two different searches, and a rename needs both

```bash
git grep -n '<old-path>'      # files that CITE the path
git ls-files '<old-path>'     # files that ARE under it
```

Neither substitutes for the other. `examples/llama2/.gitignore` appears in no content
search -- loose, anchored, relative or absolute -- because its NAME is in the renamed path
and its CONTENTS mention nothing. Only the member listing finds it. Confirmed on the
current tree: `git grep -l examples/llm` returns 54 files and `examples/llm/.gitignore` is
not among them, while `git ls-files examples/llm` lists it first.

And grep the BARE directory name as well as the full path, because a citation that
resolves at run time rarely spells the whole thing. 682's functional half was
`examples.yaml`'s nine `path: llama2/...` and five `workDir: llama2` -- resolved against
`examples/` by `ExamplesE2eTest`, invisible to any `examples/llama2` pattern, and, since
`ExamplesE2eTest` is skipped by `./mvnw test`, capable of leaving a green tree and a suite
that cannot find its examples.

The bare name is also the one that needs a human afterwards, because it matches things that
must NOT move. `llama2` named three separate things here -- the directory, llama2.c the
upstream C project (38 occurrences), and Llama 2 the architecture (27) -- plus two closed
`.todo` item names whose history rows cannot move. A bare-word substitution would have
produced `llm.c` and `Llm 2`.

Not every citation the two searches turn up should be rewritten. **A dated measurement
record keeps its old paths**, with one line saying why: rewriting them would make the
measurement claim it was taken against a tree that did not exist when it ran. 682 froze
`.todo/artefacts/123-gpu-acceleration/README.md` and `.todo/artefacts/672-*/README.md` on
that ground, and rewrote `.todo/artefacts/480-*/Gate.java` on the opposite one -- its path
was navigation rather than evidence, and already stale. **The line is EVIDENCE against
NAVIGATION, not record against source**: the same frozen README's pointer at a sibling
record is navigation and moves with it.

**The machine half of this item is `PathCitationTest`** (`.todo/710`, 2026-09-06). It fails
when a repository-rooted path cited in a backtick span (`.kb/**`, `.todo/*.md`, `doc/**`,
the root notes) or a javadoc `{@code ...}` no longer resolves, which is the citation a
human's grep missed. Its own judgement call is stated at the top of the file: what counts
as a citation, why `src/` alone is not a checked prefix (a note about a consumed ASDF
system cites `src/strings.lisp`, which is someone else's tree), and why a `.todo/NNN` item
reference is exempt where a `.todo/artefacts/NNN-*/` path is not. That exemption holds in
the notes only: outside `.kb/` and `.todo/` an item number (`.todo/NNN`, `todo NNN`,
`todo-NNN`) fails `noTodoItemIsCitedOutsideTheNotes` -- a comment there states the fact or
points at the `.kb/` file holding it, since the number means nothing once the item closes
(swept from source, tests and examples on 2026-10-07). A path the tree
deliberately no longer has goes in its `ABSENT_ON_PURPOSE` list with its reason, and a
second test fails if one of those names comes back.

### 2b. A move that changes DEPTH rewrites the paths INSIDE what moved

The four renames above kept their depth, so nothing inside the moved files had to change.
`.todo/NNN-title/` -> `.todo/artefacts/NNN-title/` (`.todo/710`, 2026-09-06) did not, and
every `../../` inside the 16 moved directories -- `../../target/rontolisp-...-exec.jar` in
a probe's run instructions, `../../.kb/gpu.md`, `cd ../..` in a `bench.sh` -- silently
began resolving one level short. **`git grep` for the old path finds none of them**, because
a relative path does not contain the directory it lives in; only walking the moved files
does. Sibling references (`../471-.../` from `.todo/artefacts/649-.../`) survive a uniform
move untouched, which is what makes the broken ones easy to miss among them.

```bash
grep -rn '\.\./' <new-path>        # then resolve each one against its own file
```

### 3. Re-apply the formatter -- a shortened path changes wrapped line lengths

A path inside javadoc or a comment participates in line wrapping like any other text, so
changing its LENGTH re-flows the paragraph around it. `pom.xml` binds
`spring-javaformat:validate` to the `validate` phase, so a stale wrap fails **every**
build, not just a formatting check.

682 shortened `examples/llama2` (15 chars) to `examples/llm` (12) and re-flowed javadoc in
`src/main/java/am/ik/rontolisp/eval/LinalgBlas.java` and
`src/test/java/am/ik/rontolisp/eval/TokenizersLibraryTest.java` -- three paragraphs whose
line breaks moved with no word changed beyond the path. Nobody would predict a formatting
consequence from a rename.

```bash
./mvnw spring-javaformat:apply
```

Then the Lisp half, from `.kb/session-workflow.md`'s "After task completion" -- a renamed directory under
`examples/` is inside that command's argument list.

### 4. A fixture LOOKUP anchored at the path, and the skip count that reports it

`ExamplesE2eTest.stageWorkspace` resolves each `workFiles` entry under
`EXAMPLES_DIR.resolve(example.workDir())`, and a missing file calls `abort()` -- a JUnit
assumption abort, i.e. a **skip**, not a failure. That is deliberate: a developer who never
ran `examples/llm/download-stories15M.sh` must not be failed by its absence. It also means
**the rename alone changed which legs execute on any box that had the fixtures**: after
682, `workDir: llm` looks in `examples/llm/` while the files sit at `examples/llama2/`, and
three `stories15M` legs stopped running with the files physically present on disk.

682's own commit message (`3506e227`) records the acceptance: "`-Drontolisp.examples.only=llm/`
is 39 legs, 0 failures, 3 skipped, matching the pre-rename baseline exactly." The equality
held on every quantity anyone compared while three legs silently stopped. **The only cell
that moved is the skip count, 0 to 3** -- and both figures were sitting in adjacent tables
in one file, `.todo/670`, hours before anyone read them as a delta.

**The equality was caused by the change, not a coincidence that hid it:** surefire counts a
SKIPPED test in `Tests run`, so skipping preserves the
headline count while removing the coverage, where deletion would have moved it to 36 and
been noticed. Failures and errors are invariant under removing any test that passed;
`Tests run` is invariant under SKIPPING only. **The mode the harness chose for an absent
fixture is the strictly more dangerous one.**

So:

```bash
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true \
       -Drontolisp.examples.only=<dir>/ test
```

...and **read its SKIP COUNT against the prior run of the same slice.** A skip count is
only a signal against a prior skip count; `3 skipped` is byte-identical whether the
developer never ran the download script (designed, harmless) or has the fixtures at the
pre-rename path (a leg someone believes they certified did not run). Its designed meaning
and its defect meaning produce the same integer, so in isolation it is unreadable by
construction. **Do not say the suite was silent; that invites the fix "make it print", and
it already prints.** What is missing is only the comparison.

The comparison is also all that is available afterwards. A skip count's meaning is a
property of a BOX at a MOMENT -- which fixtures were on disk, at which path -- and nothing
in the record captures that, so a run taken today measures today and looks authoritative
doing it. Either record the fixture state beside the count, or accept that the count is
unreadable once the box moves on.

Note the trailing slash. `-Drontolisp.examples.only=` is a plain SUBSTRING match on the
example's path: `only=llm` also matches `ml/tiny-llm.lisp` and `examples/llm-from-scratch/`.

### 5. A corpus defined by WALKING is defined by the box, not by the commit

`LispFormatterTest.repositoryLispSources()` is the only `Files.walk(Path.of("."))` in the
tree -- every other walker (`PackageCycleTest`, `DocExamplesTest`, `ExamplesE2eTest`,
`FormatCommand`, `docs-tool`) starts at a named root. It takes every `.lisp`/`.asd` under
the working directory minus three prefixes: `/target/`, `/ansi-test/suite/` and
`/.claude/`. Pinned since `.todo/708` by
`repositoryCorpusStaysWithinASmallFactorOfTrackedSources`, which asserts no selected path
comes from `/.claude/` and bounds the corpus at 3x `git ls-files '*.lisp' '*.asd'`.

Two consequences for a rename:

- **A directory moved INTO or OUT OF a filtered prefix changes the test count with no diff
  to the test.** Before the filter landed, 25 stale agent worktrees put 21470 foreign Lisp
  files in the corpus and `LispFormatterTest` alone ran 17014 of one box's 26359 tests.
  Extend the filter list when a new foreign directory appears; the pin exists so this is a
  failure rather than a silent inflation.
- **This composes with item 3 and is why 708 exists.** A rename can change formatting, and
  the walk reads other lanes' WORKING TREES -- so a not-yet-formatted `.lisp` file sitting
  mid-edit in one lane's worktree fails the MAIN tree's suite, in a file the main tree does
  not contain, naming a path the reader has never heard of. **A worktree is not private to
  the lane that owns it.**

## What decays, and why the counts above are commands

`.todo/708` recorded "the tree has 18 directory-local `.gitignore` files" and enumerated
them as "seven under `examples/wit/`". Checked against the tree: `git ls-files
'*/.gitignore'` returns **17**, and did at 708's own commit (`8f92dfcc`) too. The 18 is
`git ls-files '*.gitignore'`, which also matches the ROOT `.gitignore` -- and the root one
is the single file this whole card does not apply to, since it protects by repository
rather than by location. The `examples/wit/` group is four, with two more under
`examples/browser/`.

The second one decayed while this card was being written. 708 cited the two certification
rows above as "`.todo/670` line 186" and "line 159" -- correct when written, and by
2026-09-06 both the line numbers and the rows themselves were gone, 670 having been
compacted twice to the record plus pointers, and closed on 2026-09-10. The durable copies are 708's own quotation of
them and `3506e227`'s commit message, which is why item 4 cites those instead.

An off-by-one nobody can act on is harmless, and a stale line number costs a minute. They
are worth a section because they are **the same failure as the five above, one level up.**
A fact recorded for one incident is a measurement of a tree that has since moved, and the
whole subject of this card is machinery no diff shows you. Run the command; cite the commit,
not the line.
