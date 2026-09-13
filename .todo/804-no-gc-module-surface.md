# `--no-gc` emits every function type twice and exports an arena nothing can reach

**Status:** open. Measured 2026-09-13 on `04ddbb15b`.

Difficulty: Low

## The program

A browser-facing reactor: four host DOM imports (three taking two `:string`s), four thin
Lisp helpers over them, a recursive `fib`, four exports -- `InitApp`, `AddNumbers(:s32
:s32)->:s32`, `RunComputation(:s32)->:s32`, `AppendLogMessage(:s32)`. Seven string literals
cross to the host; nothing crosses in.

`--no-gc --no-wasi --optimize=size` = **1,383 bytes**:

| Section | bytes | count |
| --- | ---: | ---: |
| code | 491 | 20 |
| data | 498 | 1 |
| types | **129** | **24** |
| exports | **128** | **8** |
| imports | 78 | 4 |
| functions | 21 | 20 |
| globals | 7 | 1 |

Two of those rows are almost entirely avoidable, and both are the same mistake the wasm-GC
backend already stopped making.

## 1. The type section declares 24 types, 12 of which are distinct (-68 bytes)

```
x6  (func (param i32 i32) (result i64))
x3  (func (param i64) (result i64))
x3  (func (param i32 i32 i32 i32))
x2  (func (param i32) (result i64))
x2  (func (param i32) (result i32))
x2  (func (param i32))
```

One entry per function, with no deduplication at all. Encoded: 128 bytes as emitted, 60 if
each distinct signature were written once -- **68 bytes, 4.9% of the module**, on a change
that touches no code and no semantics (the function section holds type INDICES; they are
remapped, nothing else moves).

For scale, that section is 129 bytes here against 31 bytes for a non-GC toolchain emitting
the same program with six types.

## 2. Three arena entry points are exported that nothing can call (-124 bytes)

```
(export "__ronto_alloc"       (func 21))   ;; 55 bytes of body
(export "__ronto_alloc_mark"  (func 22))   ;;  4
(export "__ronto_alloc_reset" (func 23))   ;;  6
```

They exist so a HOST can allocate a buffer to pass a memory-typed value INTO the module.
**This program has no memory-typed export parameter** -- every string goes module-to-host,
and an import's `:string` argument is the module's own block (`.kb/no-gc-scalar-wasm.md`).
Nothing internal calls them either; they are roots only because they are exported.

65 bytes of bodies plus 59 bytes of names and export entries = **124 bytes, 9.0% of the
module**, for an API this module's boundary cannot use.

**This is exactly the shape `791` item 2 fixed on the wasm-GC side** for
`__ronto_seed_random`/`__ronto_set_time`: emit the hook when the program can reach what it
serves, keep emitting it when the answer is unknown. Here the question is easier than
there -- it is a property of the EXPORT DECLARATIONS, not of the call graph: any export
with a `:string`/`:s-expr`/`:bytes` parameter needs the host to allocate, nothing else
does. Do not make it cleverer than that.

## 3. Smaller, same family

- **The arena bracket on a wrapper that cannot allocate** (~6 bytes x 4 exports = 24).
  Every export wrapper opens with `global.get 0; local.set n` and closes with `local.get n;
  global.set 0`, saving and restoring the arena pointer. `AddNumbers` allocates nothing.
  Gate it on the same answer as item 2, per wrapper.
- **A boolean widened to i64 and compared back to a boolean** before an `if`: `i32.eq;
  i64.extend_i32_s; i64.const 0; i64.ne; if` where the `i32.eq` already produced what `if`
  wants. Three sites here, ~5 bytes each. The `t` arm of a `cond` also emits a
  constant-true test (`i64.const 1; i64.const 0; i64.ne; if`), ~7 bytes. 22 bytes in this
  module, and it scales with branching code rather than with module size.
- **Pure forwarders are not inlined**: the four Lisp helpers compile to `local.get 0;
  local.get 1; call N` bodies (~9 bytes each) in front of the import wrappers, so every
  host call goes through two levels. That is
  [`800`](800-single-call-site-inliner.md)'s single-call-site inliner, not this item; noted
  because it is visible here and because each forwarder also costs a type entry that item 1
  would already have folded.

## What it adds up to

Measured: 68 + 124 + 24 + 22 = **238 bytes, 17% of the module**, none of it requiring a
decision about semantics. 1,383 -> ~1,145.

## Touch points

- `codegen/wasm/NoGcWasmCompiler.java` (the type section, the export section, the wrapper
  prologue/epilogue, the boolean lowering)
- `am/ik/wasm/` if the type-table deduplication belongs with the writer rather than the
  backend -- check whether the wasm-GC backend already folds its own, and share it if so
- `.kb/no-gc-scalar-wasm.md`
