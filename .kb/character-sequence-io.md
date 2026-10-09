# Bulk character I/O: `read-sequence` into a string

**Invariant: `(read-sequence buf stream)` with a CHARACTER buffer -- a `make-string` result
or any rank-1 `:element-type 'character` array -- moves a BLOCK of storage units per host read
on all four backends, never one read per character. Reading a file into a string costs about
what reading the same file as BYTES costs; it may not cost a microsecond per character.** A
cost invariant with an exact behavioral contract: the block arm must answer, element for
element, what the per-character loop answered -- one code point per slot whatever its encoded
width, a sequence split by the end of a block completed rather than lost, `:start`/`:end`
honoured, and the stream left exactly where the last character ended.

The character sibling of `.kb/binary-sequence-io.md`, and the last mechanism between a
program and its text: the ACCUMULATE was fixed first (`.kb/string-accumulate-cost.md`,
`.todo/704`), and what was left of `uiop:read-file-string` was this
(`.todo/721`).

## The seam: one expansion, one declining primitive per backend
- `LispMacroExpander.expandReadSequence` (shared by all three front ends) calls
  `(%read-sequence-chars seq stream start end)` inside the same `or` the packed primitive
  sits in, AFTER it: the primitive answers the fill position, or **NIL = declined**, and the
  `or` falls through to the `read-char` loop. `LispNames.READ_SEQUENCE_CHARS`, in
  `PackageRegistry.CL_INTERNALS`. The BYTE-ONLY expansion (`compiler.SequenceIoNarrowing`)
  omits the call entirely -- no character buffer can reach such a site.
- **Never overshoots, by construction**: each round asks for exactly as many storage units
  (UTF-16 units on the JVM and the interpreter, BYTES on wasm) as there are code points still
  wanted. A code point is one unit or two (one byte to four), so N units hold at most N of
  them, and the buffer fills before the units run out only when every one of them was a code
  point of its own. No `fd_seek` and no shared buffer is needed -- which is what makes the
  same design fit all three backends and both WASM targets. The one byte wasm can read past
  what it consumes is the byte that shows the last sequence malformed ("Malformed input"
  below); it goes into the fd's byte pushback.
- **A sequence split by the end of a block** is completed rather than dropped: one more read
  behind a `mark(1)` on the JVM and the interpreter (a high half followed by anything else is
  its own character and the unit after it is put back, exactly as `read-char` does); on wasm
  a follow-up `fd_read` of ONE byte at a time into the block's four spare bytes, until the
  sequence is complete, malformed, or cut short by end of file (one U+FFFD).
- **Interpreter** (`Environment`, beside `%read-sequence-packed`): `Environment.readCodePoints`
  over the `BufferedReader` table entry (a file OR a string input stream) or the
  standard-stream designator, storing through `LispString.setCharAt`. A Gray instance, a
  socket, a served request body, a binary stream, a source literal and any non-string buffer
  decline.
- **JVM** `JvmIoRuntimeBuilder.buildReadSeqChars` -> `_readSeqChars`, called by
  `JvmSequenceCharsCompiler`. The buffer is the mutable character vector -- an `ArrayList`
  whose slot 0 is the LENGTH-4 header and whose elements follow from slot 1
  (`.kb/adjustable-arrays.md`) -- and each element is stored as `int[1]{cp}`, the runtime
  CHARACTER. Minted only when the program mentions `read-sequence` (`usesCharSequenceIo` in
  `JvmLispCompiler`, threaded into `Ctx`); otherwise the primitive compiles to
  `aconst_null`.
