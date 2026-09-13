# `--no-gc`: a literal that only ever crosses a folded import site carries a length header nothing reads

Difficulty: Medium

**Status:** open, measured 2026-09-14 against `c972efa5d`.

## What happens

`NoGcWasmCompiler.planMemory` lays every string literal out as
`[len:i32 little-endian][UTF-8 bytes]` and hands the HEADER's address to
whatever references it, because a string in this backend is an `i32` pointer to
that header (`.kb/no-gc-scalar-wasm.md`, "A string is an `i32` pointer to ...").

`.todo/805` then taught the backend to fold a literal `:string` argument at a
host import call site into two compile-time constants -- the content pointer
(`headerAddr + 4`) and the byte length -- and to drop the wrapper whose only job
was to compute that pair from the header at run time.

**Nothing puts those two facts together.** When every use of a literal is a
folded import site, its `[len]` header is dead: the length is already a constant
in the code, and the pointer handed over skips the header entirely. The four
bytes are emitted anyway.

In the reactor benchmark archived beside this file the module contains **no
`i32.load` at all** -- every reference to static data is an `i32.const` pair:

```wat
(func (;4;) (type 0)
  i32.const 12     ;; content = header + 4
  i32.const 47     ;; length, a constant
  call 0           ;; the host import, directly
  ...
```

Eleven literals, eleven dead headers, 44 bytes.

## Measured

`.todo/artefacts/810-no-gc-dead-literal-length-headers/reactor.lisp` at
`--no-gc --no-wasi --optimize=size`. The "after" column is not a prediction: the
headers were stripped out of the emitted binary by hand and the fourteen address
constants rewritten (every LEB128 immediate kept its width, so the code section
does not move), then `wasm-tools validate` and the archived `host.mjs` were run
against both modules.

| | before | after | delta |
| --- | ---: | ---: | ---: |
| raw | 930 | **886** | -44 |
| `gzip -9` | 650 | 626 | -24 |
| data payload | 484 | **440** | -44 |
| code payload | 230 | 230 | 0 |
| every other section | -- | unchanged | 0 |

`host.mjs` output is byte-identical across the two modules.

Measure gzip as `gzip -9 -c < file`, never `gzip -9 -c file`: the second form
stores the filename in the header, so a longer filename reads as a bigger
module.

## What to do

In `planMemory`, classify each DISTINCT literal:

- **header-free** -- every occurrence of that string, in every reachable body,
  is in a `:string` argument position of an import that will be folded. Lay it
  out as raw bytes and let the folded site push the raw address.
- **headered** -- everything else, unchanged.

A literal used both ways stays **headered** and both uses keep working (the
folded site simply goes on using `headerAddr + 4`). Never emit a literal twice:
the pool is deduplicated by string and the duplicate would cost more than the
header.

The literals `planMemory` adds for printing (`"\n"`, `"\""`, `"\\"`, `"T"`,
`"NIL"`) and for float rendering (`"NaN"`, `"Infinity"`, `"-Infinity"`) are
returned as header pointers by the runtime helpers and must stay headered. They
have no folded-site occurrence, so the default should already hold -- confirm it
rather than assume it.

## The ordering problem

`chooseFoldedImports` runs AFTER `planMemory` and takes the layout as an input,
because it sizes each candidate folded site and a site's cost includes the
LEB128 width of its address constants. The classification needs the fold set,
and the fold set is currently decided from the layout.

A provisional layout is an acceptable SIZING input -- addresses only move a
constant's width by a byte here and there -- so deciding the fold set against a
provisional layout and then laying memory out for real is legitimate. The
correctness requirement is only that the FINAL layout and the FINAL emitted
constants agree. Whichever way it is resolved, say so in the code: the next
reader will hit the same circularity.

## What to verify

- `STR_DATA_BASE` stays the base of the segment; the Schubfach tables that
  follow the literals keep their 4-byte alignment; `heapBase` is recomputed from
  the new cursor.
- A literal used at a folded site AND by `length`; AND by `print`; the same
  string appearing folded-only in one function and not in another; a literal
  used only in non-import positions; an empty literal in a folded position; a
  UTF-8 literal in a folded position (the length is in BYTES); and a program
  where NO import folds, whose layout must be byte-identical to today's.
