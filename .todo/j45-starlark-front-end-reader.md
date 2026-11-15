# j45. Starlark front end: the reader (lexer and parser)

Difficulty: Medium

A prototype showed that Starlark lowers to the core forms and runs unchanged on the four
backends (`.todo/artefacts/j45-starlark-front-end-prototype/`, `README.md`: 404 of 430 conformance chunks, identical output everywhere). It
borrowed Python's `ast` for parsing. This item writes the real reader; the lowering is
`.todo/j46`.

## Do

- A new package `am.ik.rontolisp.starlark`, depending on the AST types and `reader` only
  (the `scheme` / `clojure` row of `.kb/architecture.md`, "Package dependency direction").
- Lexer per the Starlark spec's "Lexical elements": INDENT/OUTDENT from the indentation
  stack, implicit line joining inside brackets, backslash joining, comments, string literals
  (single, double, triple, raw, bytes), int (decimal, hex, octal, binary) and float literals.
- Parser per the spec's "Grammar reference" into records carrying line and column.
- Reject with a position what Python accepts and Starlark does not: chained comparison,
  `while`, `class`, `import`, `is`, `for`/`else`, f-strings, `try`, `with`, `yield`,
  `global`/`nonlocal`, chained assignment.

## Test

Parse every chunk of the conformance corpus (bazelbuild/starlark, `test_suite/testdata`) and
compare the tree with what the prototype's `sl2lisp.py` gets from Python's `ast` for the
same chunk, or pin a hand-picked set of trees. Syntax-error chunks must fail with a position.
