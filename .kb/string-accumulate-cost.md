# Turning many pieces into one string costs the TOTAL LENGTH

**Invariant: on ALL FOUR backends, building one string out of N pieces costs the sum of
the pieces, never the sum of the PREFIXES. The two portable ways a program has of doing it
-- `(apply #'concatenate 'string pieces)` and appending into a
`with-output-to-string` stream -- are both linear, and `uiop:read-file-string` is built out
of the second.** A cost invariant, not a semantic one; invisible at the sizes ci-spec and
the doc examples use, which is why regressions here survive.

The sibling of `.kb/string-index-cost.md`: that file is about a character INDEX, this one
about the ACCUMULATE. They were found together on the same file and are separate
mechanisms -- the index was fixed first (`.todo/690`) and the accumulate was what a real
program hit before it ever reached an index (`.todo/704`).

## The two shapes and what each used to cost
Measured 2026-09-06 (JDK 25, wasmtime 47) on a multi-megabyte character file: the
re-copying accumulate made both read-a-file-as-a-string shapes slower by orders of
magnitude on the JVM and wasm, and the fix brings both to a fraction of their old time on
every backend.

- **`uiop:read-file-string`** (`src/main/resources/am/ik/rontolisp/eval/uiop-stream.lisp`)
  read 4,096 characters at a time into `(setq acc (concatenate 'string acc chunk))`. Each
  chunk re-copied everything read so far: 3,127 chunks over the real Qwen3.5-0.8B
  `tokenizer.json` (12,807,982 bytes, 10,769,328 characters) is about 1.7e10 character
  copies, and at 134 s it was still inside `_strToCharVec` under `_toMutStr` under
  `READ-FILE-STRING`. It accumulates into a string OUTPUT STREAM now -- a `StringWriter`
  on the interpreter and the JVM, a `$str_bytes` buffer that DOUBLES on both wasm
  backends (`WasmStringStreamRuntimeBuilder`) -- so the chunks are appended, never
  re-copied. It costs nothing extra to reach for: `with-open-file` already puts the wasm
  module in EH mode, so the `with-output-to-string` inside it adds no gate.
- **`(apply #'concatenate 'string lines)`** -- the shape a caller writes by hand -- is the
  FIRST-CLASS `concatenate`, so on the compile paths it runs
  `BuiltinFunctionWrappers.concatenateWrapper`, whose string arm folded pairwise through
  `%string-concat`. 495,920 lines cost about 2.7e12 character copies; at 200 s it had not
  returned. The arm sizes the result ONCE now: `mapcar` normalizes each argument, a
  `reduce` sums the lengths, `make-string` allocates exactly that, and a second `reduce`
  carries the write offset while `replace` copies each piece in. The reduce's own
  accumulator is the offset, so nothing `setq`s a captured variable.
