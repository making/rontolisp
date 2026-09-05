# 704. Accumulating a file into a string is quadratic in the file

Difficulty: Medium

Found 2026-09-05 while closing `.todo/690`, on the JVM class output over the real
Qwen3.5-0.8B `tokenizer.json` (12,807,982 bytes, 495,920 lines). BOTH ways a program
has of turning a file into one string re-copy the accumulator per chunk, so neither
reaches `rontolisp:json-parse` at all -- 690's subject was never the first thing that
run hit:

- `uiop:read-file-string` (`src/main/resources/am/ik/rontolisp/eval/uiop-stream.lisp`)
  reads 4,096 characters at a time and does
  `(setq acc (concatenate 'string acc (subseq buf 0 n)))`. 3,127 chunks over 10.77M
  characters is about 1.7e10 characters copied. Sampled at 134 s it was inside
  `_strToCharVec` under `_toMutStr` under `READ-FILE-STRING`; it had not returned.
- The `read-text-file` shape a caller writes by hand -- a `read-line` loop then
  `(apply #'concatenate 'string lines)` -- folds PAIRWISE through the builtin's
  two-argument wrapper (`_invoke_2` -> `CONCATENATE` -> `_strv`), so 495,920 lines cost
  about 2.7e12 character copies. Sampled at 200 s, still in `READ-TEXT-FILE`.

Both are the ACCUMULATE, a different mechanism from 690's character INDEX (which is
fixed: the same file parses in 525 ms once the text is in hand, `.kb/string-index-cost.md`).
The one path that is already linear is `read-file-bytes` + `rontolisp:octets-to-string`
(169 ms + 71 ms on the same file), which is what the 690 measurements used.

## Do

1. Decide whether the fix is a growable accumulator (`vector-push-extend` into an
   adjustable character vector, or `with-output-to-string`) inside
   `uiop:read-file-string`, or a linear n-ary `concatenate` that sizes the result once
   and copies each argument in -- the second also fixes every hand-written
   `apply #'concatenate 'string`, which is the shape a caller reaches for. Prefer the
   one that fixes both.
2. `concatenate` as a first-class value goes through `BuiltinFunctionWrappers`; check
   whether the pairwise fold is in the wrapper or in `apply`'s lowering, because that
   decides where an n-ary path has to live.
3. Pin it as a ratio the way `.kb/string-index-cost.md`'s cases are pinned: reading one
   N-character file against reading the same characters as many small files, on all four
   backends (the accumulate is portable Lisp, so all four share it).
4. Then `examples/llama2/llama2.lisp` can drop its own `read-file-bytes`-plus-decode
   detour for `uiop:read-file-string`, if the numbers say so.
