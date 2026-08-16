# 395. A failing rove run exits 0, on every backend

Difficulty: Medium

`.todo/372` made rove real on all four backends and `.todo/362` gave the tree a
real `uiop:quit`. What is missing is the join: running a rove test FILE says
nothing to the shell. A CI step, a `make test`, a git hook -- anything that reads
`$?` -- calls a red suite green.

## Reproduced 2026-08-16 (rove 0.10.0 from the quicklisp cache)

`demo.lisp`, the shape a user writes first (rove's README + FAQ):

```lisp
(ql:quickload :rove)

(defpackage #:rove-example
  (:use #:cl
        #:rove))
(in-package #:rove-example)

(deftest example-test
  (ok (= 1 2)))

(run-suite *package*)
```

The report is correct everywhere ("× 1 of 1 test failed"), and the status is 0
everywhere:

| how it was run | exit |
|---|---|
| `rontolisp demo.lisp` | **0** |
| `rontolisp demo.lisp -o Demo.class` + `java Demo` | **0** |
| `rontolisp demo.lisp -o demo.wasm` + `wasmtime run -W gc=y -W exceptions=y demo.wasm` | **0** |
| `rontolisp demo.lisp -o demo-comp.wasm --component` + `wasmtime run -W gc=y -W exceptions=y` | **0** |

Ending the same file with `(uiop:quit (if (run-suite *package*) 0 1))` instead
answers **1** (verified on the interpreter and on Preview 1), so the exit
machinery is not what is missing -- the ergonomics are. Nothing about this is
backend-specific: a top-level form is a statement on every backend, so the
value `run-suite` returns is dropped, exactly as `sbcl --script` drops it.

## The work

Add the CLI entry point `.todo/372` named as a follow-up ("the roswell `rove`
CLI script -- `rontolisp test FILE` mirroring it -- is a reasonable follow-up
once 374 lands"). 374 landed.

`rontolisp test TARGET...`:

**Upstream already specifies this command**: rove ships `roswell/rove.ros`
(present in the quicklisp cache, NOT in the vendored test copy). Read it before
designing anything -- it is the spec, and every piece it needs now exists here:

- the verdict is read from `rove/core/suite:*last-suite-report*` with
  `(every #'rove:passedp ...)` AFTER the load, so the runner never re-runs what
  the file already ran;
- for a `.lisp` target the system name is the file's `defpackage`, read with
  `asdf/package-inferred-system::file-defpackage-form` under
  `(*print-case* :downcase)` -- `.todo/373` (skip to the defpackage) and
  `.todo/041` (`*print-case*`) both landed, so this reads here;
- it then does `asdf:load-system` + `asdf:test-system` (`.todo/374`), and falls
  back to `(rove:run system-name)` only when `*last-suite-report*` is empty;
- it binds `rove/core/suite:*rove-standard-output*` to the real stream behind
  the synonym stream (`synonym-stream-symbol` + `symbol-value`, `.todo/377`) and
  swallows the program's own output into a broadcast stream;
- options are `-r/--reporter spec|dot|none` and `--disable-colors`; failure is
  `(uiop:quit -1)`.

Mirror it, with two deliberate differences to note in the `.kb`: exit **1**, not
`-1` (which is 255 after the 8-bit mask `.todo/362` applies), and no `COVERAGE`
arm (`sb-cover` is a non-goal). A plain `.lisp` file with no `defpackage` (the
demo above HAS one) still has to work -- decide its fallback and document it.

- **TARGET is a `.lisp` file**: load it, take the verdict from
  `*last-suite-report*`, print the report, exit 0 / 1. The demo above must work
  UNCHANGED, running its tests exactly once.
- **TARGET is a system name or a `.asd`**: `(rove:run :my-app/tests)` /
  `asdf:test-system`, the entry point `guides/testing.md` teaches, same exit
  contract. `--system-path` already resolves the registry.
- **With `-o out.class` / `-o out.wasm` (+ `--component`)**: emit the artifact
  with the SAME contract instead of running -- i.e. compile the program plus the
  standard epilogue, so the class and both wasm modules exit 1 on failure. This
  is the half the user asked for ("wasm も jvm も同じ"): `uiop:quit` is real on
  all four backends since `.todo/362`, so the epilogue is portable and no
  backend needs its own path. Document that the wasm legs still need
  `-W exceptions=y` (rove's `handler-bind` forces EH mode).
- `--style spec|dot` (rove's reporters) if it costs nothing; `(setf
  rove:*enable-colors* nil)` when stdout is not a terminal, so a CI log is not
  full of escape codes -- today the user gets raw ANSI in a pipe.

Decide and record: what exit code "no test found" gets, and what happens when
the file signals before any test runs (an uncaught error already exits 1).

## Is `uiop:quit` in the test file "the right way"? No

It is the MECHANISM, not the usage. Upstream never puts it in a test file:
`rove.ros` calls it, and a `.asd`'s `:perform (test-op ...)` leaves the exit to
whoever invoked ASDF. A `uiop:quit` inside the test file kills the process the
moment anything ELSE loads that file -- another suite, the REPL, a system that
depends on it -- which is why the runner owns the exit and the file owns only
the tests. Writing it by hand is right in exactly one place: a one-line runner
script of your own (`(uiop:quit (if (rove:run :my-app/tests) 0 1))`), which is
what `guides/testing.md` shows and what an example that IS its own runner does
(`.todo/392`). Say this in the guide -- the question is the natural one to ask
after seeing the exit code.

## Deliberately NOT changed

A plain `rontolisp FILE` keeps CL semantics: the value of the last top-level
form is dropped and the status stays 0
(`.kb/toplevel-statement-values.md`), matching `sbcl --script`. A program that
wants a status says `(uiop:quit ...)`. If the discoverability gap still bites,
the acceptable widening is a hint on stderr when a plain run ends with rove
failures ("N tests failed; `rontolisp test` exits non-zero") -- a message, never
a silent change of status. Weigh it; do not ship a status change here.

## Acceptance

- `rontolisp test demo.lisp` (the file above, verbatim) prints the report and
  exits 1; make the assertion pass and it exits 0.
- The `-o` form: the emitted `.class`, Preview 1 `.wasm` and `--component`
  `.wasm` all exit 1 for the same file and 0 when it passes. Verified by hand on
  all four backends AND pinned -- `RoveE2eTest` / `AsdfLibraryE2eSupport`
  compares stdout lines today, so the exit status needs a hook of its own.
- `printUsage()` gains the subcommand (beside `format`), and `rontolisp test
  --help` explains the target forms and the exit contract.
- `doc/{en,ja}/guides/testing.md` leads with the subcommand and keeps the
  `(uiop:quit (if (rove:run ...) 0 1))` recipe as the in-program form; both doc
  trees change together. `.kb/asdf.md`'s rove paragraph (or a new `.kb` note)
  records where the verdict is read from and why.
- `./mvnw test` stays green; no ci-spec case (the driver concatenates cases into
  one program and cannot supply a file target).

## Non-goals

- Coverage (`COVERAGE=1`, sb-cover) -- still out, as in `.todo/372`.
- A watch mode, test filtering by name/pattern beyond what rove's `run*`
  already gives, or a JUnit XML reporter.
- Changing how `examples/` spell their self-tests (`.todo/392`): those are
  programs and keep the in-program `uiop:quit` form.
