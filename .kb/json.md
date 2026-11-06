# JSON (`rontolisp:json-parse` / `rontolisp:json-stringify` / `json-span` / `json-value`)

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
- **What it builds is the walk's**: the same tables -- an object of at most eight keys a small
  record of exactly its pairs (a repeated key found by its placement hash first), a larger one a
  flat record sized so nothing grows (`WasmHashTableRuntimeBuilder.emitFlatInsert`;
  `.kb/hash-tables.md`, "Representation") --, vectors, integers (`_int_new`)
  and floats (`%json-float`'s operations, `(expt 10.0 n)` as fdlibm `pow`; an exponent past 10^15
  saturates, where the scale is infinite or zero either way), and each malformed input's error.
  A `\uXXXX` length check counts characters, not bytes, when a multi-byte character is among the
  four (`"\u0あ"` is unterminated, `"\u0あ0"` an invalid digit). The encoder walks a small
  table's record in place (a `PAIRS` frame) and every object frame branches to one key writer:
  a copy of it at each of the four places was 2.4 KB more.
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

| | json.lisp's walk | the codec | small tables |
| --- | ---: | ---: | ---: |
| 8 KiB: instructions a byte | 1,518 | 109 (`--optimize=size`), 96 (`--optimize`) | 107 |
| 8 KiB: bytes charged a byte | 1,130 | 83 | 58 |
| 1 MiB: bytes charged a byte | - | 71.7 | 49.9 |
| module, `--optimize=size` | 72,615 | 70,585 | 71,049 |
| largest body at sluice's defaults (16 MiB, a 1 s call) | 8 KiB | 192 KiB (224 KiB: memory) | 256 KiB (272 KiB: memory) |
| 256 KiB / 1 MiB, limits lifted | 14.1 s / - | 731 ms / 2.30 s | 714 ms / 2.10 s |

The last column from 2026-11-06 too, against the codec in interleaved runs (a loaded 64-way
box: about a tenth of spread); the time per byte, about 2 us, is the instructions'.
At `--optimize=size` the codec is 76 of the 109, the host copies of `get-buffer` and `set-buffer`
the other 30 (16 and 14; 9 each where the level allows them eight bytes a round,
`.kb/wasm-gc-strings.md`). wasmtime 49: 40 rounds over 8,491 bytes 61.5 M fuel with 21.5 M of
setup, the walk 415 M.

**1 MiB within 16 MiB cannot be reached by `json-parse` and `json-stringify` over strings,
whatever the tree costs**: Endive holds an element of an `i8` array in a long, so the input
string is 8 bytes a byte and the output at least 8 more -- 16 MiB at 1 MiB before the tree or
the linear memory. At 1 MiB 52.3 MB is charged, 49.9 bytes a byte: the tree about 23.5 (an
item's small table 216 bytes, its header and cell 208, its value string 168 and 136, its vector
slot 32, for 32 bytes of JSON; it was about 46 with flat tables of 1,128), the decoder's value
stack 2, the input string 8, the output 16.4 (the result and its chunks). It takes an operation
over the host's bytes in linear memory, which builds no tree: `json-span` below.

## json-span
`rontolisp:json-span (document &rest path)` / `(address size &rest path)` answers `start end
count cut-start cut-end`, byte offsets from the document's first byte, or NIL where the path
leads nowhere; `json-value` answers `(values (json-parse <the value's bytes>) t)` or `nil nil`.
Docs: `doc/*/reference/functions/rontolisp-json-{span,value}.md`. Decided 2026-11-06 with
sluice's wishes (forwarded by the orchestrator):
- **The path is `proxy-wasm:get-property`'s shape**: rest arguments, a string a key, a
  non-negative integer an index, none the document. `JsonLibrary.rewritePathCall` hands the
  helpers `(document (list segment...))`; the address form's size rides there as the first
  element (`%json-native-scan` splits it). A bad segment is code 17 before the document is read.
- **Offsets are bytes on every backend**, as `set-buffer`'s `:start`/`:size` are: a string is
  its UTF-8 octets, so past ASCII an offset is no character index.
- **A document json-parse refuses signals json-parse's error under json-span's name**
  (`%json-span-fail`, codes 1-16 in `%json-native-fail`'s order, 17-19 the arguments'), so a
  handler tells "not JSON" from NIL. Both scans make the decoder's decisions, the hex escape's
  character count included.
- **`count` is the third value**, not a function: the scan has it, and an insert's comma needs
  it. **`cut-start`/`cut-end` are what deleting the member takes**, which the value's span cannot
  give: from the comma before it; for the first, to the next member's start; for the only one,
  all inside the container (`{}`). Worked out after the scan, from the member's start, the comma
  before it and the container's opening, the document being valid by then.
