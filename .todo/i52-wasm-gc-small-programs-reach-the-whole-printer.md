# i52. A small wasm-GC program reaches the whole printer and every generic sequence function

Difficulty: High

What is left of a Proxy-Wasm filter once its SDK is lean (i51) is mostly runtime it never runs: `http_body` with the
SDK levers applied is 62,404 bytes, for a filter that removes a header, reads a body, and replaces it. Numbers and tools:
`.todo/artefacts/i51-proxy-wasm-filter-size/README.md`. The same costs fall on any small `--no-wasi` module, so measure
`size-report/programs/` too. The target number is raw and gzip of the whole module (`.kb/size-measurement.md`).

Measured again 2026-11-05 on `define-filter` (`.kb/proxy-wasm-sdk.md`), raw / gzip at `--no-wasi --optimize=size`. `http_body` is
6,086 / 3,165 under `(:on-failure :trap)` and 14,516 / 6,242 under the default handler. With `get-buffer`, `search` and `~a` of an
integer it is 8,469 / 4,275, and holds no `%PC-*`, `%POS-WALK` or `FUNC_PRINT_VAL`: a literal control without `~s` reaches no
`prin1` (`.kb/pretty-printer.md`). What stands out now is the `funcall`. That filter with a `funcall` of a function in a `defvar`
is 40,822 / 17,494, and 56,380 / 22,145 under the handler, holding `_dispatch_1`, `_dispatch_2`, `FUNC_PRINC_VAL` 2,884,
`%REPLACE-RUNTIME-ARRAY` 2,085, `CONCATENATE` 1,990 and `%SUBSEQ-RUNTIME` 1,545. Start there. The sizes below are older.

## What is there

From `funcsizes.py` on that module (about 55,600 bytes of code). `%PC-*` and the `FUNC_PRINT*` sizes are current; the other
sizes, and the removal measurements, are from just before `45f0b386a`, which added about 3KB, and rank causes:

- **`~a` of a value of unknown type** is 14,860 raw and 4,739 gzip, the largest single use. `%PC-UNQUALIFIED` 4,191,
  `%PC-WALK` 4,007, `FUNC_PRINT_VAL` 3,333, `FUNC_PRINC_VAL` 3,290, `FUNC_SYM_ESC_GC` 923, `FUNC_DEC_FMT` 913, and the
  float decimal path. The value is an integer here, and `~a` of an `:s32` parameter costs 5,405 in all.
- **`prin1` in a program that only uses `princ`.** `%pc-walk` takes `escape` as an argument, so `%PC-UNQUALIFIED`,
  `FUNC_PRINT_VAL` and `FUNC_SYM_ESC_GC` (about 8.4KB) stay for a flag that is never true. No caller passes `t` here.
- **Both printer walks live at once.** The unchanged SDK's `http_body` holds `%POS-WALK` (6,499) and `%PC-WALK` (4,322).
  The comment above `LispPreludeLibrary`'s `PRINT_CASED_WALK_INTERNAL` entry says they are never both live, but the path is
  `%POS-WALK` -> `%PRINT-OBJECT-LEAF` -> `%PRINT-CASED` -> `%PC-WALK`. The comment is wrong for this program; find out
  whether the leaf needs the whole walk.
- **The generic sequence family.** `CONCATENATE` 1,993, `%REPLACE-RUNTIME-ARRAY` 2,085, `%SUBSEQ-RUNTIME` 1,545,
  `%REPLACE-RUNTIME` 1,163, `%SEQ-TO-STRING` 1,061, `%SEQ-TO-LIST` 858, `%SEQ-TO-VECTOR` 806, `%SEQ-INT-VECTOR` 537,
  `%CHECK-SEQUENCE-RUNTIME` 402, `%SEQ-FLOAT-VECTOR` 357. Each handles every sequence type for callers that pass strings.
- **Dispatcher arms for what nothing calls first-class.** `_dispatch_1` holds `CONCATENATE`, `STRING`, `VECTOR`, `IDENTITY`,
  `%SEQ-STRING` in a filter that names none of them with `#'`. `q7-dead-hash-ref` shows one way in: `#'string` in an
  uncalled function leaves the arm. Why these five are there is **not yet known**: start with that.
- **`handler-case` around a `funcall`** is 10,923, against 2,753 without the `funcall` and 1,070 without the handler. The
  dispatcher gains `FUNC_TILDE`, `FUNC_PRIN1_TO_STR`, `FUNC_PRINC_TO_STR`: a condition's report function as a first-class
  value is the likely reason, and it is not confirmed.

## What was measured about the shape of the fix

Deleting the SDK defuns the filter never calls takes the module from 41,316 to 22,232, but only once its `~a` and `search`
are gone too. On the module that keeps them it saves 427 bytes. So several roots hold the same helpers, and no one fix shows
much alone. Measure each against the module with the others removed as well as against the real one, or a working fix
reads as a failure.

## Where to start

1. Trace why each of the five arms is in `_dispatch_1`, and whether an arm for a reference in dead code can go. Dispatchers
   are built before the shake, so the shake cannot do this today.
2. Specialize `%pc-walk` on a constant `escape`.
3. Narrow `~a` from a declared or inferred integer or string to the small path `p07-format-a` already takes.

## Done when

A module with `~a`, `search` and a `funcall` over the `http_body` shape measures well below 22,232 plus their own small
costs, the backends still agree on what they print (`.kb/running-backends.md`), and the findings are written into
`.kb/optimize-dead-code-elimination.md`.
