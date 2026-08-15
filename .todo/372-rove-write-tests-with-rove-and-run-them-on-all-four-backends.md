# Rove: write tests with rove, run them on all four backends (milestone)

Difficulty: High (the milestone; each sub-item carries its own)

Goal: `(ql:quickload "rove")` loads Eitaro Fukamachi's
[rove](https://github.com/fukamachi/rove) VERBATIM (quicklisp dist
rove-20260101-git, v0.10.0), a test file written in rove's shape runs on the
interpreter, the JVM class, WASM Preview 1 and `--component`, and the spec
reporter prints the same report on all four (modulo durations). Both entry
points work: `(rove:run :my-app/tests)` -- the README's system-driven API,
what `.asd` files call from `:perform (test-op ...)` -- and
`(rove:run-suite *package*)` (README FAQ), plus `run-test`/`run-tests`.

## What the spike established (2026-08-15)

rove was quickloaded (`~/.rontolisp/quicklisp/software/rove-20260101-git/`),
read whole, and a scratch copy patched in NINE files ran `deftest`/`testing`/
`ok`/`ng`/`signals`/`outputs`/`pass`/`fail`/`skip`/`failing`/`setup`/
`teardown`/`defhook`/`diag`/`run-test`/`run-suite` with the `:spec` and `:dot`
reporters on ALL FOUR backends (interpreter output correct; JVM/WASM output
byte-identical to each other but unindented -- row 6). Every patch is a
rontolisp gap; the table is the work. rove's dependencies (uiop, cl-ppcre,
dissect, trivial-gray-streams, bordeaux-threads) all resolve today: cl-ppcre
and dissect load from their real sources (dissect's stack is its empty no-op
interface here, so `:stacks` is always nil), the rest are the built-in shims.

