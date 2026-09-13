# `--no-gc`: a printed literal is two constants too, but only `terpri`'s newline gets them

Difficulty: Medium

**Status:** open, measured with a deliberately unsound prototype 2026-09-14
against `c972efa5d`. The numbers below need re-measuring on a correct one.

## What happens

Printing funnels through `__write_stdout(ptr, len)`. There are two ways to reach
it with a string:

- `emitWriteLiteral` pushes the content address and the byte length as two
  compile-time constants. This is what `terpri`'s `"\n"` uses.
- The generic path pushes the header pointer and computes the pair at run time:
  `local.tee; i32.const 4; i32.add; local.get; i32.load; call`.

`(princ "hello")` takes the generic path even though its argument is a literal
and both halves are as constant as `terpri`'s. In the emitted module the two
sit side by side:

```wat
    i32.add          ;; the runtime path, for the literal "hello"
    local.get 1
    i32.load
    call 2
    i32.const 21     ;; terpri's "\n", already folded
    i32.const 1
    call 2
```

Same shape as the import-argument fold in `.todo/805` and the same reason it
pays: a literal's `(ptr, len)` needs no arithmetic.

## Measured

From a prototype built only to get the number: `(princ <literal>)` lowered
through `emitWriteLiteral`, with those occurrences also counted as needing no
`[len]` header (`.todo/810`). `--no-gc --optimize=size`:

| program | before | after | code | data |
| --- | ---: | ---: | --- | --- |
| one `princ` of a literal plus `terpri` | 219 | **206** | 63 -> 54 | 50 -> 46 |
| nine `princ` literal sites over seven spellings | 765 | **660** | 504 -> 429 | 141 -> 112 |

**Where the win is:** the code section, roughly 8-9 bytes per site, not the data
section. Dropping the literal's four-byte header is a bonus, not the point --
which is the opposite of `.todo/810`, where the header IS the point. The two
items compose (a `princ`-only literal becomes header-free once its print sites
fold) but each stands alone.

Interpreter and `wasmtime` agreed on both programs, and the non-printing
benchmarks were unaffected.

## Why the prototype was unsound, and what a correct one must do

The prototype lowered every `(princ <literal>)` as VOID. That is wrong:
`princ` returns its argument, and a program that consumes the value would get
nothing. The fold is only legal where the form's value is discarded -- statement
position.

`compileStatement` knows that, but it knows it at EMISSION time, and
`.todo/810`'s classification needs the answer BEFORE the layout is fixed. So the
statement-position flag has to be carried down the reachability walk
(`collectCalls`) alongside the call sites, the same way `importCallSites`
already records where a literal crosses to a host import. Do that rather than
deciding it twice.

Also: a `princ` whose value IS used can still fold its two constants -- it just
has to leave the argument behind as a value as well. Measure whether that case
is worth a separate shape or should simply be left on the generic path.

## What to verify

- `(princ "x")` in statement position, in value position, as the last form of a
  function whose result is used, and as the last form of a `:void` export.
- `princ` / `print` / `princ-to-string` do not all funnel the same way -- check
  each before assuming the fold applies.
- A literal printed AND passed to a folded import; a literal printed AND handed
  to `length`.
- The interpreter as the oracle, compiled WITH wasi, under `wasmtime`, at
  `--optimize=off` AND `--optimize=size`.

## Related

- `.todo/810` (closed) -- the header-free literal layout, which this extends the
  classification of. Landed as `NoGcWasmCompiler.headerFreeLiterals`: `collectCalls`
  tallies every literal occurrence (`literalOccurrences`) beside `importCallSites`, and a
  spelling whose folded-site tally equals it is laid out header-free. A folded print
  site is one more tally to add there (`.kb/no-gc-scalar-wasm.md`, "Strings").
- `.todo/805` -- the same fold at an import call site, and the byte arithmetic
  that decides when folding a site is cheaper than keeping the wrapper. A print
  site has no wrapper to remove, so the arithmetic here is simpler: the fold is
  a win at every site.

## Artefacts

`.todo/artefacts/814-no-gc-printed-literal-fold/` -- the two benchmark programs with their
interpreter spellings, `measure-print.sh`, and `ext-patch.py`, which is the measuring hack
that produced the numbers above. Read its header before running it: it makes
`(princ <literal>)` VOID, which is wrong, and is why the numbers need re-measuring on a
correct implementation.
