# String indexing is unchecked on all three compile backends: an out-of-range read returns garbage and an out-of-range write silently grows the string

Found while closing `.todo/184` (adversarial review of that change; the change
itself touched only the interpreter's `charRef`). The interpreter is the ONLY
backend that range-checks a string index. Every artifact anyone ships answers an
off-by-one with a plausible-looking wrong character, or corrupts the string.

## Measured (2026-07-26, native binary + exec jar + wasmtime 46.0.1)

| program | interpreter | JVM | wasm Preview 1 | `--component` |
| --- | --- | --- | --- | --- |
| `(char "abc" 3)` | signals `CHAR: index 3 out of bounds for string of length 3` | prints `#\"`, exit 0 | prints `#\Nul`, exit 0 | prints `#\Nul`, exit 0 |
| `(char "abc" -1)` | signals | prints `#\"` | prints `#\a` | prints `#\a` |
| `(char "abcdefghij" 40)` | signals | raw `java.lang.IndexOutOfBoundsException` | `#\Nul` | `#\Nul` |
| `(let ((s (copy-seq "abc"))) (setf (char s 3) #\z) (print s))` | signals | `StringIndexOutOfBoundsException` | prints `"abcz"` | prints `"abcz"` |

The JVM's `#\"` is the giveaway: `JvmCharCompiler.compileChar` walks
`String.offsetByCodePoints(1, index)` over a QUOTE-FRAMED Java string, so index
`length` reads the closing quote and index `-1` the opening one. Both wasm
backends decode past the end of the UTF-8 byte data
(`WasmStringRuntimeBuilder._str_char_at`) and answer whatever is there.

The write case is the worst of the four cells: on both wasm backends a
`(setf (char s i) c)` past the end APPENDS instead of signalling, so a loop with
a bad bound quietly grows a string rather than stopping.

## The sibling divergence this leaves behind

`.todo/184` aligned `aref` on a string's INACTIVE slots (between the fill pointer
and the capacity) across all four backends -- CL says `aref` ignores fill
pointers, all three compile backends already served the slot, and the interpreter
now does too (`.kb/adjustable-arrays.md`, pinned by the
`string-fill-pointer-inactive-slots` ci-spec case). `char` / `schar` on the same
slots could NOT be pinned, because the three compile backends disagree with each
other for exactly the reason above:

```lisp
(let ((s (make-array 6 :element-type 'character :fill-pointer 3 :initial-element #\.)))
  (setf (char s 0) #\a) (setf (char s 1) #\b) (setf (char s 2) #\c)
  (print (list (char s 4) (schar s 5))))
;; interpreter -> (#\. #\.)   JVM -> throws   wasm P1 / component -> (#\Nul #\Nul)
```

Fixing the bounds check is therefore what unblocks a four-backend pin on
`char`/`schar` too, and the ci-spec case should be widened in the same pass.

## What to do

Emit the same check the interpreter has, per backend, on `char` / `schar` /
`aref` (rank-1 string) read AND write:

- **JVM**: `JvmCharCompiler.compileChar`, `JvmArrayRuntimeBuilder._aref1`'s
  string branch, and the `%schar-set` / `storeStringChar` write path. The bound
  is the CAPACITY, not the fill pointer (same rule the interpreter now follows).
- **WASM, both backends**: `WasmStringRuntimeBuilder` `_str_char_at` /
  `_str_char_byte_offset` and the char-vec store. `_str_char_count` already walks
  the data, so the length is available.
- The error must be the same TEXT on all four -- `CHAR: index N out of bounds for
  string of length L` -- or the ci-spec cannot pin it. Signalling makes it
  catchable by `handler-case`, which is the point (`.kb/wasm-condition-catching.md`
  for what that costs on the wasm side: an EH-mode program).

Do this together with, or after, `.todo/185` (`(char s i)` is O(i) on the compile
paths): both edit the same three accessors, and the bounds check needs the same
length the fast path would compute.

Cheap first step if the full fix is too wide: make the two WASM backends TRAP
rather than return `#\Nul` / append. A trap is a bad error message but it is not
silent corruption, and it costs one comparison.
