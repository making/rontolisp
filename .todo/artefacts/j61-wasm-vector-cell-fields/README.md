# The cell of a wasm vector names its storage: the measurements (2026-11-16)

The numbers of `.kb/adjustable-arrays.md`, "What an element access costs",
`.kb/length-runtime.md` and `.kb/starlark-frontend.md`, "The list element on wasm: the
cell names its storage". The Starlark programs and `time.sh` are
`.todo/artefacts/j54-starlark-faster-than-cpython/`, the loops `read.lisp`,
`store-*.lisp`, `length.lisp`, `struct.lisp` and `micro.sh`
`.todo/artefacts/j55-wasm-list-element-cost/`.

Everything here was measured BEFORE the compiler was changed, on modules the compiler
of the day made, edited as text:

```bash
wasm-tools print --name-unnamed m.wasm > m.wat     # every index a name, so a type can move
python3 sim-cell-fields.py m.wat m2.wat 5 3 27     # the cell, the cons, the buckets type
wasm-tools parse m2.wat -o m2.wasm && wasmtime compile m2.wasm -o m2.cwasm
```

- `sim-cell-fields.py` -- the buckets array type moved into the first rec group, a typed
  storage field on every cell, set where a general array is made, and the arm that walked
  the header rewritten over it with typed locals. `listidx.star` 2.06 -> 1.73 s.
- `sim-inline-length.py` -- on its output, `length` (a call of `_seq_len`) inline for a
  cell with the field: `walk` reads the fill pointer through the header, `len` answers the
  storage's own length (the bound of what a cached length could give). `listidx.star`
  1.76 -> 1.54 -> 1.45 s over the two helpers that index.
- `sim-inline-ub-read.py` -- every `_ub_read` (the boxed read of a raw local) inline:
  `loop.star` 0.69 -> 0.66 s for 24 B at each of 88 sites. Not made.
- The instance's slots field as the array's own type, by hand on the same text:
  `listidx.star` 1.57 -> 1.47 s.
- A subtype of the cell for vectors (the first attempt: the cell left as it was, the
  field on a subtype of it): a cast of a subtype instance to the supertype is no exact
  match and costs 8.7 ns, `read.lisp` 0.62 -> 0.88 s with one left in the loop.
- `push.lisp`, `fp.lisp`, `mk.lisp`, `box.lisp`, `ht.lisp` -- `vector-push-extend` with
  room, `fill-pointer`, `make-array`, a closure's box and a hash table's get and set, for
  `micro.sh`: what the fields gave a push (1.64 -> 0.49 s) and cost a `make-array`
  (0.81 -> 0.88 s), and that a box and a hash table did not move.
- `cell3.wat`, `cell4.wat` -- the cell with its two fields, and with the identity-hash
  slot, for `probe.sh` and `measure-v8.mjs` of
  `.todo/artefacts/839-wasm-identity-table-aggregate-keys-share-one-bucket/`
  (`.kb/wasm-gc-object-size.md`).
