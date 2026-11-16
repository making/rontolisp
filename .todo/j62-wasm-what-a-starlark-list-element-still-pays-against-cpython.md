# j62. wasm: what a Starlark list element still pays, and the six programs at or over CPython

Difficulty: High

`.kb/starlark-frontend.md`, "The cell names its storage" (2026-11-16): after j61
`listidx.star` is 1.24 s on wasm where CPython is 1.25, and five of the eleven are still
over it -- `dicts` 1.35 / 0.96, `comps` 1.16 / 0.94, `methods` 2.84 / 1.14, `strings`
3.11 / 1.16, `sorting` 2.93 / 0.96 (`time.sh` of
`.todo/artefacts/j54-starlark-faster-than-cpython/`). The goal there stays: each of them
under CPython on wasm.

What a pass of a type test, a call and a field cost on wasmtime 49 is in
`.kb/adjustable-arrays.md`, "What an element access costs"; the guest profiler
misattributes all three, so each candidate is measured on the module's text first
(`.todo/artefacts/j61-wasm-vector-cell-fields/`: `wasm-tools print --name-unnamed`, a
script, `wasm-tools parse`).

What an element of `listidx.star` still pays, read off the module's text and NOT
measured one by one:

- The call of `%starlark-index` / `%starlark-set-index` per element.
- The instance test of ONE variable repeated at each reader of it: the predicate, then
  `mutability`, `iterating` and `items` each cast `x` again, five in a store
  (`WasmStructAccess`, `.kb/defstruct.md`). Each reader also clears the multiple-value
  channel (`ref.null eq; global.set`): measured, 0.02-0.03 s of 1.21 for the thirteen of
  the two helpers.
- `xs[i] + xs[i - 1]`: `(if (and (integerp a) (integerp b)) (+ a b) ..)` is three
  `ref.test`s per operand and then one CALL of the generic add, since a single operator
  over two variables is no fused tree (`.kb/wasm-int-fusion.md`); two fixnums compared at
  an unfused site are inline since j55, added they are not.
- `(and (integerp i) (>= i 0) (< i n))`: seven tests where one i31 test and an unsigned
  compare decide a fixnum subscript.
- The loop: a `tagbody` is a `loop` over a boxed state and a `br_table`; the counter is
  copied into the loop variable through `_ub_read` (measured: 0.03 s of `loop.star`'s
  0.69 with every `_ub_read` inline, 24 B a site -- not worth it alone).

## Do

- Measure each of the above on a hand-edited module, make what pays.
- Profile `dicts`, `comps`, `methods`, `strings` and `sorting` again: none was looked at
  after the cell's fields.
- A closure's box is a `TYPE_CELL` and carries the vector's two fields: 16 B more on V8
  (`.kb/wasm-gc-object-size.md`). A box type of its own gives them back; weigh it against
  the sites that read a box.
- Re-run `time.sh` over the eleven and record the table in `.kb/starlark-frontend.md`.
