# `replace` / `fill` / `map-into` are inlined at every call site

Difficulty: Medium

Three sequence operators still expand INLINE per call site, where their neighbours
(`subseq`, `coerce`, `search`, `concatenate`) are shared spliced defuns. Measured with
the probe below on `--optimize=size`, marginal cost of one extra call site:

| operator | bytes per site |
| --- | ---: |
| `replace` | 3,806 |
| `map-into` | 1,949 |
| `fill` | 1,415 |
| `sort` | 496 |
| `position` | 451 |
| `reduce` | 105 |
| `search` | 20 |
| `concatenate` | 19 |
| `subseq` | 13 |

The probe (compile with 1 site and with 4, divide the difference by 3):

```lisp
(defvar *a* (make-array 100 :element-type '(unsigned-byte 8)))
(defvar *b* (make-array 100 :element-type '(unsigned-byte 8)))
(defun f (i)
  (replace *a* *b* :start1 i :start2 1 :end2 9)   ; x1, then x4
  i)
(print (f 3))
```

## Why it is worth doing

`CHIPZ::UPDATE-WINDOW` is the single largest function in the zlib size-report artifact
at **18,149 B -- 9.5% of the whole module**. Its source (`inflate.lisp:3-32`) is thirty
lines of Lisp whose body is four `replace` calls. chipz has ~8 live `replace` sites
after the bzip2 pruning, so a shared runtime is worth roughly **-25 KB on the zlib
rows** (~8 x 3.8 KB, less one ~4 KB spliced defun). `fill` and `map-into` are the same
shape and ride along.

The pattern is already established twice, with the reasoning and the injection-gate
mechanics written down: `.kb/subseq-runtime.md` (`%subseq-runtime`, 2,316 -> 11 bytes a
site) and `.kb/seq-conversion-runtime.md` (`%seq-to-list`/`-string`/`-vector`). Follow
those: one spliced defun, injected by the BACKEND beside the builtin wrappers, gated on
a whole-program scan, with the JVM's array-gate asymmetry accounted for.

Watch the specialised-array paths: `replace` over two `(unsigned-byte 8)` packed vectors
must keep its packed store (`.kb/packed-integer-vectors.md`), so the shared defun either
dispatches on the element kind at run time or the packed case keeps a narrow inline
form. Measure both -- a runtime dispatch that costs the hot inflate loop is not a trade
worth making for bytes.

## Also in scope: the spread dispatcher

`_dispatch_spread` is **12,156 B** of the same artifact (the eight funcall dispatch
ladders together are 20,456 B). chipz's only `apply` is one literal-target site,
`decompress.lisp:31` `(apply #'decompress output state input keys)`, and a literal
target does NOT force the runtime in isolation -- probes at `--optimize=size`:

- `(apply #'f 1 *k*)` to a plain `&rest` defun: 4,873 B, no dispatcher (the same program
  calling `f` directly is 4,890 B, i.e. `apply` is free there);
- `(apply #'g inst 5 *k*)` to a `defgeneric` with a `&rest` method: 8,801 B, no
  dispatcher.

So something else in the chipz + prelude tree turns `needsApplyRuntime` on. Find it
(`RuntimeNameProducers`, the `usesApplyRuntime` gate in `WasmLispCompiler`, and the
`--optimize` dispatch-gate story in `.kb/optimize-dead-code-elimination.md`); if it is
another over-approximating scan, this is worth up to another **-15 KB**.

## Deliverable

Measured reductions in the `zlib` rows of `size-report/results/wasm-flags.md` and the
Worker rows of `results/cloudflare-workers.md`, no change in what any row checks,
`./mvnw test` + native `CiSpecE2eTest` green, byte-identical output for programs that
use none of the touched operators, and the per-site numbers above re-measured after the
change so the table can be updated in the `.kb` file the runtime lands in.