| # | rove needs | rontolisp today | item |
|---|---|---|---|
| 1 | a package-inferred file whose FIRST form is `(in-package #:cl-user)` and whose `defpackage` is second (core/assertion.lisp, core/result.lisp, ...) | "the first form of a package-inferred system's file must be a DEFPACKAGE" -- `(ql:quickload "rove")` dies there | `.todo/373` |
| 2 | `asdf:*user-cache*`, `asdf:registered-systems`, `asdf:component-name`/`-children`/`-sideway-dependencies`, `asdf:system`/`package-inferred-system`/`cl-source-file`/`module` as `typecase` types AND `defmethod` specializers, `find-system` -> a system OBJECT, a runtime `load-system` of an already-loaded system | not external ("use ASDF::X"); `find-system` answers a name string (interpreter) or nil (compile paths); nested `load-system` = call-time error stub | `.todo/374` |
| 3 | `*load-pathname*` bound to the file being loaded when a `deftest` runs (rove's file -> package map, `make-new-suite`, is what `run` walks for a plain `defsystem` test system) | nil at run time on the compile paths -- `(rove:run "foo/tests")` would find no suites there | `.todo/375` |
| 4 | `*package*` READ at run time inside rove's own `set-test`/`package-suite`, and in the expansions of `setup`/`teardown`/`defhook` | folded to the DEFINING package at resolution (`set-test` registers every test under `rove/core/suite/package`, so `run-suite` finds 0 tests); an expansion-level read is "The variable *PACKAGE* is unbound" on the interpreter (`setup` cannot even be evaluated) | `.todo/255` (rove evidence appended) |
| 5 | `(defmethod find-suite ((package package)) ...)` + a `(package-name)` designator method | "DEFMETHOD: unknown specializer PACKAGE" (the TYPE test exists, the specializer does not) | `.todo/376` |
| 6 | an indent-stream defining only `stream-write-char` (+ `stream-line-column`/`stream-start-line-p`/`stream-finish-output`/`-force-output`/`-clear-output`), driven by `fresh-line`/`princ`/`format`/`write-char`/`write-string` | `write-char` on a Gray instance routes to `stream-write-string`, which has NO default over `stream-write-char`; `fresh-line` signals; on the compile paths `princ` bypasses the instance entirely (JVM/WASM report is unindented, newlines lost) | `.todo/252` (rove contract appended) |
| 7 | `(make-synonym-stream '*standard-output*)` as a NON-NIL stream (`(when stream (setf (reporter-stream r) (make-indent-stream stream)))` in `initialize-instance :after`) | answers the NIL designator -> reporter-stream stays nil -> "No applicable method: STREAM-INDENT-LEVEL on NULL" | `.todo/377` |
| 8 | real `macro-function` / `special-operator-p` (`form-inspect`, `form-steps`) | nil stubs -> `(ok (signals ...))`, `(ok (if ...))`, `(ok (equal x '(1 2)))` treat the macro / special form / `quote` as a FUNCTION CALL and evaluate the arguments outside it (the `signals` assertion fails; a quoted `'type-error` -> "The variable MY-APP/TESTS::TYPE-ERROR is unbound") | `.todo/378` |
| 9 | `handler-bind ((error ...))` around a test body catches `(car 1)`, `(aref v 9)`, `(/ 1 0)`, an undefined function -- how a broken test becomes a recorded failure instead of ending the run | only conditions SIGNALED via `error`/`signal`/`warn` run handler-bind handlers; interpreter `aref` OOB / `make-array -1` escape even `handler-case`; wasm traps are uncatchable | `.todo/379` |
| 10 | `(ok (signals (foo) 'type-error))` = `(typep c 'type-error)` with a RUNTIME type; typed builtin conditions | `type-error`/`simple-error`/`condition`/`warning`/... are not `cl` symbols (`MY-PKG::TYPE-ERROR`), a runtime `typep` on them is nil (the literal works); every builtin error is a `simple-error` | `.todo/380` |
| 11 | `~W` ("Expect ~W to be ~:[true~;false~].", every assertion description) | printed literally, so `~:[` eats the form and every description reads "Expect ~W to be false." | `.todo/381` |
| 12 | `(let ((*print-case* :downcase)) (princ-to-string name))` -> "add-test" (test names, `run*` patterns) | no effect -> "ADD-TEST" | `.todo/041` (rove note appended) |
| 13 | `tree-equal` (`expands`), `remprop` (`remove-test`), `enough-namestring` + `*default-pathname-defaults*` + `pathname-device` (source-location printing, dead code today but a compile error for the special), `(setf uiop:getenv)` (`with-local-envs`, i.e. `run`'s `:env` -- a hard "setf does not support place" the moment `run` compiles), `uiop:ensure-absolute-pathname`/`compile-file-type`/`implementation-identifier`/`featurep`/`pathname-parent-directory-pathname`/`lispize-pathname`/`absolute-pathname-p` (`resolve-file`; stubs, reached only when `*load-pathname*` is a fasl path -- never here), `uiop:quit` (a test runner's exit code) | missing / stubs | `.todo/033`, `.todo/038`, `.todo/036`/`357`, `.todo/356`, `.todo/362` (consumer notes appended) |
| 14 | `(make-instance (intern "SPEC-REPORTER" package) :stream s)` (`make-reporter`) | works because rove EXPORTS the class; the same call for a non-exported class is "%MOP-MAKE-INSTANCE: not an instantiable class" on the compile paths; interpreter `type-of` of a foreign-package class double-qualifies | `.todo/382`, `.todo/383` |

The scratch patch set (the reproduction of "everything else works"): `asdf:` ->
`asdf::` for the missing externals + a program-level `(defvar asdf::*user-cache* nil)`;
`run-system` / `system-files` reduced to the package-named suite; `find-suite`
without the `package` method; `set-test` taking the package from a spike special
instead of `*package*`; a `stream-write-string` method + a shadowed
`fresh-line` in `rove/misc/stream`; `*report-stream*` = `t`; `form-inspect`'s
argument-inspecting arm disabled; `enough-namestring*` = `namestring`. Undo
each as its row lands; the E2E below is the gate that they are all gone.

## Sequence

1. `.todo/373` (Low) -- the load gate; nothing else is reachable before it.
2. `.todo/255` (High) + `.todo/376` (Low) -- `deftest` registers under the right suite and `run-suite`/`package-suite` find it. After these two, `(rove:run-suite *package*)` is the first working entry point.
3. `.todo/377` (Medium) + `.todo/252` (Medium) -- the spec reporter prints, indented, identically on all four.
4. `.todo/378` (Medium) + `.todo/379` (High) + `.todo/380` (Medium) + `.todo/381` (Low) -- `ok`/`ng`/`signals` are correct and a broken test is a failure, not a crash.
5. `.todo/374` (High) + `.todo/375` (Medium) -- `(rove:run :my-app/tests)` and `(asdf:test-system "my-app")`.
6. Row 12-14 items are polish; `.todo/041` matters for output parity with SBCL, the rest can trail.

## Acceptance

- `RoveE2eTest extends AsdfLibraryE2eSupport`: rove (BSD 3-Clause) and dissect
  (zlib) vendored unmodified under `src/test/resources/{rove,dissect}` next to
  the already-vendored cl-ppcre (`extraSystemPath`), a test file in the shape of
  rove's README + `examples/passed.lisp` (deftest / testing / ok / ng / signals
  with a user condition AND `'type-error` / outputs / pass / fail / skip /
  failing / setup / teardown / defhook / a failing assertion / an assertion whose
  form signals), run through `(rove:run :my-app/tests)` on a `:package-inferred-system`
  AND a plain `defsystem` test system, then `run-suite`; `(setf rove:*enable-colors* nil)`
  first; strip ` (Nms)` duration suffixes before comparing (rove prints them for
  any assertion over 37 ms and the interpreter will cross that). Expected lines
  verified against SBCL 2.2.9 on the same sources (the sxql pin discipline).
