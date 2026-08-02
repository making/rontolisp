# Reader: allow `',@x` / `#',@x` inside a backquote template

Difficulty: 中 (one reader seam + the template lowering; the semantics are
subtle enough to deserve pinned expansion tests, but the blast radius is the
shared frontend so all four backends come for free)

Part of the Mito milestone `.todo/238` (substrate; no dependencies).

## The blocker

`LispReader.readWrappedTemplate` (LispReader.java:653) rejects `,@` after `'`
or `#'` in a backquote template:

```
am.ik.rontolisp.reader.LispReadException: ,@ cannot follow ' or #' in a backquote template
```

Hit by `(ql:quickload "trivia.level0")` — trivia level0/impl.lisp:54:

```lisp
(quote `(equal ,*what* ',@args))
```

and by type-i (same idiom). Both are on the critical path of trivia
(`.todo/243`) and therefore of sxql and mito.

## Semantics

`'x` inside a template is ordinary sugar for `(quote x)`, so `',@args` is the
two-element template `(quote ,@args)`: splice `args` into the tail of a
`(quote)` list. With `args` = `(A)` the result is `(quote A)` i.e. `'A` — the
one-element splice is the idiom's intended use (trivia guarantees a
single-element `args` at that site). Same story for `#'` with `function`.
An empty splice yields `(quote)` and a multi-element splice `(quote a b)` —
degenerate but well-defined list structure; CL readers (SBCL) produce exactly
that, so match it rather than special-casing arity.

## Where

- `LispReader.readWrappedTemplate` / `readTemplateElement`: instead of
  erroring, lower `'<splice>` to a template LIST `(quote <splice>)` so the
  existing template-list splicing machinery does the appending.
- Check the nested-backquote interaction: the trivia site is itself inside a
  quoted backquote (``(quote `(...))``), so the fix must compose with however
  nested templates are represented today.

## Acceptance

- `(ql:quickload "trivia.level0")` gets PAST the reader error (its next
  blocker, symbol-macrolet, is `.todo/240` — level0 itself may fully load).
- Pinned reader/eval tests: `` `(a ',@'(b)) `` => `(A 'B)` (printed
  `(A (QUOTE B))` if the printer does not re-sugar), empty and two-element
  splices, `#',@` variant, and one nested-backquote case copied from trivia
  level0/impl.lisp:54.
- Cross-backend: one ci-spec.yaml case (the construct is compile-time only,
  but the macroexpander runs on every backend — cheap insurance).
