# Compiled make-array :initial-contents with a run-time dims list of rank >= 2

Difficulty: Medium

Measured 2026-09-28. The compiled lowering (`LispMacroExpander.lowerInitialContentsMakeArray`)
picks the nested fill only for a LITERAL multi-element dims list (`'(2 3)`); any other dims form
takes the rank-1 fill. A dims list that only exists at run time therefore fails on every compiled
backend, where the interpreter fills it:

```lisp
(let ((d (list 2 3)))
  (print (make-array d :initial-contents (list (list 1 2 3) (list 4 5 6)))))
;; interpreter: #2A((1 2 3) (4 5 6))
;; JVM:         aref: expected 2 subscripts, got 1
;; wasm P1, component: trap (unreachable)
```

The rank-1 fill checks its first axis against `(car dims)` and then stores with `%aset`, which
rejects a rank-2 array.

Goal: a run-time rank fills row-major and checks every level's shape exactly as the literal
path does (`.kb/error-handling.md`, "`make-array :initial-contents` whose shape does not match
the dimensions"), on the JVM and both wasm backends, without growing the literal rank-1 and
rank-2 paths. Likely shape: keep the rank-1 fill when the allocation's rank is 1 and branch to
a general walk over `(array-dimensions arr)` otherwise; mind that the lowering runs during code
generation, so a helper it calls must already be present in the module.
