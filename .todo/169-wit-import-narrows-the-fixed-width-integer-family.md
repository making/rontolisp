# The import boundary still narrows the fixed-width integer family

The export boundary now carries every WIT fixed-width integer under its own type,
and carries it exactly or traps (`.kb/wit.md`, "The integer boundary"). The
import boundary does not: it maps the whole family onto one designator and keeps
its pre-existing silent narrowing. The two sides of the same contract disagree.

## What is narrow today

- `compiler/WitImportDirective.designatorOf` maps `WitTypeMapper.Rep.INT`
  (`s8` / `s16` / `s32` / `u8` / `u16` / `u32`) to a single `:s32`, so a `u32`
  import parameter or result is lowered as a signed 32-bit value on Preview 1
  WASM. A value at or above 2^31 arrives negative; at or above 2^30 it is
  truncated by the `i31` box.
- `Rep.BIGNUM_INT` (`s64` / `u64`) is not in that switch at all, so a 64-bit
  import is rejected on the Preview 1 path.
- `codegen/wasm/WasmImportCompiler.KNOWN_PARAM_TYPES` is
  `{:s32, :float, :bool, :string, :s-expr}`, so a hand-written
  `rontolisp:wasm-import` cannot name `:u32` either.
- No import wrapper range-checks anything, so the exact-or-trap rule the export
  side now keeps stops at the import seam.

The `--component` import path is NOT affected: it binds through the WIT text
(`WitComponentTypeEncoder` / `WitCanonicalAbi` map all thirteen primitives, and
`WasmComponentImportCompiler` lifts a wide integer into the boxed exact
integer -- `boxI64`/`lowerI64`, `.kb/wasm-bignum.md`). This is a Preview 1 /
`wasm-import` gap.

## Why it was left out of the export work

An import's inbound value is a promise the HOST makes, and every WASI import a
program already makes goes through this seam -- `wasi:clocks` returns `u64`
nanoseconds, `wasi:sockets` is full of `u16`/`u32`, `wasi:http` of `u16` status
codes. Applying the exact-or-trap rule there is not a local edit: it moves the
emitted bytes of every Preview 1 `wit-import` program, and each WASI import
needs its behaviour re-checked against a real host rather than assumed. Doing it
inside the export change would have made one commit that touches two contracts.

## Scope

- Widen `WasmImportCompiler` to the whole `BoundaryType` family (the emit
  helpers are the export side's; nothing new is needed for the codegen).
- Make `WitImportDirective.designatorOf` pick the WIT type's own designator
  instead of collapsing to `:s32`, and decide what `Rep.HANDLE` should be (a
  component-model handle is a `u32`, but it is currently `:s32` and never
  exceeds a small count in practice).
- Decide whether the exact-or-trap rule applies inbound here, or whether an
  import is trusted the way a component-ABI export parameter is trusted. State
  the reason in `.kb/wit.md` next to the export rule either way.
- Lift `s64` / `u64` onto the Preview 1 import path (the boxed exact integer
  is what the `--component` path already uses), or record why they stay
  rejected.

## Verification

- All four backends for a `wit-import`ed interface with a `u32` above 2^30 and a
  `u64` above 2^32, against a real host (the `examples/wit/lisp-calls-rust` and
  `examples/wit/keyvalue` shapes, plus `webgl-common/gl.wit`, whose 33 `s32`
  imports are the byte-identity subjects).
- Byte-identity: a program importing only types whose lowering does not change
  must produce the same module as before.
- `.kb/wit.md`'s "the import side is deliberately NOT widened" paragraph is
  retired in the same pass -- it exists only to point here.
