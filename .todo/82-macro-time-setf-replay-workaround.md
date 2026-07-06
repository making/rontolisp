# 82: Eliminate the `macro-time-setf-places.txt` workaround

Status: **workaround in place** (todo 81). **Goal of this task: delete the data
file AND the setf-only special case entirely**, replacing them with a principled
mechanism -- not generalize the registry (that was the old option 2; the user
wants the file gone). Difficulty: **High** for the auto-detect route (Phase A), or
**Medium** for the minimal `eval-when`-only route (see "Minimal alternative").

This is a self-contained compile-frontend task; no backend codegen. Read this
whole file plus `.kb/dynamic-special-variables.md` (the runtime-specials feature
that did NOT remove this) and the cl-who paragraph in `.kb/asdf.md` before
starting.

## What the workaround is (what to delete)

On the compile path, `UserMacroExpander.expand` walks the top-level forms and
evaluates definition forms into a **macro-time evaluator** (`macroEval`) so a
later `defmacro` body can see them at expansion time. For a *mutation* it can only
do this for a hard-coded shape: a top-level `(setf (PLACE ...) VALUE)` whose PLACE
member name is listed in a **data file**. This is the one remaining data-driven
special case.

Exact locations (as of 2026-07-06):

- `src/main/java/am/ik/rontolisp/eval/UserMacroExpander.java`
  - `expand()` replay block: the `isMacroTimeReplaySetf(resolved)` branch (~L104-114)
    that calls `macroEval.eval(expanded)`. Note the SAME method already
    unconditionally replays top-level `defun`/`defclass`/`defgeneric`/`defmethod`/
    `defvar`/`defparameter`/`defconstant` into `macroEval` (~L83-102) -- that is
    the foundation to build on; only the setf case is data-file-gated.
  - `isMacroTimeReplaySetf` (~L131-138), `member` helper (~L140-143),
    `MACRO_TIME_SETF_PLACES` + `loadMacroTimeSetfPlaces` (~L150-169), plus the
    `HashSet`/`InputStream`/`StandardCharsets` imports that become unused.
- `src/main/resources/am/ik/rontolisp/eval/macro-time-setf-places.txt` (one entry:
  `html-mode`).
- `src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/resource-config.json`
  (the resource glob that includes the `.txt`).

## Why `.todo/84` (runtime special variables) did NOT remove it

`.todo/84` shipped real dynamic (special) variable binding, but that is a
**runtime** mechanism. cl-who's `with-html-output` reads `*html-mode*` at
**macro-EXPANSION (compile) time** (its `convert-tag-to-string-list` chain decides
attribute syntax then), so a runtime binding is invisible to the already-expanded
macro. What is actually needed is that the *effect* of `(setf (html-mode) :html5)`
be visible **in the macro-time evaluator's globals** before the next
`with-html-output` is expanded. Both routes below do exactly that.

## The CL-correct framing

In real Common Lisp a top-level non-`eval-when` form is NOT evaluated at
compile time (CLHS 3.2.3.1); a program that must set state a later macro reads at
compile time wraps it in `(eval-when (:compile-toplevel :load-toplevel :execute)
...)`. rontolisp expands ALL macros at compile time, so it hit this earlier than
most, and papered over the one known case (`html-mode`) with the data file. The
principled fixes are (A) auto-detect that a top-level setf is a *pure config
setter* and replay it, and/or (B) honor `eval-when (:compile-toplevel)` so a
program can say so explicitly.

## What already exists (foundation)

- `UserMacroExpander.expand` already replays top-level definitions into `macroEval`
  (see above), and the `(defun (setf html-mode) ...)` setter IS among them -- so
  `macroEval.eval((setf (html-mode) :html5))` already works; the only question is
  *deciding* to do it without the data file.
- `expandEvalWhen` (`LispMacroExpander.java` ~L8891) currently ignores the
  situation list and expands to `progn` (so `:compile-toplevel` is a no-op today).
- `SpecialVarCollector` (from `.todo/84`, `am.ik.rontolisp`) knows which names are
  special -- useful for judging "does this setter mutate a special/global config?".
- `flattenTopLevel` (`LispMacroExpander.java` ~L10099) already splices top-level
  `eval-when` bodies for Pass-1 collection.

## Plan

### Phase A -- auto-detect pure config setters (data-file-free). Difficulty: **High**

Replace `isMacroTimeReplaySetf` + the `.txt` with a static purity judgment: a
top-level `(setf (PLACE args...) v)` is replayed into `macroEval` iff the
`(defun (setf PLACE) ...)` setter registered in `macroEval` is a **pure config
setter** -- its body only assigns special/global variables and does pure
computation, with no external side effects.

