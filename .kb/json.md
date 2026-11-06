# JSON (`rontolisp:json-parse` / `rontolisp:json-stringify`)

`rontolisp`-package functions (not CL) whose value mapping matches `com.inuoe.jzon`'s
defaults, so a program can switch to jzon unchanged; `JzonE2eTest` is the oracle. Docs:
`doc/*/reference/functions/rontolisp-json-{parse,stringify}.md`.

## Value mapping
- In: object -> `equal` hash table with string keys, array -> vector, `true`/`false`/`null`
  -> `t`/`nil`/the symbol `null`.
- Out: `nil` -> `false`, symbol `null` -> `null`, vector or list -> array, hash table or
  CLOS instance -> object (`%json-out-instance`, slots in definition order via
  `%class-slot-defs`). A symbol/slot-name key is down-cased unless it already holds a
  lower-case letter (jzon's `coerce-key`).
- Divergence from jzon: a `defstruct` instance serializes as an OBJECT.
- An integer, value or key, is written in decimal whatever `*print-base*` and `*print-radix*`
  say (`%json-decimal`, which is `~D`'s `%decimal-piece`, [format.md](format.md)), as jzon's
  `~D` writes it; through `%princ-piece` alone `*print-base* 16` wrote `#xFF`, which is no
  JSON, on every backend until 2026-11-06.
- Trap: stringify tests `stringp` *before* `vectorp` (a string is a vector) and
  `(eq v 'null)` / `(null v)` before the general `symbolp` branch.

## Implementation
- One Lisp source `src/main/resources/am/ik/rontolisp/eval/json.lisp`, fixed-arity
  `rontolisp::%json-*` defuns in canonical package shape;
  `PackageResolverTest.jsonLibraryFormsAreAResolverFixedPoint` pins that resolving it is a
  no-op, which is what makes splicing before and after resolution sound.
- Interpreter: `LispEvaluator` dispatchers over `JsonLibrary.portableForms()`. Compile path:
  `JsonLibrary.process(program, backend)` after `LoadInliner`/`UserMacroExpander`; a program
  without JSON is returned unchanged. `WASM_GC`/`WASM_COMPONENT` get the `%json-native-*`
  entry points (below) and leave the walk to the library pruner; every other target gets the
  walk and no `%json-native-*` defun, which call what only wasm-GC has. The one-argument
  `process` is the walk's.
- Open cleanup: both public functions are single-arity now, so the dispatcher/`#'`-wrapper
  indirection could collapse.

## The wasm-GC codec
`WasmJsonRuntimeBuilder`: `rontolisp::%json-decode (string null-symbol)` and
`rontolisp::%json-encode (value null-symbol other key-name)`, a conditional function block
(`WasmLispCompiler.emitsJsonCodec`, after the function cells) that only a program naming them
carries. `%json-native-parse` maps the decoder's i31 error code to json.lisp's message
(`%json-native-fail`); `%json-native-stringify` passes `%json-native-other` (a float, a ratio, a
symbol, a character, an instance, a vector it cannot walk: their text, `%json-out-instance`
taking `#'%json-native-out` for its fragments) and `%json-key-name`, which it calls through the
one-argument dispatcher.
- **One loop each, no recursion.** The decoder keeps values on one stack, a container opened
  under an i31 marker of the one it is in, and builds a container when it closes; an object's
  pairs are (key, placement hash, value) triples. The encoder keeps a frame per container, the
  innermost in locals. Nesting goes as deep as memory: json.lisp's walk recursed.
- **Written for an interpreting engine**, where `block`/`end` are dispatched instructions
  (Endive): a string byte is 8 instructions, the position moving first and a `br_table` sending a
  plain byte back to its loop; a `br_table` switch inside a per-byte loop re-entered its blocks
  on every byte (5 a byte in the first encoder).
- **What it builds is the walk's**: the same tables (`WasmHashTableRuntimeBuilder.emitFlatInsert`,
  a flat record sized so nothing grows -- `.kb/hash-tables.md`), vectors, integers (`_int_new`)
  and floats (`%json-float`'s operations, `(expt 10.0 n)` as fdlibm `pow`; an exponent past 10^15
  saturates, where the scale is infinite or zero either way), and each malformed input's error.
  A `\uXXXX` length check counts characters, not bytes, when a multi-byte character is among the
  four (`"\u0あ"` is unterminated, `"\u0あ0"` an invalid digit).
- **Deliberate differences, wasm-GC only**: every parsed string and the stringify result is an
  immutable `TYPE_STRING` (a character vector costs about 100 bytes a character on Endive's
  count; `.kb/string-write-runtime.md`); an object's keys are pooled within one parse, as jzon's
  default `key-fn` pools them (64 slots by length and end bytes, the placement hash cached
  beside the key).
- **Output in chunks**, each as large as all before it, joined once into the result: about 2.5
  times the text allocated, where a doubling buffer took about 4 (32 bytes a byte of JSON on
  Endive at 64 KiB, the largest single item).

Measured 2026-11-06, sluice's `json_plugin` (parse a response body, add a field, stringify it,
`--no-wasi`) on Endive 1.2.0's interpreter, an object of `{"id":N,"name":"item-N"}` items, one
exchange, instructions from an `ExecutionListener`, bytes as sluice's `PluginHeap` charges them
(`.todo/artefacts/i69-proxy-wasm-instructions/`):