- The interpreter is the oracle: compile each probe WITH wasi, run it under
  `wasmtime`, and diff stdout against `java -jar ... probe.lisp`, at
  `--optimize=off` AND `--optimize=size`. A lowering bug shows up as a
  disagreement between the levels. Note this backend has no `format`: take
  runtime strings in as `:string` export parameters instead.
- The pinned layout expectations, `stringLiteralsArePackedWithoutAlignmentPadding`
  and the fold's own `aLiteralStringArgumentReachesTheHostWithoutTheWrapper` /
  `aModuleThatOnlyPassesItsOwnLiteralsOutOmitsTheArenaApi`.

## Prototyped 2026-09-14 -- what it cost and what it broke

A working prototype confirmed the figures exactly (930 -> 886, data 484 -> 440,
code unchanged; `--optimize=off` 1353 -> 1309, the same 44 bytes) and the GC
backend's output stayed md5-identical. What it learned:

- **The circularity resolves cleanly.** Split the literal-laying half of
  `planMemory` into its own function, let `planMemory` return an all-headered
  plan, decide the fold against that, then re-lay the SAME literal order with
  the header-free set applied. Only the data bytes and the addresses derived
  from them move; every gate (`printUsed`, `ftoaUsed`, `hostArena`, `allocates`)
  is a property of the bodies and the boundary, not of the layout.
- **Keep two maps, not one.** Content-address-for-every-literal, and
  header-address-for-the-headered-ones. A value-position use of a header-free
  literal then fails loudly on a missing header address instead of reading a
  neighbour's bytes as a length.
- **One pinned test genuinely changes.**
  `NoGcWasmCompilerTest.stringLiteralsArePackedWithoutAlignmentPadding` pins
  `[3,0,0,0,a,b,c,2,0,0,0,d,e]` for a program whose only use of `"abc"` and
  `"de"` is a `js-log` site -- which is exactly the case that now emits
  `abcde`. The test's own claim (no padding between headered blocks) is still
  true, so give it a program that reads both literals as values and pin the
  header-free shape in a new test beside it. The fold's other two tests pass
  unchanged.
- **`--optimize=off` moves a byte outside the data section** on some programs:
  a lower `heapBase` can shorten its LEB128 in the global section.
- **`ng805.mjs` does not exercise this path at all.** Every one of its import
  call sites has a runtime argument somewhere, so the fold is declined and its
  module is byte-identical before and after. That makes it a good check that a
  non-folding program's layout does not move, and NOT a regression harness for
  the fold. Write a probe that actually folds.

## A hazard the fold decision has either way

`chooseFoldedImports` weighs a site's cost against the wrapper's, and does not
count the four bytes per literal that folding now also saves. An import sitting
just the wrong side of that comparison would fold if it did. Folding is a
whole-program per-import decision and spellings can be shared between imports,
so the accounting is not local -- leave it alone unless a measurement asks for
it, but know it is there.

## A second phase, filed separately

`print` of a literal has the same two constants available and does not use them.
That turned out to be a bigger and differently-shaped win than this item (the
code section, not the data section), so it is `.todo/814` rather than a
follow-on here.

## Where the remaining sections stand

For the same benchmark the data section was the last one behind a comparable
hand-written module (484 against 451); 440 puts it ahead. The only other section
still larger is the type section, 36 against 31, and that one is CLOSED: all
seven entries are used and none duplicate, and the extra entry is `fib`'s
`(i64)->(i64)` where a 32-bit implementation shares one `(i32)->(i32)` type
between `fib` and an export. That is the i64-native value model's price and the
i32 tier is already rejected -- it answers 3504 for `(mod (fact 13) 10000)`.

## Artefacts

`.todo/artefacts/810-no-gc-dead-literal-length-headers/` -- the benchmark, the host
harness, the section dumper, `strip.mjs` (the hand verification, no build needed),
`prototype.diff` (the working implementation), and `probe/` (the classification harness
that `ng805` is not). Its README is the reproduction recipe.