- Implement a conservative allow-list walker over the setter body: permitted =
  `setq`/`setf` of a variable place, `let`/`let*`, `if`/`when`/`unless`/`progn`,
  arithmetic/comparison/logic builtins, literals, and reads of variables. ANY
  other operator (I/O, `rplaca`/`rplacd`/`aset`, `print`/`format`, a call to an
  unknown/user function, `apply`/`funcall`) => impure => do NOT replay.
- Also require the mutated place(s) to be globals/specials (via
  `SpecialVarCollector` / the global set), not lexicals or data structures.
- The bar is asymmetric: a **false positive** (replaying an impure setter)
  double-runs side effects at compile time -- must be impossible, so the walker
  must be conservative (deny by default). A **false negative** (not replaying
  html-mode) breaks cl-who -- so the walker must accept who.lisp's actual
  `(setf html-mode)` setter (inspect `src/test/resources/cl-who/who.lisp` for its
  real body and make sure the allow-list covers it).

### Phase B -- honor `eval-when (:compile-toplevel)`. Difficulty: **Medium**

In `UserMacroExpander.expand`, recognize a top-level `(eval-when (situations...)
body...)` whose situations include `:compile-toplevel` (or `compile`) and evaluate
its body into `macroEval` (in addition to keeping it in the program per the
existing `:load-toplevel`/`:execute` handling via `flattenTopLevel`). This is the
CL-standard escape hatch that covers anything Phase A's purity walker refuses.
Mind the "keep verbatim vs canonical" logic at the end of `expand` and the
`isPackageDirective` precedent.

### Phase C -- cleanup. Difficulty: **Low**

Delete `macro-time-setf-places.txt`, the `resource-config.json` entry, and the
`isMacroTimeReplaySetf`/`MACRO_TIME_SETF_PLACES`/`loadMacroTimeSetfPlaces` code +
now-unused imports. Update: the "Macro-time setf-place replay" constraint in
`CLAUDE.md`, the cl-who paragraph in `.kb/asdf.md`, the relationship note in
`.kb/dynamic-special-variables.md`, and this `.todo/82` (delete it if fully gone).

## Minimal alternative (if Phase A's purity analysis is too much). Difficulty: **Medium**

Do Phase B + Phase C only, and rewrite the **user-side** html-mode calls to
`(eval-when (:compile-toplevel :load-toplevel :execute) (setf (html-mode) :html5))`
in `examples/http-handler-cl-who.lisp`, `examples/asdf/cl-who-demo.lisp`, and the
`ClWhoE2eTest` source. This removes the data file and the special case at the cost
of a slightly less magical UX (the caller must declare compile-time intent, which
is exactly what real CL requires). **Do NOT edit `src/test/resources/cl-who/who.lisp`
-- it is Edi Weitz's cl-who vendored unmodified (BSD); only user-side code that
*sets* the mode may change.**

## Files: touch vs off-limits

- Touch: `UserMacroExpander.java`, `LispMacroExpander.expandEvalWhen` (Phase B),
  the two resource files, `CLAUDE.md`, `.kb/asdf.md`, `.kb/dynamic-special-variables.md`.
  For the minimal route also the three user-side cl-who demos/tests.
- **Off-limits: `src/test/resources/cl-who/*` (vendored cl-who, unmodified).**

## Verification

- cl-who still switches mode: `(setf (html-mode) :html5)` (or the `eval-when`
  form, minimal route) renders HTML5 (`<br>`, not `<br/>`) on **all four backends**
  (interpreter / JVM / WASM Preview 1 / component). `ClWhoE2eTest` is the ready
  E2E; run it and the `special-variable`/cl-who ci-spec cases.
- New negative test (Phase A): a top-level `(setf (PLACE) (progn (print 'x) 1))`
  with an *impure* setter is NOT replayed (no `x` printed at compile time).
- `./mvnw test`, then the native `CiSpecE2eTest` (see CLAUDE.md) since compile-path
  behavior changed.

## Acceptance

`macro-time-setf-places.txt` and `isMacroTimeReplaySetf` are gone; cl-who's mode
switch still works on all four backends; no library-specific term remains in Java
source OR in a data file. Record the outcome (delete this file when done).

Related: `.kb/asdf.md` (cl-who), `.kb/dynamic-special-variables.md`, `.todo/84`
(runtime specials -- shipped, does not subsume this), `.todo/54` Phase 4,
`[[cl-who-loadable]]` memory.