- **A repeated key answers its last occurrence**, json-parse's: a key matching again clears what
  the earlier one led to.
- **`json-value` exists so a filter ports by its first arguments only** and reads a field with no
  memory accessor of its own.
- **Interpreter and JVM: a walk in json.lisp** (`%json-locate`) over the octets, one text for
  both as json-parse's walk is; `string-to-octets` encodes a lone surrogate as the three bytes
  wasm-GC stores. An integer document is code 19 there: no linear memory.
- Name trap: the helper is `%json-span-value`. `%json-value` is the parser's `(s i n)`, and a
  defun of that name redefined the parser on the interpreter.

The wasm-GC scan is `WasmJsonSpanRuntimeBuilder`, one function `(%json-scan document size path
mode)` in a block after the codec's (`emitsJsonSpan`), two copies of one emitter: `i32.load8_u`
for memory, `array.get_u` for an array. 12,059 bytes raw of the sample filter's 74,846.
- **A quote past the last byte ends every loop**, as the decoder's frame does. A string has it;
  past an address the scan writes one and puts the byte back (the bytes are the module's for the
  callback, `with-buffer`); bytes ending with the memory are scanned as a framed copy; bytes past
  it are code 18. An octet vector is copied framed once.
- **The way to the value**: `on` the deepest container on it, `focus` the depth whose members are
  looked at (`on`, or once entered the value's own container `tdepth`), `match` what the next
  value is. A value, key, comma and close each pay a depth comparison; only a container at
  `focus` compares a key (`compareKey`, escapes decoded only for a key with a backslash) or counts.
- Allocated: the path as an array, a 16-byte stack of the open containers' kinds that doubles.
  Nothing a byte.
- On Endive a string byte is 6 instructions (the position moving first), a digit 10, a token
  about 50 (a value's dispatch alone 27: blocks count): 17.6 a byte on the bench document's
  30-byte items. A colon right after a key skips the blank scan (6 a key). wasmtime 49: 200
  scans of 8,491 bytes in memory 27.1 M fuel, 16 a byte, against the codec's 118.
- Trap: `ignore-errors` under `multiple-value-bind` binds the condition to `end`, and arithmetic
  on it kept the condition printer and `format`: the sample filter 162,749 bytes against 74,803
  with `(handler-case ... (error () nil))`.

Measured 2026-11-06, sluice f408d6e (a copy), Endive 1.2.0, the defaults (16 MiB, a 1 s call),
`{"items":[{"id":N,"name":"item-N"},...]}`, item 100's name read, replaced and deleted; a loaded
64-way box (load 15-21), best of 3 after a first run:

| | span: add / read / replace / delete | tree (json-parse, json-stringify) |
| --- | --- | --- |
| instructions a byte, 64 KiB | 18.0 / 18.7 / 18.7 / 18.7 | 102.2 / 58.4 / 102.1 / 102.1 |
| charged at 64 KiB | 19.0 / 7.7 / 5.5 / 5.5 KB | 3.52 / 2.43 / 3.51 / 3.51 MB |
| 256 KiB | 125 / 125 / 155 / 94 ms | 499 / 286 / 512 / 523 ms |
| 1 MiB | 406 / 418 / 408 / 398 ms (first 416-580) | refused: memory |
| 2 MiB | 684 / 792 / 761 / 833 ms, one first run past 1 s | refused |

The sample (`json_plugin`, a field added, sluice's `JsonBench`): 1 MiB best 475 ms, first 688;
2 MiB at the call's limit (928 ms, 3 of 4 past it under that load); 4 MiB past it. At 1 MiB 52 KB
charged, 1.35 MB peak (the body's 1 MiB in linear memory), against the tree's 52.3 MB and 107 MB.
The tree plugin's largest body stays 256 KiB. Module 74,846 / 24,892 gzip, the tree one 71,031 /
25,495. The time is the instructions' (about 20 ns each with sluice's allocation listener on).

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
(`jsonOpsWorkInPreview1Mode`, with the codec's fuel bound and json-span's address form: the byte
put back, the copy at the memory's end, code 18, the scan's fuel bound;
`plistHashTableAndClosObjectsInPreview1Mode`,
`equalAndHashTablesAcceptRuntimeBuiltStringKeys`); ci-spec `json-parse-and-stringify`,
`json-codec-edges` (every error, escape and number form, pooled keys, structures, deep nesting,
objects either side of eight keys stored into and removed from, on all four),
`json-span-and-value` (offsets, counts and cuts past multi-byte characters, repeated and escaped
keys, nesting past the kind stack, every error, on all four),
`string-equal-content-and-hash-keys`, `alist-plist-conversions`.
Native binary embeds `json.lisp` via `resource-config.json` (typeReachable `JsonLibrary`).
