# j57. Starlark: `s += piece` in a loop copies the string so far

Difficulty: Medium

`.kb/starlark-frontend.md`, "Against CPython (2026-11-16)": `s += "ab"` 20,000 times is
0.60 s on the JVM and 0.10 s on wasm against 0.03 s of CPython, and 200,000 times did not
finish in minutes (Go Starlark: 41 s). Every `+=` is a `%string-concat` of the whole
string; CPython extends the string in place when nothing else holds it. The JVM pays
most: a string there is framed in quotes, so a join copies it more than once.

## Do

- Measure the shape first on the three backends at 5,000, 20,000 and 200,000 pieces
  (`strings.star`'s `accumulate` in `.todo/artefacts/j54-starlark-faster-than-cpython/`).
- In the lowering: a local the loop only appends to (`s += e`, `s = s + e`), that nothing
  else in the loop reads and no nested function captures, collects its pieces while the
  loop runs and is joined where the loop is left -- its end, `break`, a `return` -- as
  `%starlark-concat-pieces` joins them. The value before the loop decides when it runs: a
  string collects, anything else (a list, an int) takes `+=` as it stands, and a piece
  that is no string ends the collecting with the helper's error.
- The conformance corpus and a `starlark-spec.yaml` case pin that nothing can tell: a
  global is not collected (a Common Lisp caller or a later buffer reads it), and an error
  inside the loop leaves a local nobody can read.
