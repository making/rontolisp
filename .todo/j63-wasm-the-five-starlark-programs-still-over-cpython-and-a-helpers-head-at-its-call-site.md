# j63. wasm: the five Starlark programs still over CPython, and a helper's head at its call site

Difficulty: High

`.kb/starlark-frontend.md`, "An int test compiled twice" (2026-11-16): after j62 six of
the eleven are under CPython on wasm (`listidx` 0.95 against 1.18) and five are not --
`dicts` 1.17 / 0.89, `comps` 1.03 / 0.90, `methods` 2.54 / 1.10, `strings`
3.03 / 1.13, `sorting` 2.80 / 0.98 (`time.sh` of
`.todo/artefacts/j54-starlark-faster-than-cpython/`). The goal there stays: each of them
under CPython on wasm.

What costs is the NUMBER of instructions, not one construct
(`.kb/wasm-int-fusion.md`, "An integer test compiled twice": a struct type test is about
twelve on wasmtime 49, `array.get` by a constant index fifteen, a reference live across a
call that is never taken is stored and reloaded all the same). Read the machine code
(`wasmtime objdump --filter 'function[N]' m.cwasm`) or edit the module's text; the guest
profiler charged 52% of a loop of `gethash` to `puthash`.

Measured and not made (`.todo/artefacts/j62-wasm-fixnum-versions/`):

- **A helper's head at its call site.** Sixty hand-written instructions in front of
  `%starlark-index` and `-set-index` were `listidx.star` 1.20 -> 0.79 s before the two
  copies; the compiled heads are a function each (458 and 670 B), so an element still
  pays the call (frame, stack check, the arguments stored to their stack-map slots), the
  loop variable boxed for it (`_ub_read`) and unboxed in the head, `length` boxed and
  unboxed, the cell cast twice. `am.ik.wasm.WasmInliner` moves a body of at most 64 B
  (`MAX_MOVED_BODY`); a head compiled where it is called, its subscript the caller's raw
  local, is all of the above at once.
- **`_t_sym`**: a call per `t` a function answers, over a lazily made global.
  `global.get` with the global set when the program starts: `sorting.star` 2.99 -> 2.87
  s. Every entry of a module (`_start`, an export, a component's) has to set it first.
- **One instance test for several readers of one variable**: 0.01 s of `listidx.star`
  (`.kb/defstruct.md`). Not worth a typed local that every landing pad would refresh.

Not measured:

- `dicts`: `d[k] += 1` is three `gethash` (`in`, the read, the store's own before
  `rplacd`); a string key is hashed on every one. `gethash` alone is 29 ns over a loop of
  11 (`equal`, a fixnum key).
- `strings`, `methods`, `sorting`: the guest profile names `_string_concat`, `_str_fresh`,
  `_str_char_count`, `_str_search`, `_str_lit` (a call and a 1,024-entry cache per
  literal), `_str_order` and `%starlark-less` -- by a profiler that misattributes, so each
  on the text first.
- The loop: the counter is copied into the loop variable through `_ub_read` every round
  and the limit unboxed every round (`for i in range(n)` over a parameter `n`).
- A closure's box is a `TYPE_CELL` and carries the vector's two fields: 16 B more on V8,
  none on wasmtime (`.kb/wasm-gc-object-size.md`). A box type of its own touches every
  site that makes or reads a box (`emitNewCell`, `boxedVars`: ten files) for no time on
  wasmtime.

## Do

- A small defun's body at its call site in the wasm backend (the heads above; what
  decides which defuns, and what a site pays in bytes), then the subscript raw into it.
- `dicts`, `strings`, `methods`, `sorting`: each named cost on a hand-edited module, make
  what pays.
- Re-run `time.sh` over the eleven and record the table in `.kb/starlark-frontend.md`.