- **wasm-GC / `--component`** `WasmCharIoRuntimeBuilder` -> `_read_seq_chars`, index
  `FUNC_READ_SEQ_CHARS` appended after `FUNC_RENAME_FILE` (it becomes `FX_FUNC_LAST`; no index
  above shifts), signature `TYPE_CALLABLE_BASE + 3`, called by `WasmSequenceCharsCompiler`.
  The buffer test is one `_charvec_p` call -- the marker invariant keeps its single owner --
  plus "the data slot is the element array" (a string view and a displaced view decline) and
  "rank 1". Bytes stage through a 64 KiB block plus four spare bytes reserved at `HEAP_PTR`
  and popped after (the `_open` discipline). Bytes the fd's pushback holds (a peek's, or a
  byte read past a malformed sequence) are read first, a character at a time through
  `_read_char`, which takes them before the fd. A negative i31
  (a string input stream) declines, so `with-input-from-string` keeps the element loop there.
  A program with no `read-sequence` gets a declining STUB body.
- **A range outside the buffer DECLINES rather than trapping** on every backend, so the loop
  signals exactly the error it always did.

## Malformed input
**Every character stream over a file, a socket or standard input decodes malformed UTF-8 by
Java's rule on all four backends**: a byte that leads no sequence (`80..C1`, `F5..FF`) is
one U+FFFD; a sequence a byte outside its continuation range interrupts is one U+FFFD for
the bytes before that byte, which then starts the next character; a sequence end of file cuts
short is one U+FFFD; the second byte is `A0..BF` after `E0`, `90..BF` after `F0`, `80..8F`
after `F4`; and a complete three-byte surrogate encoding (`ED A0..BF xx`) is ONE U+FFFD. The
last rule is Java's, not the Unicode recommended practice (which narrows `ED`'s second byte to
`80..9F`, making `ED A0 80` three U+FFFDs) -- chosen because the JVM backend's plain
`FileReader` and the Clojure oracle's `slurp`/`reader` answer it. `octets-to-string` keeps its
own lenient rule (`.kb/characters-code-points.md`).

Measured 2026-10-08/09, the bytes `41 E9 42 FF`: interpreter and JVM `(65 65533 66 65533)`,
both WASM legs `(65 37055)` (the lead byte took whatever followed it, unchecked). The item's
premise that the interpreter already answered Java's rule was half true:
`runtime/RontoCharFileReader` (the interpreter's `open`, and the JVM's in a program naming
`file-position`) narrowed `ED` like the Unicode practice, so `ED A0 80` was three U+FFFDs
there and one on the JVM's `FileReader`; and `runtime/RontoIoFileStream` (`:io`, interpreter
and JVM) took continuation bytes unchecked like wasm. Both now decode by the rule above.

- **wasm: one decoder, `WasmUtf8StreamDecoder.emitDecode`**, a byte at a time over a fetch
  callback: `_read_char` and `_peek_char` fetch through `emitNextByte` (the fd's pushback,
  else one `fd_read`), `_read_seq_chars` from its block. `_read_line` is a `_read_char` loop
  that re-encodes each character, so a line is a well-formed string. String input streams keep
  the lenient decoder: their bytes are a string's own encoding.
- **The fd byte pushback replaced peek-char's code-point slot**: `PUSHBACK_KEY_ADDR` holds
  `(fd + 1) << 3 | count` (0 = empty), `PUSHBACK_BYTES_ADDR` 1..4 bytes, the front in the low
  byte. Writers: a decode pushes back the byte that interrupted a sequence; a peek pushes back
  EVERY byte it took (at most four: a three-byte prefix and the interrupting byte), so the next
  read decodes the same bytes to the same character. `file-position` subtracts the count
  (exact now, where the slot subtracted the parked code point's UTF-8 length -- wrong after a
  U+FFFD); a seek and a `close` drop it. ONE descriptor at a time: a push for another fd
  replaces the bytes, the trade the code-point slot already made (interleaved reads of two
  malformed streams can lose one byte there).

## The numbers
Measured 2026-09-12 (JDK 25, wasmtime 47, Linux x64) on a 2,668,890-character UTF-8 file
(2,836,915 bytes -- the first 2.67M characters of a Qwen3.5 `tokenizer.json`), before -> after:

