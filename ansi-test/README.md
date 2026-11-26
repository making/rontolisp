# ANSI conformance measurement

Runs the [ANSI Common Lisp test suite](https://gitlab.common-lisp.net/ansi-test/ansi-test)
against the rontolisp **interpreter** and writes `results/interpreter.md`.

```bash
ansi-test/fetch.sh      # clone the suite at the pinned revision into suite/ (git-ignored)
ansi-test/measure.sh    # run every chapter, rewrite results/interpreter.md
ansi-test/measure.sh cons numbers   # one or more chapters only
```

The report names the suite revision it measured; bump it in `fetch.sh`.

## Who runs the whole suite

`.github/workflows/ansi-report.yaml`, daily, and it commits the refreshed
`results/interpreter.md` with `[skip ci]` when -- and only when -- a count moved.
The checked-in report is therefore a series measured by one machine with one
stall window, which is what makes a moved number mean something. Run the
workflow by hand (`workflow_dispatch`) after a change expected to move it.

Locally, measure a chapter at a time: a subset run writes `results/partial.md`
and leaves the baseline untouched, so it cannot overwrite the series with a
number taken on a different machine.

## How a run works

`AnsiCompliance` (parent) starts one child JVM per chapter (`AnsiChapterRunner`), which
loads `rt-shim.lisp` and then the chapter's files in the order the suite's own `load.lsp`
loads them, preceded by the aux files `gclload1.lsp` loads.

Two properties of the driver decide what the numbers mean:

- **A form is the unit, not a file.** Each top-level form is split out of the source
  (`TopLevelSplitter`), read, and evaluated on its own under `catch (Throwable)`. A form
  the reader rejects, or one that leaves a raw Java exception or a `StackOverflowError`,
  costs exactly that form -- without this, one such form early in a 700-test file would
  hide every test behind it and the chapter would score zero for one gap. When the failing
  form is itself a `deftest`, a raw exception the shim's own `handler-case` cannot catch
  (an `UnsupportedOperationException`, or a condition type it does not recognize as
  `error`) is attributed to that one test as an `error` line, exactly like a caught one --
  it costs nothing beyond the test it came from. Only a non-`deftest` form (an aux
  `defparameter`/`defun`) that fails this way still costs every test behind it, since the
  driver has no test name to charge it to.
- **A hang is the only thing that costs more than one form.** The child prints a progress
  marker per form; when the parent sees no output for `-Drontolisp.ansi.stall` seconds
  (45 by default) it kills the child, reads the form index off the last marker, and
  re-runs the chapter with that form skipped.

Each chapter runs in its own scratch directory under `results/work/` (git-ignored, like
`results/logs/`): the `files` and `streams` chapters create, rename and delete files in the
working directory, and a run must not leave that in the repository root.

`rt.lsp` itself is not loaded -- see the comment at the top of `rt-shim.lisp` for why. The
shim supplies `deftest` and the RT specials the aux layer reads; everything else the tests
call comes from the suite's real `auxiliary/*.lsp`.

## Reading the report

- `pass` / `fail` / `error` count TESTS: a test that returned the wrong values fails, one
  that signalled counts as an error. The distinction is worth keeping -- a wrong value is a
  semantics bug, a signalled error is usually a missing operator.
- `top-level forms lost` counts FORMS the driver could not read, could not evaluate, or had
  to skip. Every test such a form would have defined is missing from the other columns, so
  a chapter with a high count is measured optimistically.

## What the count is

The denominator counts EXECUTED `deftest` forms -- a macro that generates several tests
(`do-tests`-style, or a `dotimes` around a template) counts each one, not the one static
`deftest` call in the source. That is why a chapter's `tests` column runs well above a
plain `grep -c '(deftest' <chapter>/*.lsp` of its source: at the pinned revision
(`ca06bd919661af162c67407c9d994e881870bdb3`) `structures` holds 147 static `deftest` forms
but executes 1,007, `reader` 167 static / 575 executed, `conditions` 321 static / 673
executed -- a factor of up to seven.

**This figure is not comparable with another implementation's published ANSI number.**
Beyond how much of the standard each implementation actually gets right, the two counts
differ in ways that have nothing to do with conformance:

- a different suite revision -- the suite has no numbered releases, so "the ANSI test
  suite" means whatever commit was checked out; this repo pins one commit and prints it in
  every report;
- a different unit -- the suite's own RT framework keys its test database by name, so a
  redefinition (a duplicate `deftest` name) REPLACES an entry rather than adding one; a
  per-form report like this one counts every execution;
- macro-generated tests, per the paragraph above -- a chapter that unrolls its `deftest`
  macro more aggressively reports a denominator many times another driver's, independent of
  how much of the chapter passes.

## What the numbers are not

The suite tests full ANSI CL, which rontolisp does not set out to be. A failing test is a
statement about the standard, not automatically a bug worth fixing; the value of the report
is the ranked list of what is missing, and the ability to see it move.
