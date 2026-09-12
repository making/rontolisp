# Inline the single-call-site functions the shake leaves

Difficulty: Medium (the shape is known and the census says where the bytes are; the work
is the local renumbering and the block wrapping, plus a guard against debris)

First of the three items `791` item 3's measurement split into
(`.kb/optimize-dead-code-elimination.md`, "What an external optimizer still finds, and what
it is made of"). What that measurement corrected: inlining is ~10% of binaryen's residue on
the two large modules (`zlib` 1,985 of 20,237 B; the hello-clack Worker 20,718 of 204,809),
and the enabler of half of it on the small ones (`pi_approx` 257 of 506, `webgl-triangle`
197 of 305) -- there it is what lets a boxed float built by one function and unboxed by the
next collapse (`struct.new $float; call _as_f64` at the size level, where fusion is off).

The census (`analyze.py` in `.todo/artefacts/791-module-level-slack-globals-types-data-hooks/`):
`zlib` has 145 non-root functions with exactly one `call` site, 39,836 of its 86,432 code
bytes (58 lambdas, 41 chipz defuns, 18 `%`-internal defuns, 22 runtime `FUNC_*` helpers,
the top-level chunk, one arity dispatcher); the Worker 44 such functions, 139,771 B.
binaryen inlines every one of them and seven tiny multi-call ones.

## What the pass is, and the trap

Move the callee's body to its one call site: the callee's params become fresh locals of
the caller (the arguments are on the stack in order, so `local.set` them in reverse), its
locals are appended after the caller's, every local index in the moved body is shifted, a
`return` inside it becomes `br` to a wrapping `block` whose type is the callee's result
type, and the callee's function-section entry and code entry go. Byte-level in
`am.ik.wasm` over `WasmCodeModel`, after `WasmCallForwarding.redirect` and before the
shake; a callee that is exported, the start function, a hook, recursive, or the caller
itself is out; a callee reached through a `ref.func` would be too, but the backend emits
none.

**The trap: `wasm-opt --inlining` alone makes `zlib` 15,214 B BIGGER**, because the moved
parameters become `local.set`/`local.get` pairs the caller never had and every local index
past 127 costs a second byte. The deliverable is the move PLUS its own clean-up: an argument
that is a `local.get` of a caller local the body never assigns needs no new local at all;
a parameter read once, immediately, is a `local.tee`-free stack value; and a callee whose
locals push the caller past 128 is measured before it is moved. Without that arithmetic
the pass is a net loss on exactly the modules it is meant for.

## Guards

`WasmTreeShakerCorpusTest` (validate + round trip, `default` and `size`),
`WasmRefTypeFoldHostSuppliedValuesE2eTest`, and a size assertion on
`size-report/programs/` plus the Worker: the pass must not grow any of them. Measure the
residue again with the scripts; a number from one micro program is not a result.
