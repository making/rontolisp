# A literal `~[` whose selector matches no clause still advances the argument pointer

Difficulty: Low

With a run-time selector, no default clause and clauses that all consume the same number of
arguments, the literal path moves past those arguments even when no clause runs:

```lisp
(defun sel () 5)
(format nil "~[x~a~;y~a~] ~a" (sel) 1 2)                  ; " 2"
(apply #'format nil "~[x~a~;y~a~] ~a" (list (sel) 1 2))   ; " 1" (SBCL agrees)
```

Cause: `FmtParser.clauseExprs` calls `args.adoptIndex(end)` for a non-divergent `~[` whether or
not a clause is sure to run. Without a default clause the "no clause" outcome consumes nothing,
so the clauses diverge from it: treat that outcome as one more (empty) clause ending at the
selector's position, which sends the case down the existing divergent path. Add a row to
`FormatRendererTest.staticAndRuntimeRenderingAgree`.