- `ci-spec.yaml`: none -- the driver cannot provide the `.asd`; the vendored E2E is
  the four-backend gate.
- Docs: a row in `doc/{en,ja}/guides/asdf-systems.md` "What can I actually load?"
  and a `guides/testing.md` page (nav entry) showing the two entry points, the
  four run commands, and the exit-code recipe (`.todo/362`'s `uiop:quit`, or
  `(unless (rove:run ...) (error ...))` until then); `.kb/asdf.md` gets the rove
  paragraph in the loadable-libraries record.

## Non-goals

- Coverage (`COVERAGE=1`, sb-cover) and the roswell `rove` CLI script (`rontolisp test FILE`
  mirroring it -- derive the system name from the file's defpackage, load, `test-system`, exit code -- is a
  reasonable follow-up once 374 lands, not part of this).
- `deftest`'s `:compile-at :run-time` on the compiled backends: it routes the body
  through `(compile nil '(lambda () ...))`, whose eval runtime cannot expand user
  macros -- today it silently answers nil (`.todo/384`); document as interpreter-only.
- `:style :none` on the compiled backends unless the program loads
  `rove/reporter/none` itself (`make-reporter` loads an unknown style's system at
  run time; the interpreter can, a compiled program cannot).
- Backtraces in failure reports (dissect's `stack` is nil on every backend) and
  `print-object` for result objects NESTED in a printed list (`(print (rove:run ...))`
  shows `#<ROVE/CORE/RESULT:PASSED-TEST :NAME ...>` slot dumps -- the documented
  nested-`print-object` limitation, `.kb/clos.md`).
- A raw wasm TRAP inside a test body (`(car 1)`, `(/ 1 0)`) still ends the run on
  the wasm backends: `.todo/379` catches what is signaled and what the JVM/interpreter
  raise; traps are the documented divergence of `.kb/error-handling.md`.
