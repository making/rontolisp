# A program that reports no condition still carries the report renderer

Difficulty: High

zlib prints nothing, formats nothing and signals nothing: it reads stdin, inflates and
writes bytes. Its `--optimize=size` artifact still carries the whole value printer and
the condition-report renderer, about **7.0 KB of the 137,430**, plus the accessor-name
strings those paths would print.

## Measured

| function | bytes |
| --- | ---: |
| `FUNC_PRINT_VAL` | 1,827 |
| `FUNC_PRINC_VAL` | 1,743 |
| `%NO-APPLICABLE-METHOD` | 1,244 |
| `%CONDITION-REPORT-STR` | 1,105 |
| `%FORMAT-CONDITION` | 467 |
| `%PRINT-OBJECT--m0` | 487 |
| `%PRINT-OBJECT-STR` | 165 |
| **total** | **7,038 (5.1%)** |

The call graph of the emitted module says how they stay alive, and it is a narrow chain,
not a broad one:

- `FUNC_PRINT_VAL` has exactly ONE caller: `FUNC_PRIN1_TO_STR`.
- `FUNC_PRINC_VAL` has two: `FUNC_PRINC_TO_STR` and `FUNC_STRING_CONCAT`.
- `%NO-APPLICABLE-METHOD` has THIRTY: every CLOS accessor dispatcher chipz's
  `define-condition`s and `defstruct`s generate (`CHIPZ::EXPECTED-CHECKSUM`,
  `%setf-CHIPZ::FLAGS`, ... and `PRINT-OBJECT`).

So the renderer is reachable because the accessor dispatchers' last resort and the
condition reporter build their message with `princ-to-string`/`%string-concat`, and those
reach the printer. The data section carries the matching literals -- `"PRINT-OBJECT on "`,
`"CHIPZ::EXPECTED-CHECKSUM on "`, `"CHIPZ::ACTUAL-CHECKSUM on "` -- roughly 1.2 KB of the
9,199 B data section in one segment.

## Why this is not just "make the shaker smarter"

The edge is real: if a slot accessor IS called with no applicable method, that path runs.
Nothing here is unreachable in the reachability sense, so the answer has to change what
the code SAYS, not what the shaker believes. Candidate directions, in rough order of how
much they change:

- **A cheaper last resort.** `%NO-APPLICABLE-METHOD` renders the class and the operator
  into prose. A signal that carries the two as VALUES and renders only when something
  reports it would cut the accessor dispatchers' tail from the printer entirely -- the
  same trade the two earlier passes over this floor made (their rows in
  `.todo/.history.md` name the commits that closed them).
- **Report lazily.** `%CONDITION-REPORT-STR` is only needed by whoever prints a condition.
  A program with no handler that prints and no top-level report has no caller for it; the
  question is whether the uncaught-condition path counts as one, and on which backend.
- **Split the printer.** `FUNC_PRINT_VAL` renders every value type there is. What the
  report path needs is a small subset (a symbol, a string, an integer). A narrow renderer
  for that path would leave the full printer for programs that actually print.

Read `.kb/error-handling.md` and the condition-floor history first; this is the third
visit to that floor and the earlier two recorded what they deliberately left.

## Deliverable

Measured reductions in the `zlib` rows of `size-report/results/wasm-flags.md` with the
row's check still gunzipping byte for byte, unchanged reported text for a program that
DOES report (the point is what is carried, never what is printed) pinned across all four
backends, `./mvnw test` + native `CiSpecE2eTest` green, and the reason the remaining
floor is a floor written into `.kb`.
