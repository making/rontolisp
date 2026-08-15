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
| 1 | a package-inferred file whose FIRST form is `(in-package #:cl-user)` and whose `defpackage` is second (core/assertion.lisp, core/result.lisp, ...) | DONE (2026-08-15): the derivation skips forms until the package definition form, like real ASDF's `file-defpackage-form`; rove's whole graph derives | -- |
| 2 | `asdf:*user-cache*`, `asdf:registered-systems`, `asdf:component-name`/`-children`/`-sideway-dependencies`, `asdf:system`/`package-inferred-system`/`cl-source-file`/`module` as `typecase` types AND `defmethod` specializers, `find-system` -> a system OBJECT, a runtime `load-system` of an already-loaded system | not external ("use ASDF::X"); `find-system` answers a name string (interpreter) or nil (compile paths); nested `load-system` = call-time error stub | `.todo/374` |
| 3 | `*load-pathname*` bound to the file being loaded when a `deftest` runs (rove's file -> package map, `make-new-suite`, is what `run` walks for a plain `defsystem` test system) | nil at run time on the compile paths -- `(rove:run "foo/tests")` would find no suites there | `.todo/375` |
| 4 | `*package*` READ at run time inside rove's own `set-test`/`package-suite`, and in the expansions of `setup`/`teardown`/`defhook` | DONE (2026-08-15, `.todo/255`): `*package*` is a genuine dynamic variable on all four backends -- the package keyword `find-package` answers, read at call time, assigned by `in-package`, let-bindable, bound to `cl-user` by `with-standard-io-syntax` (`.kb/packages.md`) | -- |
| 5 | `(defmethod find-suite ((package package)) ...)` + a `(package-name)` designator method | DONE (2026-08-15): `package` is a TYPE specializer sharing the type test's ONE definition, ranked ahead of `keyword`/`symbol` so the designator method cannot recurse forever (`.kb/clos.md`) | -- |
| 6 | an indent-stream defining only `stream-write-char` (+ `stream-line-column`/`stream-start-line-p`/`stream-finish-output`/`-force-output`/`-clear-output`), driven by `fresh-line`/`princ`/`format`/`write-char`/`write-string` | DONE (2026-08-15, `.todo/252`): the whole OUTPUT protocol dispatches -- `write-char` reaches `stream-write-char`, each write generic defaults to the other, and `terpri`/`fresh-line`/`write-line`/`princ`/`prin1`/`print`/`force-output`/`finish-output`/`clear-output`/`close` are rewritten on the compile paths too (`.kb/gray-streams.md`) | -- |
| 7 | `(make-synonym-stream '*standard-output*)` as a NON-NIL stream (`(when stream (setf (reporter-stream r) (make-indent-stream stream)))` in `initialize-instance :after`) | DONE (2026-08-15): a synonym stream is a distinct VALUE forwarding per operation for ANY symbol -- true, `streamp`, `synonym-stream-symbol`, and a Gray stream on either side of it writes through (`.kb/read-load-streams.md`) | -- |
| 8 | real `macro-function` / `special-operator-p` (`form-inspect`, `form-steps`) | DONE (2026-08-15, `.todo/378`): the two PARTITION the operators with no function value on all four backends, so `form-inspect`'s three branches are right; `macroexpand-1`/`macroexpand` also answer `expanded-p` now, and a compiled `macroexpand-1` SIGNALS on a macro call rather than looping (`.kb/symbol-runtime-api.md`, `.kb/gensym-macroexpand.md`). The scratch patch "`form-inspect`'s argument-inspecting arm disabled" is retired | -- |
| 9 | `handler-bind ((error ...))` around a test body catches `(car 1)`, `(aref v 9)`, `(/ 1 0)`, an undefined function -- how a broken test becomes a recorded failure instead of ending the run | DONE (2026-08-15): built-in errors run the cluster stack -- the interpreter at the signal point (its seam also wraps the raw `aref`/`make-array` escapes so `handler-case` catches them), the compiled backends at the `%hb-guard` landing pad of the handler-bind expansion; handlers run ONCE per condition (`%handlers-ran%` identity). wasm raw TRAPS stay uncatchable -- the documented spectrum (`.kb/error-handling.md`) | -- |
| 10 | `(ok (signals (foo) 'type-error))` = `(typep c 'type-error)` with a RUNTIME type; typed builtin conditions | DONE (2026-08-15, `.todo/380`): the seeded condition class names ARE the `cl` symbol list (`ClosRegistry.CONDITION_CLASS_NAMES` -> `PackageRegistry`), which also makes a RUNTIME specifier match; a built-in error carries its class -- `type-error` for a bad `car`/index/argument type, `division-by-zero`, `unbound-variable`, `undefined-function` -- on the interpreter and the JVM, and the undefined-function stub signals its class on the wasm backends too (raw traps stay traps) | -- |
| 11 | `~W` ("Expect ~W to be ~:[true~;false~].", every assertion description) | DONE (2026-08-15, `.todo/381`): `~W` is `write` of the argument -- `prin1` under the printer variables -- on BOTH renderings of the directive set, so it consumes its argument and the following `~:[` reads the right one (`.kb/format.md`) | -- |
| 12 | `(let ((*print-case* :downcase)) (princ-to-string name))` -> "add-test" (test names, `run*` patterns) | no effect -> "ADD-TEST" | `.todo/041` (rove note appended) |
| 13 | `tree-equal` (`expands`), `remprop` (`remove-test`), `enough-namestring` + `*default-pathname-defaults*` + `pathname-device` (source-location printing, dead code today but a compile error for the special), `(setf uiop:getenv)` (`with-local-envs`, i.e. `run`'s `:env` -- a hard "setf does not support place" the moment `run` compiles), `uiop:ensure-absolute-pathname`/`compile-file-type`/`implementation-identifier`/`featurep`/`pathname-parent-directory-pathname`/`lispize-pathname`/`absolute-pathname-p` (`resolve-file`; stubs, reached only when `*load-pathname*` is a fasl path -- never here), `uiop:quit` (a test runner's exit code) | missing / stubs | `.todo/033`, `.todo/038`, `.todo/036`/`357`, `.todo/356`, `.todo/362` (consumer notes appended) |
| 14 | `(make-instance (intern "SPEC-REPORTER" package) :stream s)` (`make-reporter`) | works because rove EXPORTS the class; the same call for a non-exported class is "%MOP-MAKE-INSTANCE: not an instantiable class" on the compile paths; interpreter `type-of` of a foreign-package class double-qualifies | `.todo/382`, `.todo/383` |

The scratch patch set (the reproduction of "everything else works"): `asdf:` ->
`asdf::` for the missing externals + a program-level `(defvar asdf::*user-cache* nil)`;
`run-system` / `system-files` reduced to the package-named suite; `set-test` taking the package from a spike special
instead of `*package*`; a `stream-write-string` method + a shadowed
`fresh-line` in `rove/misc/stream` (row 6, no longer needed); `*report-stream*` = `t` (row 7, no longer needed); `form-inspect`'s
argument-inspecting arm disabled (row 8, no longer needed); `enough-namestring*` = `namestring`. Undo
each as its row lands; the E2E below is the gate that they are all gone.

## Sequence

1. ~~`.todo/373` (Low) -- the load gate; nothing else is reachable before it.~~ DONE; `(ql:quickload "rove")` now stops at row 2 (`asdf:*user-cache*` is not external).
2. ~~`.todo/255` (High) + `.todo/376` (Low)~~ DONE -- `deftest` registers under the right suite and `run-suite`/`package-suite` find it. After these two, `(rove:run-suite *package*)` is the first working entry point.
3. ~~`.todo/377` (Medium) + `.todo/252` (Medium)~~ DONE -- the spec reporter prints, indented, identically on all four.
4. ~~`.todo/378` (Medium)~~ DONE + ~~`.todo/379` (High)~~ DONE + ~~`.todo/380` (Medium)~~ DONE + ~~`.todo/381` (Low)~~ DONE -- `ok`/`ng`/`signals` are correct and a broken test is a failure, not a crash.
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