| | interpreter | JVM class | wasm-GC |
|---|---|---|---|
| `read-sequence` into a 4,096-character buffer, chunks discarded | 3,141 -> **86** ms | 322 -> **104** ms | 3,283 -> **62** ms |
| `uiop:read-file-string` | 3,695 -> **366** ms | 605 -> **322** ms | 3,698 -> **349** ms |
| the same file read as BYTES + `rontolisp:octets-to-string` | 317 -> 307 ms | 324 -> 321 ms | 160 -> 150 ms |

So the character read is now 26 ns per character on the interpreter (was 1.2 us), 39 ns on the
JVM (was 121 ns) and 23 ns on wasm-GC (was 1.2 us), and **`uiop:read-file-string` is at parity
with the byte detour on all three** -- the `--component` leg, not in the table, measured 453 ms
after and shares the Preview 1 read path. What is left in `read-file-string` above the raw read
is the `with-output-to-string` accumulate it is built out of (~260 ms of the interpreter's 366).

**The JVM's premise was different and is worth remembering**: its character arm was never a
microsecond per character -- a `BufferedReader.read()` plus a boxed store is 121 ns -- so the
gap there was 1.9x, not 23x, and most of what remained after the fix is the accumulate. The
same block arm still paid for itself 3x on that leg.

**The remaining accumulate does not shrink further at the Lisp level**: `.todo/786` tried
replacing the `with-output-to-string` accumulate with a `make-string` buffer that doubles,
to cut three character-touches to two, and measured it SLOWER on every backend it could
test -- `make-string`'s mandatory `:initial-element` fill costs more than the touch it was
meant to save. Numbers and why: `.kb/string-accumulate-cost.md`, "The 'sized-once'
alternative is not a win".

## Tests
ci-spec `read-sequence-over-a-file-decodes-a-block-of-characters-at-a-time` (all four
backends: the non-BMP character sits ON the block boundary of every one of them);
ci-spec `file-character-streams-decode-malformed-utf-8-as-the-jvm-does` (all four: read-line,
read-char, peek-char, read-sequence completing past its block, file-position, `:io`);
`MalformedUtf8FileDecodingTest` (both runtime decoders against `InputStreamReader`, every
sequence of up to three boundary bytes); `WasmLispCompilerIntegrationTest`
`#fileCharacterStreamsDecodeMalformedUtf8AsTheJvmDoes` and
`#everyBoundaryByteTripleDecodesAsJavasDecoderOnEveryCharacterRead` (every triple run
together, 65,856 bytes, through the five read paths on both wasm targets);
`readSequenceOverACharacterBufferDecodesABlockAtATime` in `LispEvaluatorTest` /
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`; and the per-backend COST pins
`evalReadSequenceIntoAStringCostsAboutWhatTheSameFileCostsAsBytes` /
`compileReadSequenceIntoAStringCostsAboutWhatTheSameFileCostsAsBytes` /
`readSequenceIntoAStringCostsAboutWhatTheSameFileCostsAsBytes`, which read the same 1,048,576
characters as bytes and as characters off one file and bound the second by the first -- a
self-calibrating ratio, so no machine-dependent millisecond budget is written down. The
wasm pin runs the timed pair three times and pools the attempts -- cheapest string leg
against the most generous byte leg, bound formula unchanged (2026-09-24, after the plain
any-attempt-passes retry of `.todo/822` still failed once on CI with 786 ms against a
734 ms bound): contention only ever ADDS to a leg's time, so the min string leg is that
leg's true cost and the max byte leg is the loosest honest denominator, while a
per-character `fd_read` regression raises every attempt by ~1000x and its min still
misses the bound by three orders of magnitude.

Related: `.kb/binary-sequence-io.md`, `.kb/string-accumulate-cost.md`,
`.kb/string-index-cost.md`, `.kb/read-load-streams.md`, `.kb/adjustable-arrays.md`.
