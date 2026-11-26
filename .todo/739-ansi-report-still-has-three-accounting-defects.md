# The ANSI report still has three accounting defects

Difficulty: Low

Found while closing `.todo/681` (the report dropping failing tests from its own
denominator, fixed separately). `.todo/681`'s "How to do it" fixed the
`%%%EVAL`-drops-a-deftest bug; these three were filed there too but are
independent design decisions, not part of that fix. Measured fresh against
`ansi-test/results/interpreter.md` after `.todo/681` landed
(10,807 / 19,459 pass, 2026-09-08):

## 1. `auxiliary/ansi-aux.lsp` runs in every chapter

It holds 3 `deftest` forms and is in `AnsiChapterRunner.SuiteLayout.PREFIX`, which
every chapter's `chapterFiles()` prepends -- so those 3 tests run, and are
counted, 25 times: 75 duplicate results in the suite total. Either drop those
three `deftest`s from the counted total (subtract `3 * (chapters - 1)`) or make
the driver count the file once across the whole run instead of once per chapter.

## 2. Duplicate test names are counted twice

The suite's own RT framework keys its test database by name, so a redefined
`deftest` (there is more than one `FOO.1` etc. across files that both get
loaded) REPLACES an entry rather than adding one -- RT's own denominator would
be the unique-name count. This driver has no such table; every execution is a
result line. Currently `types-and-classes` reports 625 results over 623 unique
names (measured 2026-09-08; `.todo/681`'s original filing said 613/611 -- the
gap is unchanged in kind, moved in count because `.todo/680`/`.todo/681`
recovered more executed forms). Decide whether the report should de-duplicate
by name (matching RT's semantics) or document that it deliberately counts
executions, not names.

## 3. `(in-package ...)` is skipped, so everything runs in `COMMON-LISP-USER`

`AnsiChapterRunner.main` skips every top-level `(in-package ...)` form outright
(a deliberate driver constraint -- the shim's names must stay reachable). That
is most of why `packages` scores lowest of any chapter: 9.8% (measured
2026-09-08, up from 6.0% pre-`.todo/680`), with 33 `No such package` errors in
its 492 tests -- tests that assume they are running in a package the driver
never entered. Either give the `packages` chapter a driver that can actually
enter a package (tracking a shim-reachable-names allowlist across the switch),
or have the report say explicitly that the `packages` number is not a
capability measurement, the way `.kb/error-handling.md`-style notes qualify
other numbers.

## Suggested approach

These are three independent, small decisions rather than one change; a
Sonnet-class model can size each on its own once picked up. None requires
re-deriving the whole report format -- `ChapterResult.parse` /
`ReportWriter` / `AnsiChapterRunner` in `src/test/java/am/ik/rontolisp/ansi/`
are the only files involved for any of the three.

## 4. The reason table mixes per-test and per-form rows (added 2026-09-08)

`ChapterResult.parse` merges into one `reasons` map both `ERROR <test> <msg>`
lines (one per TEST) and `%%%EVAL` / `%%%READ` lines (one per lost FORM, per
chapter). A single aux form that fails in the shared PREFIX therefore contributes
25 rows -- once per chapter -- to the same table a reader uses to rank missing
operators, where every other row is a test count.

Concretely, in the checked-in report `The variable *UNIVERSE* is unbound` reads
202 but is **175 tests plus 27 form losses**; `*NUMBERS*` reads 80 but is 30
tests; `*FLOATS*` reads 65 but is 15. Recomputing the top of the table from
`ERROR` lines alone reorders it.

The fix is the same shape as §1 and §2: either count the two kinds in separate
tables, or label the column. Whoever takes this should re-read `.todo/715` after,
since its ranking is derived from this table.
