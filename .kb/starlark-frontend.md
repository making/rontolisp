# The Starlark front end (`am.ik.rontolisp.starlark`)

Not yet reachable: the reader is in, the lowering and the seam are not. The prototype that
measured the lowering is `.todo/artefacts/j45-starlark-front-end-prototype/` (`README.md`).

## Where it sits

`starlark` depends on the AST types (`SourceLocation`) and `reader` (`LispReadException`)
only, like `scheme` and `clojure` (`PackageCycleTest`).

## Reader

`StarlarkLexer` (text -> tokens, one per `next()`) and `StarlarkParser` (recursive descent
over the spec's "Grammar reference", precedence climbing for the binary operators) build a
`StarlarkSyntax` tree: one record per production, each with its `SourceLocation`. Errors
are `LispReadException`s with a position; `isEndOfFile()` is true when the input ran out
(unterminated string, open bracket, missing indented block).

- Lexing is lazy, so the first error in source order is the one reported, lexical or
  syntactic.
- Indentation is spaces only (the spec; Python accepts tabs): a tab there is an error. Blank
  and comment-only lines are skipped; inside brackets line endings and indentation are white
  space; backslash-newline joins lines anywhere.
- Literals: ints are `BigInteger` (hex, octal, binary; a leading zero is an error, as the spec
  grammar has only `0`); floats `double` (an infinite literal is an error); strings are Java
  `String`s (octal and hex escapes at most 127, Unicode escapes refuse surrogates); bytes are
  `byte[]` (text as UTF-8, escapes as raw bytes up to 255). A backslash before anything else
  is an error. Raw strings keep the backslash before a quote or line ending, as Python does.
- Rejected with a position, though Python accepts them: the reserved words (`while`,
  `class`, `import`, `is`, `try`, `with`, `yield`, `global`, `nonlocal`, ...) in the lexer,
  string prefixes other than `r`/`b`/`rb`/`br` (f-strings), chained comparison, chained
  assignment, `for`/`else`, a slice or call as a target, a tuple or list as an augmented
  target, an unparenthesized tuple with a trailing comma, arguments out of the order
  positional, keyword, `*args`, `**kwargs`, parameters out of the order required, optional,
  `*`, keyword-only, `**kwargs`. Also rejected: `return` outside a `def`, `break`/`continue`
  outside a loop, `load` inside a `def` or with no symbol, a bare `*` with no keyword-only
  parameter after it.
- Accepted though Python rejects: non-ASCII text in a bytes literal (the spec encodes it as
  UTF-8), a `lambda` as a comprehension's `if` condition (the spec's grammar and Go's
  parser allow it; Python 3.9+ does not).
- Positions: the operator for a unary or binary node, `if` for a conditional, the opening
  bracket for a call, subscript, slice or collection, the field name for a dot, the first
  token for everything else. Columns are 1-based UTF-16 units.
- Parentheses leave no node; `elif` is an `If` alone in the `else` list; `True`, `False`,
  `None` are identifiers.

Measured 2026-11-15 against Python 3.12's `ast` (`astdump.py` in the prototype's directory
prints `ast` as `StarlarkTrees` prints a tree, names and literals with `@line:column`):
bazelbuild/starlark `c0af0ea` `test_suite/testdata`, 430 chunks, plus the prototype's
`demo.star` and `bench.star` and a 47-statement lexical stress file: every tree identical,
positions included. The corpus has three syntax-error chunks (two argument-order, one
`6burgle`); both parsers reject all three. It has no float or bytes literal and no syntax
Python accepts and Starlark rejects, so `StarlarkParserTest` pins those by hand.
