# j53. Starlark: what tripled the output of `print(1 + 2)`

Difficulty: Medium

`.kb/starlark-frontend.md`, "Measured": `print(1 + 2)` compiled to 62,136 / 51,673 / 55,336 B
(`.class` / `.wasm` / component) after the first lowering, and to 177,793 / 178,269 / 181,868 B
at `85dd248a6`, after the conformance corpus's run-time additions (`%g`, `format`, the `repr`
table, `dir`/`getattr` attribute tables, positions). The demo grew far less (161 KB -> 285 KB),
so the growth is a fixed cost every Starlark program pays. Nobody measured where it comes from.

## Do

- Bisect the growth over the commits between the two measurements and name the library
  definitions a one-line program now keeps (`StarlarkLibrary`'s splice, the pruner in
  `LibraryDefunPruner`). A likely shape: a table or a generic entry point (`repr`, the
  attribute table behind `dir`/`getattr`, the error/position path) that references every
  method, so pruning cannot drop any of them.
- Make a program pay only for what it can reach, if the measurement shows that is possible
  without restating the pass pipeline. A cost the measurement says is inherent is recorded,
  not forced down.
- Record the before/after sizes (all three outputs, `print(1 + 2)` and `demo.star`) in
  `.kb/starlark-frontend.md`.
