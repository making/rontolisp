# `destructuring-bind` and macro lambda lists drop surplus elements; the last short wrappers

Difficulty: Medium (one destructuring binder shared by four backends, plus a
blast-radius measurement over every `defmacro` the libraries define)

Split out of `.todo/921` (2026-09-22), which made a surplus argument past an
`&optional` tail a `program-error` for FUNCTION lambda lists
(`.kb/lambda-lists.md`, "A surplus argument is a program-error").

## Measured (2026-09-22, interpreter)

```lisp
(destructuring-bind (a b) '(1 2 3) (list a b))           ; => (1 2), SBCL signals
(destructuring-bind (a &optional b) '(1 2 3) (list a b)) ; => (1 2), SBCL signals
(defmacro m1 (a &optional b) `'(,a ,b))
(m1 1 2 3)                                               ; => (1 2), SBCL signals
(defmacro m2 (a b) `'(,a ,b))
(m2 1 2 3)                                               ; program-error (already)
```

`LambdaLists.appendTailBindings` (the destructuring path) and the required-part
destructuring in front of it never check that the list is exhausted. A macro whose
lambda list goes beyond "required + one `&rest`" goes through the same
`destructuring-bind` wrapping (`LispEvaluator.evalDefmacro`,
`.kb/defmacro-backquote.md`), so it drops them too.

## What it needs

- The exhausted-list check in the destructuring binder (required-only patterns
  and the `&optional` tail alike), signalling the catchable `program-error`.
- The blast radius FIRST: every spliced library's `defmacro` and
  `destructuring-bind` runs through it. Run the full suite and diff the whole ANSI
  suite by test NAME before landing.

## Also left

- `#'write-line`'s first-class wrapper is `(string &optional stream)`; CL's is
  `(string &optional stream &key start end)`, so a legal `(funcall #'write-line s
  out :start 1)` is now a `program-error` on the compile paths where it used to
  drop the keywords.
- `complement` covers arities 0-3 and now REFUSES a fourth argument (ANSI
  `COMPLEMENT.4`, FAIL -> ERROR); the unbounded lowering's cost is measured in
  `.kb/sequence-designator-evaluation.md`.
