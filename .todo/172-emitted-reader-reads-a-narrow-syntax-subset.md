# The emitted runtime reader reads a narrow syntax subset, and misreads the rest silently

The reader compiled into a JVM class / WASM module understands exactly six shapes:
`(` (dotted pairs included), `'`, `#'`, `"`, `)` and "atom" (symbol / integer /
bignum / double / nil / t). **Every other `#` dispatch form the frontend reader
handles falls through to the atom path and is silently misread** -- it does not
signal, it returns wrong data:

| `(read-from-string ...)` | interpreter | JVM / WASM P1 / `--component` |
| --- | --- | --- |
| `"#S(RP :X 1 :Y 2)"` | the instance | the symbol `#S`, then `(RP :X 1 :Y 2)` is left for the next read |
| `"#(1 2)"` | the vector | the symbol `#`, then `(1 2)` |
| `"#\\a"` | the character | the symbol `#\A` |
| `"1/3"` | the ratio | the symbol `1/3` |
| `"#x10"` | 16 | the symbol `#X10` |

Measured 2026-07-25 on the native binary at `48d68929`. This is a
**wrong-data hole of exactly the kind `.todo/171` phase 3 closed on the frontend**
(where `#S(...)` used to read as `#S` + a list); it survives at run time because the
two readers were never brought to parity.

## Decision that produced this item

`.todo/171` phase 4 planned to teach the emitted readers `#S(` alone. Measuring
first showed why that would be wrong: a runtime reader that reads `#S(P :X 1)` but
not `#\a` or `#(1 2)` is harder to explain, and harder to keep honest in the docs,
than one that reads neither. The repo owner chose **full parity** (2026-07-25) --
close the whole class, not the one form todo-171 happened to need. The interpreter
half of todo-171 phase 4 (runtime `read`/`read-from-string` folding `#S`) already
landed; nothing here blocks it.

## Scope

Bring `JvmReadRuntimeBuilder` and `WasmReadRuntimeBuilder` up to the frontend
reader for the forms a runtime `read` can plausibly meet, ranked by how often a
program prints them (so a `prin1` -> `read` round trip is closed, which is the
real user story):

1. `#S(NAME :SLOT v ...)` -- structure instances. The layout table each backend
   needs is ALREADY in the artifact: WASM bakes every layout into the
   `WasmInstanceLayouts` linear-memory blob (bake-ALL), and the JVM interns
   `String[]` layouts per referenced tag in `JvmLispCompiler.LayoutPool` -- but
   only on demand, so the JVM needs an all-layouts table added (gated on "the
   reader is emitted AND the program may hold instances", so no artifact that does
   not read grows). Reading is the exact reverse of the printer's fixed loop, so
   write it beside `_instToString` / `emitPrintInstance` and keep them symmetric.
   Error set must match `StructLiteralFolder`'s messages.
2. `#(...)` vectors and `#nA(...)` arrays.
3. `#\a` / `#\Space` character literals.
4. Ratios (`1/3`) and radix integers (`#x`/`#o`/`#b`).
5. `#f(`/`#d(` packed float arrays, `#*` bit vectors, `#n=`/`#n#` labels -- decide
   explicitly whether these are in or out, and say so in the docs either way.

`#.` read-eval and `#+`/`#-` conditionals stay OUT (they need an evaluator and a
feature set at run time); state that as a permanent limit rather than a gap.

**Whatever is left out must SIGNAL, not misread.** The single most valuable part of
this item is the last one: an unknown `#` dispatch in the emitted reader has to
become a clear runtime error, so the remaining gap stops producing wrong data. Do
that first if the parity work has to be split across sessions -- but note it is a
behavior change (today `#foo` reads as the symbol `#FOO`), so sweep the ci-spec
corpus and the loadable libraries for a program that reads a `#`-prefixed token.

## Verification bar

Every form this item adds must read IDENTICALLY on all four backends -- interpreter,
JVM, WASM Preview 1 and `--component` -- with a ci-spec case per group, since the
whole point is that they disagree today. `(read-from-string (prin1-to-string x))`
should round-trip for each supported value kind; make that the shape of the ci-spec
cases. Then the native-binary `CiSpecE2eTest`, not just `./mvnw test`.

Update `.kb/read-load-streams.md` (whose opening paragraph currently records the
subset and this gap), `.kb/instance-syntax.md`, and
`doc/{en,ja}/reference/functions/{read,read-from-string}.md` +
`reference/special-forms/defstruct.md` + `guides/missing-features.md`, all of which
now spell the limitation out and would become wrong.
