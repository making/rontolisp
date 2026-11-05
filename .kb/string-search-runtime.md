# `search` over two strings takes no element loop on wasm-GC

**Invariant: `search` over two strings, with bounds or without and with no `:test`, `:test-not`,
`:key` or `:from-end`, allocates nothing on wasm-GC (Preview 1 and the component), in either string
representation.** A cost invariant: the answers are the prelude loop's on all four backends.

## The mechanics
The prelude's `search` checks its operands and bounds as before, then asks
`(%string-search seq1 seq2 start1 end1 start2 end2)` (`LispNames.STRING_SEARCH`): an index, -1 for no
match, or nil for "not decided", which runs the loop. Same shape as `%replace-bulk`
(`.kb/sequence-op-runtimes.md`).

- **wasm-GC**: one call of `_str_search` (`FUNC_STR_SEARCH`,
  `WasmStringRuntimeBuilder.buildStrSearchBody`), six eqrefs in, one eqref out, so no new type.
  Shaken from a module that never searches. An operand that is no string answers nil.
- **Two `TYPE_STRING`s compare as bytes.** UTF-8 spells a code point one way and no lead byte is a
  continuation byte, so a byte match starting at the needle's lead byte is a character match. The
  answer is converted from bytes only when there is one: `start2` plus the lead bytes before it.
- **A character vector on either side compares code points through `_str_char_ref`.** Rendering it
  would be one scan's cost per bounded call, quadratic in a tokenizer's loop over a buffer
  (`.kb/string-index-cost.md`).
- **The interpreter and the JVM answer constant nil.** `SequenceScanFast` has already served the
  interpreter's call (`.kb/seq-coerce-runtime.md`), and a JIT makes little of the loop's boxes.
  `--no-gc` refuses `search` for its `&key`.
- Not served, so still the loop: `:test`, `:test-not`, `:key`, `:from-end`, a list or general vector
  on either side. `mismatch` keeps the same loop with the same boxes.

## What it answered
sluice-client interprets a plugin with Endive and counts what a call allocates against `max-memory`
(16MB by default) with no credit for garbage; `call-timeout` is 1s by default. Measured 2026-11-05 on
a JVM warmed by a few exchanges, a 64KiB body of `x` through a filter holding it, best of 5,
instructions from an `ExecutionListener` over one exchange:

| The filter | Instructions | Per byte | Time |
| --- | ---: | ---: | ---: |
| `get-buffer-octets` alone, `--optimize` | 1.06 M | 16 | 26ms |
| `get-buffer` alone, `--optimize` | 2.31 M | 35 | 47ms |
| `get-buffer` + `search`, the loop, `--optimize` | 52.7 M | 804 | 1.48s |
| The same, `--optimize=size` | 84.3 M | 1286 | 2.84s |
| `get-buffer` + `search`, this arm, either level | 3.23 M | 49 | 65-103ms |
| `get-buffer` + `(char= (char a i) (char b (+ p i)))` in a `do`, `--optimize` | 35.0 M | 534 | 1.06-1.30s |
| `get-buffer-octets` + the typed loop below, `--optimize` | 9.38 M | 143 | 218-249ms |
| The same, `--optimize=size` | 28.5 M | 434 | 1.00s |

- **The structs were the loop's characters**: `(elt seq i)` lands in a `let`, so each position boxes
  two `TYPE_CHAR`s, the 2 per byte measured, and `eql` is a generic call on them. The arm's own scan
  is 14 instructions per byte and allocates nothing. A body with the match at its end is the same
  plus the count of the lead bytes.
- **`http_body` at the default limits**: 64KiB passes at 16MB and 1s on both levels, matching or not.
  `call-timeout` is what ends it, not `max-memory`: 1,000,000 bytes fit 16MB and take 1.3s warm, and
  262,144 bytes (444ms warm) lose only the first call on a cold JVM.
- **The typed loop's 2us per byte is instructions, not the host's overhead.** Endive takes about
  25ns an instruction, and turning sluice's listener off changed the times within noise (287 vs 218ms
  on the loop, 58-66 vs 65-103ms on `http_body`). The loop is 127 instructions per byte: the wasm
  backend reads no `(safety 0)`, and `(declare (type fixnum i))` leaves `i` a dual-representation local
  (`.kb/wasm-unboxed-locals.md`). So each read of `i` tests the shadow against the sentinel, `aref`
  keeps its `ref.test` and index range test with the generic arm behind them, and `(+ i 1)` its
  overflow test. At `--optimize=size` fusion is off too (`.kb/optimize-dead-code-elimination.md`).
  A `search` now costs a tenth of what that loop costs, so no filter needs the loop for this.

```lisp
(defun %contains-secret (octets)
  (declare (optimize (speed 3) (safety 0))
           (type (simple-array (unsigned-byte 8) (*)) octets))
  (let ((n (- (length octets) 5)) (i 0))
    (declare (type fixnum n i))
    (loop while (< i n)
          do (when (and (= (aref octets i) 115) ...) (return t))
             (setq i (+ i 1)))))
```

## Costs
Bytes, the same `search` with and without the arm: wasm +378 raw at both levels, JVM +370 for an arm
that never answers (with `(< found 0)` in place of the `eql` the loop needs anyway, +599). A module
that never searches is unchanged. `http_body` at `--optimize=size`: 58,948 -> 59,633 raw, 22,867 ->
23,065 gzip.

## Pinning tests
- ci-spec `search-over-strings-answers-characters-in-either-representation` (all four backends, its
  rows checked against sbcl): every pair of representations, multi-byte characters so a byte offset
  and a character index differ, each bound, an empty needle, a near miss sharing a lead byte, views,
  a fill pointer, a first-class `search`, a declined keyword.
- `WasmLispCompilerIntegrationTest.searchAndMismatchAnswerTheSameAsTheInterpretersNativeArm`: fuel
  over 65,536 characters, 23.1 M to build them, 23.9 M with the immutable search, 38.9 M with the
  character vector, 65.4 M through the loop. No pin counts allocations: the loop is the only thing
  that boxes here and fuel tells it apart.
