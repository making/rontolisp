# h31. `#+#.(expr)` / `#-#.(expr)` ignore the evaluated feature expression; no `:common-lisp` feature

Difficulty: Low

Found 2026-10-30 probing fiveam (`src/run.lisp:280`) and named-readtables (`src/cruft.lisp`).

## 1. `#+#.` (bug)

```lisp
(list #+#.(cl:if nil '(and) '(or)) (sb-ext:foo) 2)  ; SBCL: (2)   here: error No such package: SB-EXT
(list #-#.(cl:if t '(and) '(or)) 5 6)                ; SBCL: (6)   here: ((AND) 5 6)
```

The `#.` result is not used as the feature expression. fiveam guards an SBCL-only
`declaim` this way, so it cannot load. Fix in `LispLexer` (and the `datumEnd`
stop index, `.kb/reader-features.md`). The skipped form must be read suppressed, with
no package lookup. Bug-fix protocol: add the failing case first (ci-spec).

## 2. `:common-lisp` in `*features*` (decision)

named-readtables writes its portable fallback as `#+ :common-lisp ...` in every
`define-cruft`. Because `:common-lisp` is absent (deliberate,
`reader/Features.java` javadoc: "rontolisp is not a conforming implementation"), every
`define-cruft` body is empty and the load fails with `(assert (not (null alternatives)))`.
`#+common-lisp` is the conventional "any CL" branch, not a conformance claim. Re-decide:
announce `:common-lisp` on every backend, or keep it out and record why. Grep the cached
Quicklisp sources for `#+common-lisp`/`#-common-lisp` first, to measure what flips.
ci-spec `reader-features-variable` pins `(length *features*)`.
