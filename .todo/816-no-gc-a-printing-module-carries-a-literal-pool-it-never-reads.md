# 816: a printing `--no-gc` module carries a literal pool and a heap bracket it never reads

Difficulty: Medium

`hello_world-nogc.lisp` -- `(princ "Hello, World!") (terpri)`, the smallest thing the
backend emits -- is 211 bytes at `--no-gc --optimize=size`, and **45 of them are
unreachable**. Rewriting the emitted binary to drop them gives a module that validates
and prints the same text at **166 bytes (-21.3%)**.

Three separate misses, all of the same shape: the printing SUPPORT surface is emitted
because the module prints, not because the module uses it.

## 1. The escape/boolean literal pool is unconditional

Every module that prints carries five literals, whatever it prints:

```
"\n"  "\""  "\\"  "T"  "NIL"     -- 27 bytes with their [len] headers
```

`runtimeLiterals(printUsed, ftoaUsed)` adds all five the moment anything prints. But
`princ` of a string needs only `"\n"`, and `print` needs the two escapes. Measured at
`--optimize=size` by compiling four one-line programs and counting the segment bytes no
`i32.const` in the code section can name:

| program | segment | unreachable | exact? |
| --- | ---: | ---: | --- |
| `(princ "hi") (terpri)` | 29 | 26 | yes |
| `(print "hi") (terpri)` | 33 | 17 | yes |
| `(princ n) (terpri)` | 27 | 7 | lower bound |
| `(princ (> n 0)) (terpri)` | 27 | 7 | lower bound |

The last two are a lower bound because the probe cannot tell an arithmetic constant that
lands in the segment's address range from a header pointer (see **Probe** below); the
two literal-only rows have no such constant and are exact.

**`T` and `NIL` are unreachable in every `--no-gc` module compiled here, including the
one that prints a boolean** -- that backend renders a boolean as `1`/`0`, so nothing
reaches the two literals the pool lays down for it. That is a cross-backend defect in its
own right and is `.todo/817`; the two items have to be settled together, because fixing
817 makes `T`/`NIL` live exactly where a boolean is printed and leaves them dead
everywhere else -- which is the gating this item is asking for.

`ftoaUsed` adds `NaN` / `Infinity` / `-Infinity` on the same all-or-nothing basis. Those
three are genuinely reachable (any f64 can be a NaN), so they are not part of this item.

Emit each of the five on first use, the way a program literal already is.

## 2. A `princ`-folded literal's `[len]` header is dead

Exactly the rule `.todo/810` applied to import-folded literals, now applicable to the
second class of folded literal. A literal written as `i32.const <addr>; i32.const <len>;
call` never has its header loaded, so the four bytes in front of it are dead. `"\n"` is
that literal in every printing module; a `princ`-only spelling of the program's own text
is another (`.todo/814` already lays those out header-free, so what is left is the
built-in pool's).

## 3. The heap bracket and the heap global survive a module that bumps nothing

After `.todo/814` a module whose only printing is folded literals allocates nothing at
all, yet the wrapper still brackets the call:

```wat
(func (;1;) (type 1)
  (local i32)
  global.get 0        ;; save the heap mark
  local.set 0
  i32.const 8
  i32.const 13
  call 2              ;; write "Hello, World!"
  i32.const 25
  i32.const 1
  call 2              ;; write "\n"
  local.get 0
  global.set 0)       ;; restore it
```

Nothing between the two touches global 0: `__write` stores the iovec into fixed scratch,
and neither literal is allocated. The bracket is 8 bytes, the scratch local 2, and with
the bracket gone **nothing in the module reads or writes the heap pointer at all**, so
the global section (8 bytes) goes too.

`Mem.allocates()` is what decides this, and its "the module prints" clause is what has
become over-conservative: `__itoa`/`__ftoa` allocate the text they return, but a folded
literal write does not. The clause wants to be "the module prints something that is not
a folded literal".

## Measured

Hand-verified by rewriting the EMITTED binary (`.todo/artefacts/816-.../dead816.mjs`) --
no compiler change, so the numbers are a floor a real pass has to reach, not a prototype's
claim. Every rewritten module validates under `wasm-tools validate` and prints byte-for-byte
what the original printed.

| program | before | after | delta | gzip |
| --- | ---: | ---: | ---: | --- |
| `size-report/programs/hello_world/hello_world-nogc.lisp` | 211 | **166** | -45 (-21.3%) | 205 -> 169 |
| `(princ "Hello, world!") (terpri)` | 206 | 161 | -45 (-21.8%) | 198 -> 164 |
| `(princ "hi") (terpri)` | 194 | 150 | -44 (-22.7%) | 185 -> 152 |
| `(print "hi") (terpri)` (part 1 only) | 325 | 308 | -17 | |

`hello_world_nogc` is a row in the periodic size report, so this one lands on a tracked
number.

## Where it does NOT pay

A module that prints a NUMBER reaches `__itoa`, which allocates, so part 3 does not apply
to it and part 1 is worth 10 bytes. `report.lisp` (nine `princ` sites, one of them a
number) keeps its bracket. The win is concentrated in the small literal-only modules --
which is exactly where a 45-byte constant matters as a fraction.

## Not to be confused with

- `.todo/810` removed dead headers for IMPORT-folded literals only; part 2 is the same
  rule for the print-folded ones and for the built-in pool.
- `.todo/813`'s `__char_at` helpers are gated on the operator that calls them already --
  that gating is the pattern part 1 wants.
- `.todo/817` is the correctness half of the same gap: `T`/`NIL` are dead because
  `--no-gc` never prints a boolean as `T`/`NIL`. Settle that first, or part 1 gates a
  literal on an operator that can never want it.

## Probe

`.todo/artefacts/816-no-gc-dead-print-support/` -- `dead816.mjs` reads liveness off the
CODE section (after `.todo/814` a data segment is a mixture of headered and headerless
literals and cannot be walked structurally) and rewrites the binary. It is a MEASUREMENT
PROBE: on a module that prints numbers it over-approximates liveness, because an
arithmetic constant that happens to fall in the segment's address range is indistinguishable
from a header pointer without dataflow. The numbers above are from literal-only modules,
where no such constant exists and the count is exact.