- **The INTERPRETER's `#'concatenate` is the Java builtin, not the wrapper**, and that one
  was already n-ary and linear -- which is why the second row barely moves there. The
  wrapper is a compile-path artifact.
- The in-repo caller of the second shape is `safetensors::%read-text`
  (`src/main/resources/am/ik/rontolisp/eval/safetensors.lisp`), which reads a
  `model.safetensors.index.json` line by line and joins it that way. It was quadratic on
  every compile path and needed no edit: the wrapper is where the cost was.

## The LIST family had the same bug with `append`
`concatenatedElements()` (the wrapper's list and vector arms) folded LEFT --
`(reduce (lambda (a x) (append a (coerce x 'list))) seqs :initial-value nil)` -- and
`append` copies its accumulator, so `(apply #'concatenate 'list lists)` was quadratic in
the total length too. It is a RIGHT fold now
(`:from-end t`, `(append (coerce x 'list) a)`), where each step copies only its own
argument's elements. The `nil` seed is still what copies the LAST argument, so the result
is a fresh list either way and the outer `(append ... nil)` the left fold needed is gone.

## What is deliberately NOT linear
- **The CALL-POSITION `(concatenate 'string a b c)`** still lowers to a nested binary
  `%string-concat` chain (`ConcatenateForms.stringChain`, `.kb/concatenate-result-families.md`).
  The argument count is written in the SOURCE there, so the chain's extra copying is
  bounded by the program text and never by the data; only the n-ary paths -- `apply` /
  `funcall` over a runtime list -- can be handed 495,920 arguments. Change this only if a
  macro is found generating a wide literal concatenate.
- **`(setq acc (concatenate 'string acc chunk))` in a LOOP is quadratic by construction on
  every backend** and no `concatenate` fix can help it: each call copies the accumulator.
  That is a shape to avoid in library Lisp, and the reason `read-file-string` no longer
  uses it. `with-output-to-string`, or `make-string` sized once and filled with `replace`,
  are the two answers to AVOIDING QUADRATIC -- both linear. They are not equally fast in
  absolute terms, though: `make-string` pays to fill its whole capacity every call, which
  makes a buffer that grows by doubling slower than `with-output-to-string` in practice
  ("The 'sized-once' alternative is not a win" below).

## The accumulate was never the whole cost of reading a file
It was not even most of it. What remained after this file's fix was the CHARACTER
`read-sequence`, which decoded and stored one code point per host read -- about 1.2 us per
character on the interpreter and wasm -- and that is fixed too
(`.kb/character-sequence-io.md`, `.todo/721`). After that second fix (2026-09-12), on the
same file:

**`uiop:read-file-string` is at parity with reading the file as bytes and decoding
once**, so the byte-reader detour in `examples/llm/llm.lisp` and
`examples/llm/checkpoint-tokenizer.lisp` is no longer a cost REQUIREMENT -- it is kept for
what it also buys: one decode of one contiguous byte array, and an immutable
`java.lang.String` on the JVM, which is the representation `rontolisp:json-parse` is fast
over (`.kb/string-index-cost.md`). `.todo/704` asked whether they could drop it; the answer
is "they may now, and it would be a wash".

Of what is left of `read-file-string`'s interpreter time, the larger share is the
`with-output-to-string` accumulate THIS file is about -- it is linear, but it is now the
larger half of reading a file into a string on every backend.

## The "sized-once" alternative is not a win: `make-string` FILLS the whole capacity
`.todo/786` asked whether the accumulate's remaining ~260 ms could be cut from three
character-touches (store into the read buffer, copy into the stream, render into the
result) to two, by replacing `with-output-to-string` with one `make-string` buffer that
DOUBLES on demand (`read-sequence buf in :start len`, answered as `(subseq buf 0 len)`).
Measured 2026-09-12 (JDK 25, wasmtime 47, Linux x64), both alternatives against the SAME
file, steady state (repeated reads in one process):

**The doubling buffer LOSES on every backend measured**, not by a small margin. The reason
is `make-string`: `expandMakeString` lowers it to `(make-array n :element-type 'character
:initial-element #\Space)` (`.kb/adjustable-arrays.md`), so every `make-string` call pays
for FILLING its whole capacity, not just what the caller goes on to write. A doubling
buffer therefore fills its buffer TWICE across its lifetime (once as spaces at allocation,
once with `replace` copying the real data over the first half) plus the growth copy itself
-- three to four touches, not two, and the growth capacities sum to roughly 2x the final
size on top of that. Isolated: a bare loop that doubles a `make-string` buffer to
megabytes (no I/O at all) costs the interpreter alone about as much as the ENTIRE baseline
`read-file-string` for a real file. `replace`
and `subseq` are not the problem (they copy only real data); the unconditional
`:initial-element` fill is.

A second alternative -- keep `with-output-to-string` but grow the READ chunk from 4,096 to
65,536 characters, cutting the call count by an order of magnitude -- helps the interpreter
on THIS file and is noise-level on the JVM and wasm (within run-to-run
variance). But `make-string`'s fill cost is paid on EVERY call regardless of the file's
real size, so a bigger fixed chunk is a tax on the common case instead: many reads of a
tiny file, steady state on the interpreter, run several times slower at the large chunk.
Sizing the chunk from the file is not portable either -- `file-length`
answers nil on both WASM backends (the comment already on `read-file-string`).

**Conclusion: `uiop:read-file-string` keeps its current shape.** Neither alternative is a
net improvement once the common (small-file) case is weighed against the one large file
the doubling shape was designed for. A real win needs a buffer allocation that skips the
CL `:initial-element` fill obligation -- an uninitialized-storage primitive `make-string`
cannot honestly be, since the language sizes the default fill from the array's own default
element (`.kb/adjustable-arrays.md`, "An array slot nobody wrote..."). Exposing one to
library Lisp (and auditing every `make-string` caller that currently relies on the fill,
e.g. anything reading uninitialized tail slots) is a separate, larger project than this
item's scope.

## Pinning
- `BuiltinFunctionWrapperCatalogTest#aWrapperNameResolvesToItsCatalogLambda`: the catalog
  lambda every compile path injects for `#'concatenate` sizes one `make-string` and
  `replace`s into it, folds the list arm `:from-end t`, and spells no `%string-concat`
  (the old pairwise fold's only spelling). The shape, not a clock.
- ci-spec `string-accumulate-is-not-quadratic` (all four backends): CORRECTNESS only --
  4,096 pieces of 64 characters in ONE `apply #'concatenate 'string` build what 256
  sixteen-piece calls build, the same over a `with-output-to-string` accumulator, then
  `uiop:read-file-string` over a 4,500-character file it writes itself, across the
  4,096-character read boundary.
- **The wall-clock pins were removed 2026-10-23**: the ci-spec `CONCAT-FLAT` /
  `STREAM-FLAT` tokens and the JVM / WASM
  `#aNaryConcatenateCostsTheTotalLengthAndNotTheSumOfThePrefixes` bounded the one-call half
  by `500 + 6 *` the chunked half in ms, a machine-speed verdict (the same family failed
  CI 22 times, `.kb/character-sequence-io.md`). The doubling of the string-output buffer
  has no deterministic pin: a WASM fuel budget cannot see it (an `array.copy` costs the
  same fuel whatever its length), and the JVM / interpreter use the JDK's `StringWriter`.
  `WasmStringStreamArenaE2eTest#aStringOutputStreamCostsTheArenaNothingPerWrite` pins the
  per-write half on WASM.
- Correctness of the rewritten wrapper arms rides the existing
  `#compileAndRunConcatenateAsFunctionValue` /
  `#concatenateBuildsListAndVectorResultTypes` family and the mutable-result pins in
  `.kb/string-write-runtime.md`.

Related: `.kb/string-index-cost.md`, `.kb/concatenate-result-families.md`,
`.kb/string-write-runtime.md`, `.kb/read-load-streams.md`.
