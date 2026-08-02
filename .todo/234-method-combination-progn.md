# defgeneric `:method-combination progn :most-specific-last`

Difficulty: 中 (likely one session if scoped to the built-in operator
combinations; touches generic dispatch on all four backends)

Split out of `.todo/231` (2026-08-02 survey). DEFGENERIC currently throws
`UnsupportedOperationException` on a `(:method-combination ...)` option.
Probe evidence: yason's `encode-slots` hook —

```lisp
(defgeneric encode-slots (object)
  (:method-combination progn :most-specific-last))
```

— is yason's ONLY blocker; with the option stripped, the whole system loads
and its core parse/encode paths do not depend on it (it is the user-facing
CLOS extension hook for encoding user classes).

Scope decision to make first: full CLHS short-form combinations
(`progn`/`and`/`or`/`+`/`list`/`nconc`/`append`/`max`/`min`, each with the
optional `:most-specific-last` order argument) are one uniform mechanism —
"call every applicable primary method in CPL order, combine results with
the operator" — and implementing the family generically is barely more than
implementing `progn` alone. `define-method-combination` (long form) is OUT
of scope.

Mechanics: applicable-method collection already exists for `call-next-method`;
a combination changes the effective-method shape from "most-specific primary
+ next chain" to "operator over ALL primaries" (order reversed under
`:most-specific-last`). `:around`/`:before`/`:after` in short-form
combinations: only `:around` is legal (CLHS), primaries must carry the
combination name as a qualifier (`(defmethod encode-slots progn ...)`) —
DEFMETHOD must accept that qualifier position too.

Interpreter + JVM + both WASM backends (the dispatch runtime is duplicated
per backend — grep `.kb/clos.md` for where the effective method is built).
Depends on nothing else in the 231 family; independent of `.todo/232`.
