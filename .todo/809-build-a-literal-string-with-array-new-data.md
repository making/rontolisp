# `_str_build` walks a literal byte-by-byte where `array.new_data` is one instruction

**Status:** open. The second, independent half of
`801` -- whose first half (the HOST BOUNDARY round
trip) landed 2026-09-13 and is written up in `.kb/wasm-import.md`, "A literal `:string`
argument does not round-trip". Nothing here depends on that; the two touch different
halves of the same detour.

Difficulty: Medium

## The measurement

`_str_build(off,len)` -- the SOLE constructor of every compiled string and symbol value
(`.kb/wasm-gc-strings.md`) -- is **69 bytes** on the `789` reactor, and 66 of them are a
`while (i < len) arr[i] = mem[off + i]` loop copying the literal's bytes out of the
interned data segment into a fresh `$str_bytes`.

`array.new_data $str_bytes $seg (offset) (size)` is ONE instruction that does exactly that
copy, and it would leave `_str_build` at roughly the `struct.new` alone -- call it 20
bytes, so about **-50 on every module that builds any literal string or symbol**, which is
nearly all of them. Unlike `801`'s half, this one is not confined to modules with a host
import.

## The obstacle, which is the whole item

`array.new_data` reads a **passive** segment, and an active one is DROPPED after
instantiation (`array.new_data` on a dropped segment traps). The literals are in an ACTIVE
segment today because other things read the SAME bytes out of LINEAR memory:

- `_intern` / `_rd_memeq` (`WasmReadRuntimeBuilder`) compare a reader token against the
  interned names in linear memory -- present exactly when `usesRead`;
- `801`'s `_lit_stage` `memory.copy`s a literal's bytes out of linear memory;
- the `id` field of every `TYPE_STRING` is the linear OFFSET, and identity (`eq`) is
  offset equality -- that is an identity number, not a read, so it survives either way,
  but check it rather than assume it.

So the question to settle FIRST, before writing any emitter code, is **which programs read
literal bytes out of linear memory at all**. If the answer is "only the reader and the
literal host-boundary staging", then the segment can be passive for every program that
uses neither -- a gate of the `Ctx.charvecPossible` kind -- and the rest keep today's
active segment and today's bytes. If it is "always", the item is a choice between two
copies of the data (passive + active, paying the data section twice) and nothing, and the
honest answer is probably nothing: measure the data section on `zlib` (90,817 bytes total)
before deciding.

A passive segment also needs a **DataCount** section, which `am.ik.wasm.DataDef` does not
emit today (`addActiveData` is its only entry point), and `.kb/wasm-gc-strings.md` states
"No DataCount/`array.new_data`/segment reorder" as a current constraint -- so that file
changes with the code.

## What would settle it

The deliverable is the measurement either way. Build the gate answer over the corpus
(`size-report/programs/`, `examples/browser/*`), then either land the passive segment
behind it and record the new sizes, or record in `.kb/wasm-gc-strings.md` the number that
says a byte loop is what this backend should keep and why.
