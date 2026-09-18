# Stale %mv-spill on the compiled backends, non-tail leaks, and zero values

Difficulty: High

Measured 2026-09-18 (`.kb/multiple-values.md`, "An argument is a single-value context"):
the interpreter now clears the spill at every call's argument boundary; nothing else does.

1. JVM, WASM preview 1 and component: a consumer reads an ARGUMENT's extras as the call's.
   `(multiple-value-list (+ 1 (values 5 6)))` -> `(6 6)`, `(list (f))` with `f` ending in
   `(floor 7 2)` -> `((3) 1)`, `(h (values 1 2))` with `(defun h (x) (+ x 1))` -> `(2 2)`.
   Built-in calls compile inline in many places, so a per-call-site clear in each backend
   will miss some; look for one seam (the expander, gated on `injectMvSpillGlobal`'s scan)
   or a representation change. Measure a call-heavy compiled benchmark.
2. Every backend, interpreter included: a `values` in a non-tail, non-argument position
   leaks -- `(multiple-value-list (let ((x (values 1 2))) x))` -> `(1 2)`,
   `(multiple-value-list (progn (values 1 2) 3))` -> `(3 2)`. A clear after each non-last
   body form collides with the publish shape `(progn (setq %mv-spill extras) primary)`, and
   a clear after a `let` init collides with the consumer's own snapshot, which sits in the
   NEXT nested `let` (`lowerMvProducer`). Either restructure those shapes or find another
   discriminator.
3. Zero values: `(values)` publishes nil, so `(multiple-value-list (g))` with
   `(defun g () (values))` -> `(NIL)` (CL: `NIL`), `(multiple-value-list (funcall #'values))`
   -> `(NIL)`, and Scheme `(call-with-values (lambda () (values)) list)` -> `(())` (R7RS and
   gosh: `()`), on all four backends. WASM also answers `NIL` for
   `(multiple-value-call #'list (values) (g) 1)` (others `(NIL 1)`, CL `(1)`). A distinct
   zero-count spill value is blocked on 1 and 2: a stale one would erase a later consumer's
   primary.

When 1 lands, `ClPpcreE2eTest.expectedOnTheInterpreter` goes away: every leg answers `(NIL)`
for `(scan "abc" "xyz")`, as SBCL does.
