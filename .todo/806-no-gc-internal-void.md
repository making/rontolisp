# `--no-gc` has no internal void: every statement pushes an integer 0 for somebody to drop

**Status:** open. Measured 2026-09-13 on `fcb0e8738`.

Difficulty: Medium

Found while measuring [`805`](805-no-gc-literal-import-call-sites.md) and
[`804`](804-no-gc-module-surface.md) on the same browser reactor.

## The shape

`nil` is the integer `0` in this backend's value model, and there is no internal VOID: a
form evaluated for effect still produces a value, so

- every `:void` import wrapper ends with `i64.const 0` (2 bytes) and every caller `drop`s
  it (1 byte);
- a void forwarder's type is `(… ) -> i64` rather than `(…) -> ()`, so it needs its own
  type entry rather than sharing one;
- `InitApp` and `AppendLogMessage` remain export WRAPPERS -- 15 and 18 bytes -- for no
  reason except to `drop` that 0 and close the arena bracket. Their internal functions
  return an integer where the export type is `()`, so `isPassThroughExport` refuses them.

Measured on the reactor: **~17 bytes** of `i64.const 0` / `drop` pairs, plus the two export
wrappers those pairs keep alive.

## What to do

A fourth point in the value lattice -- `VOID`, joining like `INT`-bottom but emitting no
value -- so that a form in statement position produces nothing and a `:void` boundary is
`(…) -> ()` on both sides.

- The arithmetic half of the lattice is untouched: `VOID` never meets `INT`/`FLOAT` in an
  expression, only in "what does this statement leave on the stack".
- `.kb/no-gc-scalar-wasm.md` documents `%block`/`while` leaving nil as the i64 zero; that
  is the same decision and should move with this one or be stated as deliberately staying.
- It is the true fix for `AppendLogMessage`'s remaining bytes, and it is what lets the two
  void exports reach `isPassThroughExport` at all.

## The layout coupling in front of it

`isPassThroughExport` has a second gate, `if (mem.used()) return false;`, and half of it is
not semantic. `NoGcWasmCompiler.planMemory` computes `allocIndex = funcBase + internalCount
+ exportDecls.size()` -- **one wrapper per export, assumed before the pass-through decision
exists** (the comment at that line says so). A spike that elided more wrappers than planned
threw `Index 25 out of bounds`.

So decoupling the helper index base from the export count is a **prerequisite for anything
that elides a wrapper**: this item, `804` item 3's per-wrapper arena gate, and any future
pass-through widening. It is bookkeeping, not design, and it should probably be done first
and on its own.

The semantic half of that gate is real and stays: the arena bracket reclaims what the body
allocated, so a wrapper may only be elided when nothing reachable from the export can bump
the heap.

## Not to be confused with

An i32 tier. That was measured on the same program (123 bytes with `:s32` pass-through) and
**rejected**: every boundary value can fit `:s32` while an intermediate crosses 2^31, so
`(mod (* n n) 1000)` and `(mod (fact 13) 10000)` return silently wrong answers on exactly
the programs `doc/*/guides/wasm-nogc.md` advertises this backend for. The measurement and
the reasoning are in this file's commit message and were not filed as a todo, because the
answer is "no".

## Touch points

- `codegen/wasm/NoGcWasmCompiler.java` (`Ty`/`Ty.join`, `valType`, statement position,
  `planMemory`, `isPassThroughExport`)
- `.kb/no-gc-scalar-wasm.md`
