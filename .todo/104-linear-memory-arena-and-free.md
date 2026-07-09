# 104 — a way to FREE linear memory: `with-arena`, and `__ronto_alloc_mark`/`_reset` on wasm-GC

**Goal:** give both WASM backends a real reclamation mechanism for the bump-allocated linear heap,
usable from Lisp (not only from the host across an export boundary).

Sequenced AFTER `.todo/103` (destination-passing kernels), which removes the *need* to free in the
common case by making the bump high-water equal the live set. 104 handles what 103 cannot reach:
`linalg:` transforms (fresh array every call), `vec:zeros`/`from-list` inside a loop, and any
`--simd` wasm-GC program once `.todo/101` moves packed arrays into linear memory.

## Where things stand

Both WASM backends bump-allocate and never free:

| | `--no-gc` | wasm-GC |
|---|---|---|
| heap pointer | wasm global 0 | linear memory cell `HEAP_PTR_ADDR = 84` |
| bump allocator | `__alloc` (4-byte aligned) | `__ronto_alloc` (8-byte aligned) |
| exported | when `mem.used()` | when a memory-typed export exists |
| `__ronto_alloc_mark` / `__ronto_alloc_reset` | **yes** (todo-89) | **no** |
| auto-reset on scalar-return export | **yes** (todo-88) | **no** |

`--no-gc` survives because it is *always* a reactor — top level is defuns + `wasm-export`
directives, no `_start` — so "one export call = one arena" always holds and the boundary pops it.

## What transfers to wasm-GC, and what does not

**Transfers (cheap).** `__ronto_alloc_mark` = `i32.load HEAP_PTR_ADDR`,
`__ronto_alloc_reset` = `i32.store HEAP_PTR_ADDR`. Two tiny functions, the `--no-gc`
`markBody`/`resetBody` with a memory cell in place of global 0. wasm-GC already exports
`__ronto_alloc` and already has reactor mode (`rontolisp:wasm-export` + `--no-wasi`, exporting
`_initialize` instead of `_start`), so a resident host can bracket its calls immediately.

**Does NOT transfer: todo-88's automatic reset on a scalar return.** `--no-gc`'s `collectCalls`
rejects cons, closures, hash tables, `eval` and global `setq` outright, so nothing allocated during
an export call can outlive it except the returned pointer — the auto-reset is sound *by
construction*. wasm-GC has all of those. A packed array stashed in a top-level global or a closure
capture keeps its **GC struct handle** alive while the reset pops its **linear data block**; the
block is still in-bounds, so there is no trap — just silently wrong floats. Either gate it behind a
static "no packed array escapes an export call" analysis, or don't emit it.

**`HEAP_PTR` on wasm-GC is not a pure bump.** Per `.kb/wasm-gc-strings.md` it is a *stack pointer*
with two disciplines sharing it: a permanent low-water (the interned-symbol byte pool, which
`_intern` copies into and advances; plus string-stream buffers) and a transient region above it
(runtime string builds, assembled then popped). Popping below the intern high-water discards bytes
the runtime intern registry (`RT_INTERN_COUNT_ADDR`, cell 100) still points at. This is exactly why
the serve adapter's per-request reset is **intern-count-guarded**; any wasm-GC arena needs the same
guard.

## The design: `with-arena` is mark/reset with the boundary named in Lisp

The default wasm-GC output is a `_start` command module — there is no host call boundary to hang a
reset on. The boundary has to come from the source:

```lisp
(dotimes (i epochs)
  (rontolisp:with-arena ()
    (train-step net batch)))     ; everything allocated inside is popped here
```

`with-arena` = `mark` on entry, `reset` on exit. Same primitive as the host-facing pair, exposed to
the program. Consequences:

- Works on **both** WASM backends, and therefore also closes `--no-gc`'s intra-call hole (the one
  103 cannot reach).
- On the interpreter, the JVM and default wasm-GC it expands to a plain `progn` — a real GC is
  already doing this. Cross-backend semantics are uniform; only the reclamation is backend-specific.
- Escape contract, inherited from `__ronto_alloc_reset`'s existing one: **nothing allocated inside
  the body may be reachable after it**, except the body's own value. A packed-array value must be
  copied down to the mark before the pop (`dst < src`, so a single forward byte copy is a safe
  memmove). Strings/conses/closures on wasm-GC are GC objects and are unaffected; only
  linear-backed values (`--no-gc` strings and vectors; wasm-GC `--simd` packed arrays) participate.
- Must be intern-count-guarded on wasm-GC (skip the pop if a new symbol was interned inside the
  body), or `with-arena` must reject a body that can intern (`read`/`load`/`intern`/`gensym`).

Open question worth settling before implementing: whether `with-arena` should refuse to pop (and
warn) rather than silently skip when the guard trips.

## Steps

1. `__ronto_alloc_mark` / `__ronto_alloc_reset` on wasm-GC. Appended after the existing memory
   helpers — but note the GC backend HAS a fixed-`FUNC_*`-index invariant and byte-identical
   component blobs, unlike `--no-gc`, so check where new function indices may be inserted
   (`.kb/wasi-component.md`) before choosing the position.
2. `rontolisp:with-arena` as a `LispMacroExpander` lowering: `progn` on interpreter/JVM/default
   wasm-GC; mark/body/reset on `--no-gc` and wasm-GC `--simd`.
3. The copy-down of a packed-array result value.
4. The intern-count guard.
5. Decide on todo-88-equivalent auto-reset for wasm-GC reactor exports (probably: don't).

## Verify

- `--no-gc`: a loop of `(with-arena () (vec:zeros 1000))` leaves `memory.size` flat; without the
  arena it grows. Same shape as the `count-vowels` host-arena measurement in
  `.kb/no-gc-scalar-wasm.md` (100000 calls, 65536 → 65536 with the bracket).
- wasm-GC `--simd` (after `.todo/101`): same, under `wasmtime run -W gc`.
- Interpreter / JVM / default wasm-GC: `with-arena` is observationally a `progn` (same value, same
  side effects, ci-spec cross-backend case).
- A pinning test for the escape contract: a value allocated inside and stored to a global is
  documented-undefined; assert only that the guard cases (intern inside the body) do not corrupt.

## Related

- `.todo/103` — destination-passing kernels (do first; makes the leak bounded).
- `.todo/101` — wasm-GC linear-memory packed arrays for `--simd` (the consumer of this).
- `.kb/no-gc-scalar-wasm.md` (todo-88 / todo-89), `.kb/wasm-gc-strings.md` (HEAP_PTR discipline),
  `.kb/wasi-component.md` (fixed function indices).