| | json.lisp's walk | the codec |
| --- | ---: | ---: |
| 8 KiB: instructions a byte | 1,518 | 109 (`--optimize=size`), 96 (`--optimize`) |
| 8 KiB: bytes charged a byte | 1,130 | 83 |
| module, `--optimize=size` | 72,615 | 70,585 |
| largest body at sluice's defaults (16 MiB, a 1 s call) | 8 KiB | 192 KiB (224 KiB: memory) |
| 64 KiB / 256 KiB / 1 MiB, limits lifted | 3.4 s / 14.1 s / - | 155 ms / 539 ms / 2.17 s |

At `--optimize=size` the codec is 76 of the 109, the host copies of `get-buffer` and `set-buffer`
the other 30 (16 and 14; 9 each where the level allows them eight bytes a round,
`.kb/wasm-gc-strings.md`). wasmtime 49: 40 rounds over 8,491 bytes 61.5 M fuel with 21.5 M of
setup, the walk 415 M.

**1 MiB within 16 MiB cannot be reached by any parse that builds the tree**: at 1 MiB 75.2 MB
is charged, 71.7 bytes a byte, of which the tree is about 46 -- an item's table 1,128 bytes
(cell, header, record, two-slot buckets and order, two six-slot entries) and its value string
320 for 32 bytes of JSON -- the input string 8, the output 16 (the result and its chunks). The
1 s call holds about 450 KiB. Past that it takes an operation that builds no tree (adding a field
is a splice), or tables of another representation.

## Portability rules inside json.lisp
- Only ASCII structural characters go through `char-code`; non-ASCII copied by `subseq`.
- `\uXXXX` emits one CHARACTER per code point (`%json-encode-char` -> `code-char`,
  surrogate pairs combined first) — `.kb/characters-code-points.md`.
- Integers to 18 digits parse exactly everywhere, wider as float (`.kb/wasm-bignum.md`);
  float output byte-identical on all four backends (`.kb/format.md`).
- No `read-from-string`, so compiled output does not pull in the runtime reader.

## Neighbours
- Prelude defuns (`LispPreludeLibrary`): `rontolisp:plist-hash-table` / `hash-table-plist`,
  `alist-hash-table` / `hash-table-alist` (`alexandria` subsets; first-key-wins, `eql`),
  and the hash-table-free `alist-plist` / `plist-alist` (ORDER-PRESERVING, duplicate-keeping).
- Shipped with this feature: WASM `_equal`'s string branch calls `_string_eq` and `_hash`
  folds content bytes (`h = h*31 + byte`) — `WasmRuntimeBuilder.buildEqualBody`/`buildHashBody`;
  `eql` still compares strings by identity (`WasmEqGeneralCompiler`).

## Tests
`LispEvaluatorTest` json* cases; `JvmLispCompilerTest.compileAndRunJson*`;
`WasmLispCompilerTest.jsonOpsCompileInEveryMode`; `WasmLispCompilerIntegrationTest`
(`jsonOpsWorkInPreview1Mode`, with the codec's fuel bound, `plistHashTableAndClosObjectsInPreview1Mode`,
`equalAndHashTablesAcceptRuntimeBuiltStringKeys`); ci-spec `json-parse-and-stringify`,
`json-codec-edges` (every error, escape and number form, pooled keys, structures, deep nesting
on all four), `string-equal-content-and-hash-keys`, `alist-plist-conversions`.
Native binary embeds `json.lisp` via `resource-config.json` (typeReachable `JsonLibrary`).
