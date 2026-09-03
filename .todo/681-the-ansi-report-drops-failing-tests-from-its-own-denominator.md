# The ANSI report drops failing tests from its own denominator

Difficulty: Low

`ansi/AnsiCompliance.ChapterResult.parse` counts `%%%READ` and `%%%EVAL` markers
into `reasons` (the failure-reason table) but NOT into `pass`/`fail`/`error`, and
`total()` is `pass + fail + error`. A form that throws where the shim's
`handler-case` cannot catch it -- every raw Java exception, see `.todo/680` --
therefore vanishes from every column instead of counting as a failing test.

The committed `results/interpreter.md` says:

> **9,109 / 18,063 tests pass (50.4%)** ... 7 top-level forms could not be read,
> 1,929 could not be evaluated, 3 did not terminate

Those 1,939 forms are overwhelmingly ONE `deftest` each. Per chapter,
`tests-run + forms-lost` lands on the chapter's static `deftest` count (or just
above it, where a chapter generates tests from a macro):

| chapter | static deftest | run | lost | run+lost |
|---|---:|---:|---:|---:|
| sequences | 3,136 | 2,402 | 903 | 3,305 |
| cons | 1,654 | 1,702 | 105 | 1,807 |
| arrays | 1,254 | 1,277 | 31 | 1,308 |
| symbols | 1,149 | 1,138 | 24 | 1,162 |
| objects | 852 | 695 | 238 | 933 |

So the honest headline is **9,109 / 20,002 = 45.5%**, not 50.4%. The README
already says a chapter with a high lost count "is measured optimistically"; the
number in bold does not.

## How to do it

1. In `AnsiChapterRunner.runForm`, when `evaluator.eval(form)` throws and the
   form's head is `deftest`, print `ERROR <name> <message>` instead of (or as
   well as) `%%%EVAL` -- the name is already reachable via the shim's
   `%ansi-test-name` rule (a `deftest` name may be `(name :notes ...)`). The
   form is read before it is evaluated, so the head is in hand.
2. Leave `%%%READ` and the hang path as lost FORMS -- they genuinely are -- and
   keep `%%%EVAL` for a non-`deftest` form (an aux `defparameter`, a `defun`),
   where one lost form really does cost every test behind it.
3. Then the `top-level forms lost` column means what it claims, and a `.todo/680`
   fix shows up as passes rather than as a smaller loss count.

## Three smaller accounting defects in the same file

- **`auxiliary/ansi-aux.lsp` holds 3 `deftest`s and is in `SuiteLayout.PREFIX`**,
  so it runs in all 25 chapters: 75 duplicate tests in the total. Either drop
  those three or count the file once.
- **Duplicate test names are counted twice.** `types-and-classes` reports 613
  results over 611 unique names; the suite's own RT keys its database by name,
  so a redefinition replaces rather than adds.
- **`(in-package ...)` is skipped and everything runs in `COMMON-LISP-USER`.**
  That is a deliberate driver constraint (the README says why), but it is also
  most of why `packages` reports 6.0% -- 23 `No such package` and 21
  `in-package is only supported as a literal top-level form` in that chapter's
  `%%%EVAL` rows. Either the chapter gets a driver that can enter a package, or
  the report says the number is not a capability measurement.

## And a README paragraph

`ansi-test/README.md` should say what the count IS -- executed `deftest` forms,
so a macro-generated test counts individually -- and that the figure is NOT
comparable with another implementation's published ANSI number. The two differ
by more than the implementations do:

- a different suite revision (this one pins upstream `ca06bd9`, 17,225 static
  `deftest`s);
- a different unit (the suite's own RT counts registered unique names; a
  per-file report counts static names in the source text);
- macro-generated tests, which move a chapter by a factor of seven
  (`structures`: 134 static, 960 executed; `reader`: 165 / 569;
  `conditions`: 310 / 665).

The report is a time series about rontolisp, and only that.

Found while re-evaluating the ANSI measurement method (2026-09-03). Siblings:
`.todo/679` and `.todo/680`; this item is what makes either of them visible in
the report.
