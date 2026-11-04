# i47. The printer never signals `print-not-readable`

Difficulty: Medium

Measured 2026-11-05, against SBCL 2.2.9. `*print-readably*` is t under `with-standard-io-syntax` and picks
the `prin1` conversion, but no backend signals.

```lisp
(with-standard-io-syntax (prin1-to-string (lambda (x) x)))       ; SBCL signals PRINT-NOT-READABLE, here "#<lambda>"
(with-standard-io-syntax (prin1-to-string (make-instance 'c)))   ; here "#<C>"
(with-standard-io-syntax (print-unreadable-object (1 s) ...))    ; here "#<...>"
```

Consumer found by grepping the quicklisp cache: serapeum's `same-literal-p`, which handles the condition around
`(write-to-string x :readably t)`. Any two lambdas print `#<lambda>` here, so it answers true for them. The other
mentions (serapeum's and coalton's methods signalling it themselves) work since the class and its reader exist.

The obvious seam is the leaf of `%pc-walk` and `%print-cased`'s raw arm: under `*print-readably*`, a leaf whose
text holds `#<` and is not a string signals. Traps found on the way:

- `error`, `warn`, `assert` and `check-type` format their control eagerly. `(with-standard-io-syntax (error "bad ~S"
  #'car))` would raise `print-not-readable` instead of the error asked for. SBCL formats at report time.
- A condition report printed by `~A` runs under `*print-readably*` t, since only the `print-object` method route
  binds it nil (`printObjectCall`).
- `print-unreadable-object` must signal too, and `*print-readably*` then becomes a mention of every program using it.
- A program naming `*print-readably*` alone is not routed: it has to join `PRINT_CONTROL_VARS` without pulling the
  case-fold tables (`mentionsPrintControlVariable` also gates those).
- `error` and the condition class in the renderer reach every routed program (`printsConditionReports`,
  `mayCreateInstance`), so the arm needs a gate like `expandPrintCasedLeaf`'s.
- A value SBCL prints readably and this printer spells `#<`: a hash table (`#.(MAKE-HASH-TABLE)` on SBCL).
