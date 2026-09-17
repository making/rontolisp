# wasm-GC object size: what a struct field costs on the heap, measured

**Invariant: the heap cost of a struct field is decided by the engine's object layout, not
by the field's width, so it is MEASURED per engine before it is weighed -- and on wasmtime
a cons carries up to 16 bytes of fields for the price of two references.** The numbers
below are why `.kb/hash-tables.md`'s identity-hash slot (a third `(mut i32)` on every
cons of a module that makes an `eq`/`eql` table) was accepted: the todo that asked for it
assumed "a cost paid by every cons is likely not worth it", and the measurement said the
cost is zero where the compiled modules run.

## The method

A hand-written module allocates `n` structs of one shape into a linked list through a
global (`alloc(n)`), so nothing is collectable, and the engine is asked how many fit.

- **wasmtime**: a HARD-CAPPED GC heap, `wasmtime run -O gc-heap-may-move=n -O
  gc-heap-reservation=<bytes> -O gc-heap-reservation-for-growth=0 --invoke alloc m.wat n`,
  bisected on `n` (the copying collector's semi-space is half the reservation). Without
  `gc-heap-may-move=n` the reservation is not a cap: the heap moves to a larger mapping and
  every `n` fits. RSS is useless for this -- the semi-space doubles, so two layouts that
  differ by 25% land on the same RSS.
- **V8** (node): `v8.getHeapStatistics().used_heap_size` before and after `alloc(n)`, both
  behind a forced `gc()` (`--expose-gc`); the delta divided by `n`.

The modules and the two drivers are kept in
`.todo/artefacts/839-wasm-identity-table-aggregate-keys-share-one-bucket/`.

## The numbers (2026-09-17, linux-x86-64)

wasmtime 47.0.3 (`5554cc1a6`, copying collector): the largest `n` that fits a 64 MiB
reservation, and what it says per object.

| shape | fields | fits | traps | bytes/object |
| --- | --- | --- | --- | --- |
| cell `{eqref}` | 4 | 1,040,000 | 1,050,000 | 32 |
| cell `{eqref, i32}` | 8 | 1,040,000 | 1,050,000 | 32 |
| cons `{eqref, eqref}` | 8 | 1,040,000 | 1,050,000 | 32 |
| cons `{eqref, eqref, i32}` | 12 | 1,040,000 | 1,050,000 | 32 |
| `{eqref, eqref, i32, i32}` | 16 | 1,040,000 | 1,050,000 | 32 |
| `{eqref, eqref, i32 x4}` | 24 | 690,000 | 700,000 | 48 |

So a wasmtime object is a 16-byte header plus its fields, ROUNDED UP TO 16: a 4-byte
reference is 4 bytes, and every shape up to 16 bytes of fields is one 32-byte object. The
128 MiB reservation confirms the reading (2,080,000 two-field conses fit, 2,100,000 trap).
**The identity-hash slot costs a cons, a cell and an instance nothing on wasmtime.**

V8 13 (node 24.19, no pointer compression, so a reference is 8 bytes): `used_heap_size`
delta per object over `n = 1,000,000`.

| shape | bytes/object |
| --- | --- |
| cell `{eqref}` | 24 |
| cell `{eqref, i32}` | 32 |
| cons `{eqref, eqref}` | 32 |
| cons `{eqref, eqref, i32}` | 40 |
| `{eqref, eqref, i32, i32}` | 40 |
| `{eqref, eqref, i32 x4}` | 48 |

A 16-byte header plus 8-byte-aligned fields: **on V8 the slot is +8 bytes per cons (+25%)
and per cell (+33%)**, which is why the slot is GATED on the module making an identity
table rather than declared everywhere -- a Cloudflare Worker (`.kb/size-measurement.md`)
runs on V8, and a module that never keys by identity keeps the two-field cons byte for
byte. Chrome and workerd compress pointers (4-byte references), where the same layout
rule would put a two-field cons at 16 bytes and the slot at +8 (+50%); not measured.

## What this does not say

- Nothing about SpiderMonkey, or about the browser playground's V8 with pointer
  compression: measure before quoting.
- Nothing about arrays: `TYPE_HASH_BUCKETS` and the packed `TYPE_F64ARR`/`TYPE_I32ARR`
  storage are wasm `array` types, which have no field to add. A packed array as an
  identity key therefore still hashes to `_hash`'s constant 0 (`.kb/hash-tables.md`).
- The MODULE cost is separate and is what `.kb/size-measurement.md` is about: the slot is
  two bytes (`i32.const 0`) per allocation site, `_ihash`'s body and one global -- +202
  bytes on the 10,566-byte cons-keyed benchmark module; a shaken module carries six to
  twenty-one cons sites, so the sites are a few dozen bytes.
