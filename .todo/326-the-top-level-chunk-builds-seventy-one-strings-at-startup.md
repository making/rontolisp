# The top-level chunk builds seventy-one strings at startup

Difficulty: Medium

`_toplevel_chunk_582` is **7,020 B, the single largest function in the zlib
`--optimize=size` artifact (5.1%)**, for a program whose own top level is three forms:
one `ql:quickload`, one `let*` reading stdin, one `write-sequence`. The rest is what
quickloading chipz splices there.

## Measured

7,020 body bytes, 159 `call` instructions to 27 distinct targets:

| target | calls |
| --- | ---: |
| `FUNC_STR_BUILD` | 71 |
| `FUNC_T_SYM` | 23 |
| `FUNC_CHARVEC_TO_STR` | 9 |
| `FUNC_STR_CHAR_AT` | 7 |
| `FUNC_STR_CHAR_COUNT` | 6 |
| everything else (`_int_new`, `_arr_get`, `_as_f64`, the ratio helpers, ...) | <= 4 each |

**Seventy-one `_str_build` calls** -- the top level spends most of its bytes constructing
string values at run time. A string literal reachable at compile time is already a
constant in the data section addressed by an offset (`.kb/wasm-gc-strings.md`), so each
of these is a build the emitter decided it could not fold: symbol names being interned, a
`defparameter` whose value is assembled, a package/registry entry, or a literal reaching
a site that wants a fresh heap string.

## What to find out first

This item is an audit before it is a fix. Read the chunk back (the
`-Drontolisp.wasm.debug-func-sizes=1` name plus a `wasm-tools print` of that function is
enough) and answer:

- Which of the 71 builds are the SAME string built more than once, and why the string
  table's deduplication does not reach them.
- How many are symbol names that only exist because a name registry row, an accessor
  spelling or a `PRINT-OBJECT` label was interned -- those overlap with the report-floor
  work, so measure before both are changed or the second one will look free.
- Whether the builds are constant-foldable at compile time. `.kb/pure-builtin-fold.md`
  owns the rule for what may be folded over literal arguments and what is deliberately
  excluded; a fresh MUTABLE string is exactly the kind of value that must not be shared,
  so the answer may be "no" for a subset and the audit should say which.

`.kb/library-defun-pruning.md` and the toplevel chunker (`WasmToplevelChunkingTest`) are
the other two files this touches: the chunk is one function because the chunker decided
it fits, and a smaller top level may change that split.

## Deliverable

Either a measured reduction in the `zlib` rows of `size-report/results/wasm-flags.md`
with the row's check still gunzipping byte for byte, or -- if the builds turn out to be
irreducible -- the finding written into `.kb` with the numbers, so the next visitor does
not re-measure the same 7 KB. `./mvnw test` + native `CiSpecE2eTest` green either way.
