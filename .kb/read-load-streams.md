# `read`/`load`, `read-line`, file streams in all three backends

A runtime reader is emitted into compiled output (like `eval`). Interpreter `LispReader`/`Files`;
JVM recursive-descent `JvmReadRuntimeBuilder`; WASM `WasmReadRuntimeBuilder` over linear memory,
interning symbols to shared string offsets.

## Emitted reader: frontend parity, else SIGNAL
**Invariant: the emitted reader has FRONTEND PARITY; anything outside it SIGNALS, never misreads.**
`buildReadExpr` (JVM) / its WASM twin cover the frontend lexer's whole set: `(` (dotted pairs), `'`,
`"`, `)`, atoms (symbol / integer / bignum (JVM) / double / ratio / nil / t), and the `#` mirror —
`#'`, `#\` (the frontend's 9-name table), `#(...)`/`#nA(...)`, `#*`, `#f(`/`#d(` packed float arrays
(`--simd` builds the VBLOCK layout), `#S(...)`, `#P"..."` (fixed PATHNAME layout), `#x`/`#o`/`#b`,
nesting `#|...|#`.

- Unclaimed tokens fall to the atom path like `readSymbol`: `#foo` -> `#FOO`, `#16r1f` -> a symbol.
  The interpreter's reader has since learned errors the emitted readers never did: `..`/`...`
  (dot-only tokens), `#:a:b`, `#<`, `#n*` over/under-fill, a constituent behind `#*` bits and a
  trailing `\` are reader errors there and symbols here. The `#n*` fill (repeat-last-bit) the
  interpreter computes in the lexer has no twin on either compiled backend.
- PERMANENT limits: `#.`, `#+`/`#-`, `#n=`/`#n#` signal a catchable error. The interpreter's runtime
  read still resolves the first two and reads labels — one documented interpreter/compiled
  divergence; `#.` EVALUATES via `Environment.setReadTimeEvalResolver`, gated on `*read-eval*`
  (`.kb/reader-features.md`).
- Errors: JVM `RuntimeException` caught as `simple-error` with the frontend's EXACT messages;
  WASM `unreachable`, or in EH mode a catchable `$lisp-cond` throw whose message is STATIC, no
  name interpolation (`WasmReadRuntimeBuilder.emitErr`). The interpreter instead signals TYPED
  conditions -- `reader-error` for a bad token, `end-of-file` for input that ran out mid-datum,
  both carrying the stream `stream-error-stream` reads back -- converted from `LispReadException`
  (whose `isEndOfFile` flag is the distinction) in `LispEvaluator.foldStructLiteralsOf`. A
  `handler-case` for `error` catches both spellings everywhere, so the divergence only shows to
  a clause naming the type.
- `#S`: JVM bakes `_rdStructs` in `<clinit>` (`structTableClinit`, gated on
  `usesRead && mayUseInstances`); WASM appends a directory blob after the `WasmInstanceLayouts`
  records (`buildReadCtx`). An omitted slot takes a nil initform, re-reads a baked
  `EmittedReaderInitforms` constant text in place, or signals — never a silently wrong value.
- The reader forces the JVM array machinery (`usesFloatArray |= usesRead`). WASM integers are `i31`;
  decimal floats -> `TYPE_FLOAT` via `emitTryFloat`, exact under one rounding for |exp| <= 22.
- Dotted pairs: `.` is a dot token only when the next byte is a delimiter (whitespace `( ) ' " ;`) or
  EOF (`LispReader.readList`, `buildReadList`, `buildReadListBody`).

Pinned by `Jvm/WasmLispCompilerTest#compileReadFromString{CharLiterals,RatiosAndRadix,VectorsAndArrays,StructLiterals,SymbolParityAndBlockComments,ReaderErrors*}`,
ci-spec `runtime-read-*`.

## `read` is PRELUDE RONTOLISP, not a primitive on any backend
One `LispPreludeLibrary` entry (`read` + the `%rd-*` family), so all four backends run one code path.
It consumes exactly ONE datum's characters and leaves the stream after them.

- **The scanner only DELIMITS**; `read-from-string` parses the text, so a syntax the emitted reader
  lacks (backquote, `|...|`) is a `read-from-string` gap, identical for `read`. The walk mirrors
  `LispLexer.skipDatum`, where `#+`/`#-` take feature expression AND guarded form as one unit.
- The character a terminator gives back rides the `unread-char` cell (`unread-char.lisp` on compile
  paths, the `Environment` cell interpreted) — what makes `read` + `read-line` on one stream work.
- **After the object, ONE whitespace character is consumed** (CLHS 23.2); a terminating macro
  character is unread instead.
- EOF: `eof-error-p` defaults to `t`, so `(read s)` signals `end-of-file` at end of input and
  `(read s nil v)` answers `v`; an INCOMPLETE datum signals `end-of-file` whatever `eof-error-p`
  says. The prelude's own scan errors are typed too (`reader-error` for a bad token, `end-of-file`
  with the stream attached) -- one prelude definition, so all four backends agree here.
- **Trap**: `read` needs BOTH compile-path passes — `LispPreludeLibrary.process` and
  `UnreadCharLibrary.process` — in `CompileFrontend`'s order; neither run = call-time "The function
  READ is undefined".
- Dead: `JvmReadRuntimeBuilder`'s `_read`/`_readStream`. WASM's `FUNC_READ` keeps its slot with an
  unused stub — removing a function shifts every later index and changes the component blobs.

Pinned by `LispEvaluatorTest#read*`, `JvmLispCompilerTest#compileAndRunRead*`,
`WasmLispCompilerIntegrationTest#read*`, ci-spec `read-stream-datum-by-datum`.

## `read-from-string` answers the STOP INDEX, and honors `*read-suppress*`
**Invariant: the second value exists on ALL FOUR backends; the suppressed MODE is the
interpreter's alone** (the `*read-eval*` shape -- the emitted readers have no such mode).
- The index is `%read-from-string-end`, emitted ONLY by the multiple-value lowering of a
  `read-from-string` producer (`LispMacroExpander.isMvProducerForm`), so a plain
  `(read-from-string s)` parses once and pays nothing. Interpreter: `LispLexer.datumEnd`,
  a RAW-CHARACTER scan (`skipDatum`, the `#+`/`#-` walk) rather than a second parse --
  which is what lets it answer for text the parse refuses. JVM: `_readFromString` then
  `_readPos`. WASM: the cursor delta, the start value riding the OPERAND STACK across the
  parse call rather than costing a scratch address.
- CLHS 23.2 decides the last character: a whitespace terminator is CONSUMED with the
  datum it terminates, a terminating macro character is given back -- and (SBCL-verified,
  2026-09-19, `.todo/903`) this holds for ANY datum, not only a token: a list, a string
  and a character literal all swallow one trailing whitespace character exactly like a
  symbol or number does. `"abc  def"` -> 4, `"(1 2) x"` -> 6, `"\"str\" x"` -> 6. The
  premise once recorded here (only a token's terminator is consumed) was the bug
  `.todo/903` fixed: interpreter `LispLexer.datumEnd` no longer gates the trailing-
  whitespace check on how the datum ended; JVM `_readFromString` advances `_readPos` past
  it after `_readExpr` returns; WASM `%read-from-string-end` advances the cursor the same
  way before computing the delta.
- `*read-suppress*` (CLHS 2.2): the datum's characters are consumed and NOTHING is parsed,
  so an unknown package, a bogus character name and an out-of-range digit all pass. The
  interpreter's built-in reads the variable through `Environment.setReadSuppressQuery`,
  which the evaluator installs for the reason the `#.` resolver is installed: the built-in
  holds the GLOBAL environment and the binding is always dynamic.
- **`read` must stay SINGLE-valued**: its prelude body ends in `(values (read-from-string
  %rd-text))`, and without that `values` the index rides out of the tail through the spill
  and every `(read s)` answers two values.
- **The publication is TAIL-position only.** `lowerMvProducer` runs
  `spillEscapingMvProducers` over a producer form it does not itself recognize, so a
  recognized producer at the end of the `(let ...)`/`(progn ...)` the consumer was handed
  publishes -- and one whose value is DISCARDED (a loop step, a `let` initform) does not.
  Measured: publishing on EVERY call was +219/-62 on the ANSI suite, the 62 being the
  discarded calls' indices surfacing as an enclosing form's second value; tail-only was
  +213/0 (`.todo/715`).
- Not supported, and the six ANSI `READ-FROM-STRING.*` tests still on it: the real lambda
  list (`eof-error-p`, `eof-value`, `:start`, `:end`, `:preserve-whitespace`). The
  producer is recognized at ONE argument only, so a call carrying them keeps the old
  single value rather than answering an index computed as if they were absent.

Pinned by `LispEvaluatorTest#readFromStringAnswersTheStopIndexAsItsSecondValue`,
`#aDiscardedReadFromStringLeavesNoSecondValueBehind`,
`#readSuppressConsumesTheDatumAndAnswersNil`,
`JvmLispCompilerTest#compileReadFromStringStopIndex`,
`WasmLispCompilerIntegrationTest#readFromStringStopIndex`, ci-spec
`read-from-string-stop-index`.

## `read-line`, `read-char`, `peek-char`
- `read-line` strips one trailing CR everywhere (`BufferedReader.readLine`; WASM `_read_line` does an
  explicit `pos--` on `0x0D`), so a lone `\r\n` line reads `""`, not `"\r"`.
- `read-char`: JVM `_readChar` (lazily initializes the shared `_stdinReader`); WASM `_read_char`
  (`FUNC_READ_CHAR` after `FUNC_FBOUNDP`) reads ONE BYTE from fd 0 / a WASI fd via
  `BYTE_SCRATCH_ADDR`, or a negative string-stream handle's `[cursor,end)`. **Trap**: the compilers
  evaluate multi-argument call forms right-to-left, so sequence consecutive `(read-char s)` calls
  through `let*`, not `(list ...)`.
- `peek-char`: only "the next character, left in place" is a primitive (`%peek-char`,
  `PEEK_CHAR_INTERNAL`); the SKIPPING forms are one shared `LispMacroExpander.expandPeekChar`
  lowering, kept behind a runtime `(null ...)` test for a non-literal peek-type. **The `characterp`
  guard in the loop's end test is load-bearing**: with a nil `eof-error-p` it ends the loop on the
   eof-value instead of skipping forever. Interpreter/JVM `mark(2)`/`reset()`; WASM has no reset, so a
   WASI fd parks the code point in a ONE-SLOT pushback keyed on the fd (`PEEK_FD_ADDR` = fd+1,
   `PEEK_CP_ADDR`). `_read_char`, `_read_line` and the bulk `read-sequence` character path all
   drain it first (`.todo/936`); `read` never needed a drain of its own -- the whole prelude
   `%rd-*` scanner family consumes through `read-char`. A parked newline ends the next `read-line`
   as an empty line, the loop's own newline-break shape; any other parked code point is
   UTF-8-encoded into the line's staging ahead of the fd bytes. Cost (2026-09-23): a `read-line`
   program +291 B Preview 1, a program without one byte-identical; the `_read_line` core body is
   one build shared by both WASM backends.

## `open` / `with-open-file` / `%probe-file`
- `with-open-file` is a plain macro (`expandWithOpenFile`) over `open`/`close`.
- The positional form's direction is ONE literal token so both compilers resolve the mode at
  compile time (`compiler.OpenModes.directionMode` / `staticMode`, used by `Jvm/WasmOpenCompiler`):
  `:input`, `:output`, `:io`, and the four normalized direction + `:if-exists` spellings
  `:append`, `:overwrite`, `:io-append`, `:io-overwrite` (`LispNames.outputDirectionToken`).
  Mode bits `OUTPUT_BIT` 1, `BINARY_BIT` 2, `APPEND_BIT` 4, `OVERWRITE_BIT` 8, `IO_BIT` 16
  (`:io` sets `OUTPUT_BIT` too): 0/1/2/3 text-in/text-out/bin-in/bin-out, 5/7 append, 9/11
  overwrite, 17..27 the three `:io` dispositions x element type.
- **A failed `open` signals a `file-error` on every backend**, carrying the designator as given
  (`file-error-pathname`) and reporting `OPEN: cannot open file <namestring>` -- one text on all
  four. Interpreter: the `open` built-in throws `ClosRegistry.newFileErrorCondition`. Compiled:
  ONE shared call-site lowering, `LispMacroExpander.expandOpenFileErrorSignal` (the
  `expandReadEofSignal` shape), applied by `Jvm/WasmExprCompiler`'s `open` case -- the backend
  opens through the internal `%open-or-nil` (`Jvm/WasmOpenCompiler`), which answers nil on
  failure, and the expansion tests it and calls `%file-error`. WASM `_open` answers
  `ref.null eq` on a non-zero errno; JVM `_open` catches `IOException` in its own exception
  table (`IoMethod.exceptionTable`, the one runtime method that has one) and answers null. A
  computed option dispatches onto literal `open` leaves FIRST, each leaf lowering separately.
  `%file-error` itself is the `%program-error` split (`lowerFileError`): a typed
  `%error-cond` over a `%obj-new` behind a landing pad, plain `%error` otherwise -- so the
  whole-program scans cannot see the construction and take `LispMacroExpander.FILE_ERROR_SITES`'
  presence for the tag (`conditionNarrowing`, `WasmLispCompiler.usedLayoutTags`). Cost
  (2026-09-19, `--optimize=size`): `with-open-file` + `read-line` without a handler +39 B P1
  / +37 B component / +213 B JVM class; inside `handler-case` +172..+243 B WASM, +370..+501 B
  JVM.
- `--component`: `adapter.wat`'s `$ensure_preopen` read the first `get-directories` element
  unconditionally, handing `open-at` handle 0 with no `--dir` (`unknown handle index 0` trap); it now
  caches `-1` and `$path_open` turns that into an errno. Hit `probe-file` too.
- **`%probe-file` stays a string-in/string-out PRIMITIVE**, not `open` in a `handler-case`: nil is
  cheaper than catching a condition, works outside EH mode, and `--no-gc` rejects catching. Public
  `probe-file` is prelude Lisp over it (`uiop:file-exists-p` lowers onto it). Contract: the
  namestring when the file exists (nothing resolves symlinks or absolutizes), nil otherwise; a
  directory counts as existing. Interpreter goes through the installed **`SourceLoader.exists`**,
  never `Files` directly, so the playground's in-memory loader answers (`fileSystem()` OVERRIDES with
  `Files.exists` — a read is wrong for a file that exists but is not decodable text). JVM
  `_probeFile` + `JvmProbeFileCompiler`; WASM `_probe_file` (`FUNC_PROBE_FILE` after `FUNC_T_SYM` as
  the new `FX_FUNC_LAST`) closes the fd via `fd_close` (`probeFileLeaksNoDescriptor`).
- **`:if-exists :append`** is the ONE non-default value of the three otherwise-ignorable options that
  is implemented rather than rejected: it normalizes into the `:append` PSEUDO-DIRECTION (not a CL
  direction; produced only by `OpenModes.normalizeKeywordForm` and `expandWithOpenFile`, shared
  predicate `isAppendIfExists`), so every backend reads ONE literal token. WASM `_open` answers
  `oflags = O_CREAT` alone (O_TRUNC would discard exactly what the append keeps) plus
  `fdflags = FDFLAGS_APPEND`. `:if-exists :append` with `:direction :input` is ignored, as in CL.

Pinned by `LispEvaluatorTest#evalOpenAppendKeepsTheExistingContent`,
`JvmLispCompilerTest#compileAndRunOpenAppend`, `LispEvaluatorTest#probeFile*` + twins, ci-spec
`probe-file-existing-and-missing`, `open-if-exists-append-keeps-the-existing-content`.

## The `:if-exists` / `:if-does-not-exist` table is ONE lowering over `probe-file`

**No backend learned a mode for it.** `.todo/906`: the whole table plus `:direction :probe`
is a GUARD around the existing literal open, built once in
`LispMacroExpander.lowerRuntimeOpenOptions` and reached by `open`'s two compilers (through
`OpenModes.lowerRuntimeOptions`), `expandWithOpenFile` and the `#'open` wrapper. The
interpreter's `open` built-in is the fourth reading, natively over `Files.exists`; the two
must stay in step, and the cross-backend pin is ci-spec
`open-if-exists-if-does-not-exist-and-probe`.

- The guard reads `(probe-file path)` ONCE into `__open_act`, a keyword of `:open` /
  `:create` / `:error-exists` / `:error-missing` / nil, and the base open appears once
  below it -- a dispatch over six literal leaves is not duplicated. Both arms folding to
  "just open" emits NO guard, so an existing program keeps its exact bytes
  (`needsOpenExistenceGuard`, `aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore`).
  The `probe-file` prelude entry's splice gate therefore keys on the SURFACE option list
  (`callsOpenWithExistenceGuard`, the `callsLoadWithIfDoesNotExist` pattern) -- `#'open`
  counts, since its wrapper passes every option computed.
- **`:if-exists` is read only on an OUTPUT open.** An input or probe open drops it whatever
  it says, which is what makes `(open p :if-exists :rename)` the plain input open CL says
  it is. `:supersede` / `:new-version` / `:rename` / `:rename-and-delete` all collapse onto
  the truncating open (no version numbers here, and SBCL leaves the same content);
  `:error` and nil answer instead of opening; `:append` and `:overwrite` are the
  pseudo-directions above (section "`:direction :io` and `:if-exists :overwrite`").
- **`:if-does-not-exist` defaults per CLHS**: `:create` for a superseding output or `:io`
  open, nil for `:probe`, `:error` everywhere else -- INCLUDING an APPENDING or OVERWRITING
  open, which
  therefore no longer creates the file (measured against sbcl 2026-09-20; smart-buffer's
  disk spill is unaffected because `uiop:with-temporary-file` creates the file first).
  `:error` on a non-output direction is left to the open's OWN failure, so the one
  "cannot open file" text still comes out and no guard is emitted.
- **`:direction :probe` is an open followed by a close**, not a new kind: CL's probe stream
  is a file stream that is already closed, so `(let ((s (open p :input))) (close s) s)` is
  literally it, on every backend, with `%probe-file` deciding nil.
- **`close` on an already-closed stream answers `t`** rather than signalling (CL, and
  SBCL): the `unwind-protect` shape `with-open-file` expands to closes a stream the body
  may already have closed. Three of the four needed work for it. The JVM `_closeStream`
  needed a null-entry guard -- without one the chain reached the `Writer` arm with null and
  threw. `--component` TRAPPED (`unknown handle index`, the host refusing a second drop of
  the same `descriptor` resource), so `adapter.wat`'s `$fd_close` now returns 0 when the
  slot's live flag (offset 12) is already clear; regenerate `adapter.wasm` with
  `src/wasm-component/regen.sh` after touching it. Preview 1 already ignored the EBADF.
  **`open-stream-p` on a closed handle still answers `t` on BOTH WASM backends** -- a WASI
  fd has no stream table behind it -- so the ci-spec case does not ask; the interpreter and
  JVM pins are `LispEvaluatorTest#closingAnAlreadyClosedStreamAnswersTrue` and
  `JvmLispCompilerTest#compileAndRunOpenExistenceOptions`.

## Five stream operators that are prelude Lisp over what exists

`clear-input` (the read-side `clear-output`: nothing here buffers input a program could
throw away), `interactive-stream-p` (always nil -- no backend tells a terminal from a pipe),
`stream-external-format` (always `:utf-8`), `file-string-length` (the UTF-8 byte length) and
`broadcast-stream-streams`. All five validate a stream and answer; none needed a primitive,
a per-backend compiler or a wrapper entry. The composite constructors gained the BYTE half
of their Gray protocol the same way (`stream-read-byte` / `stream-write-byte` on
`%two-way-stream`, `%echo-stream`, `%concatenated-stream`, `%broadcast-stream`), so
`read-byte`/`write-byte` reach the components like `read-char`/`write-char` already did.
Pinned by ci-spec `stream-operators-clear-input-and-friends`.

## Output left open at the end is WRITTEN, on all four backends

A file output stream the program never closes keeps what it wrote, however the program ends
(last form, `uiop:quit` / Scheme `exit` / `emergency-exit`, uncaught condition) -- C's `exit`
and Gauche behave so. **SBCL does not** (measured 2026-09-19: an unclosed `open` + `write-line`
leaves an EMPTY file under `--script`, `--non-interactive`, `sb-ext:exit` and an unhandled
`error` alike); ANSI leaves it unspecified, and cross-backend identity decided, since wasm writes
through `fd_write` and cannot lose it.

- Interpreter: `Environment.flushOpenStreams` flushes every `Flushable` in the stream table
  (a failing flush is skipped, as `exit` skips it). `RontoLispCli.interpret` calls it in a
  `finally` round the form loop -- which `LispExitSignal` and an uncaught condition both
  cross -- and `repl` likewise at the session's end. An embedder driving `LispEvaluator`
  directly owns the program's end and calls `flushOpenStreams` itself.
- JVM: `_flushStreams()V` (`JvmFlushStreamsBuilder`, same loop over `_streams`, `IOException`
  skipped) is called before `main`'s `RETURN`, by `%host-exit` before `System.exit`
  (`JvmExitCompiler`) and by the uncaught-condition handler before the rethrow
  (`JvmUncaughtHandler`), reached through `Ctx.flushStreams`. **Gated on the unexpanded program
  naming `open` or `with-open-file`** (the Scheme file ports splice Lisp that names `open`);
  `with-open-file` must be in the gate because a quit in its body skips the close. Measured
  2026-09-19 (default / `--optimize=off`): hello and `uiop:quit` keep their exact bytes, as
  does a Scheme `display` program; `open` + `uiop:quit` 9,686 -> 9,874 / 372,771 -> 372,959,
  `with-open-file` 8,411 -> 8,609 / 372,108 -> 372,293; a Scheme STRING-port program
  54,231 -> 54,454 (its spliced port helpers name `open` for the file arms -- the gate
  over-approximates, and a stray flush costs nothing else). Wasm outputs unchanged.

Pinned by `UnclosedOutputFileE2eTest` (all four backends; reads the file after the process
ends, since the spec corpora compare stdout only).

## Computed open options (the mode is still picked from a LITERAL)
`(with-open-file (s path :element-type et) ...)` — options passed down as function arguments, as
uiop's `call-with-input-file` does — lowers to `LispMacroExpander.lowerRuntimeOpenOptions`: path and
every computed option value bound left to right in one `let*`, checked, then dispatched by nested `if`
onto at most SIX literal `open` leaves. No backend learned a runtime mode — the dispatch IS the
mechanism.

- **A spec whose values are all literal never enters it** (`hasRuntimeOpenOption`, per FORM), so
  existing output stays byte-identical
  (`JvmLispCompilerTest#aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore`).
- **The path is bound once** — six leaves name it.
- **The accepted value SET is the literal path's minus the bidirectional modes and every integer
  type but the octet, and only the time of the refusal moves.** `:direction` `:input`/`:output`;
  `:element-type` `character` / `(unsigned-byte 8)` (plus unsized `unsigned-byte`,
  `(unsigned-byte *)`) -- a COMPUTED wide type is refused at call time, the `:io` trade again
  (section "Element types wider and narrower than one octet"); the interpreter's own `open`
  built-in cannot tell computed from literal and takes every element type; `:if-exists` `:supersede` and its
  version synonyms, `:append`, `:error`, nil; `:if-does-not-exist` `:create`/`:error`/nil;
  `:external-format` `:utf-8`/`:default`. A COMPUTED `:io` / `:overwrite` is refused at call
  time although the literal works -- the measured trade in the next section.
- **Three entries, one lowering**: `expandWithOpenFile`, `OpenModes.lowerRuntimeOptions` in front of
  `Jvm/WasmOpenCompiler`, and `BuiltinFunctionWrappers.openWrapper` — which is what gave
  `(apply #'open p '(:direction :output :if-exists :append))` the append it used to drop silently.
  The interpreter's own runtime `open` in `Environment` is the fourth reading of the same table;
  **keep the two in step**.

Pinned by `LispEvaluatorTest#withOpenFileComputedOptions*`,
`JvmLispCompilerTest#compileAndRunComputedOpenOptions`, its WASM twin, ci-spec
`computed-stream-options-439`.

## WASM: a path resolves against the PREOPEN TABLE, not fd 3
`_path_dirfd` (`WasmIoRuntimeBuilder.buildPathDirFdBody`, via `emitDirFdAndPath`) answers the
descriptor a staged path opens relative to and leaves the bytes it accounts for in `PATH_SKIP_ADDR`
(248). Every `path_open` goes through it — `_open`, `_probe_file`, `_list_directory`, `_load`.

- Relative -> fd 3, skip 0. No preopen covering an absolute path -> fd 3, skip 0 too, so the failure
  is the ordinary "cannot open" ERRNO each caller turns into nil — an errno, never a trap
  (`.kb/wasi-component.md`).
- Absolute is matched against preopen NAMES via preview1 imports `fd_prestat_get` (its EBADF at the
  first non-preopened fd ENDS the walk) and `fd_prestat_dir_name`. Match is a path-COMPONENT prefix,
  LONGEST wins (`/tmp/x` -> `/tmp`; `/tmpfoo` matches neither); a trailing slash is stripped first; a
  relative preopen name (`--dir .`) covers no absolute path; a path naming the preopen ITSELF becomes
  `"."`.
- **Trap: a LITERAL absolute path tests none of this.** `(with-open-file (s "/tmp/x.txt") ...)`
  appears to work with no `--dir` because `CompileTimePathnameFolder` bundles the file's COMPILE-TIME
  contents into the artifact as a `with-input-from-string` (`.kb/asdf.md`). Every pin BUILDS the path
  at run time.
- Cost: `IMPORT_FUNC_COUNT` 9 -> 11 and `FUNC_START` with it, so every emitted function index shifted
  (`fd_readdir` precedent, `.kb/directory-listing.md`). No new type entries — `fd_prestat_get` reuses
  `TYPE_INTERN`, `fd_prestat_dir_name` `TYPE_RD_MEMEQ`. In step: `--no-wasi` gets two more EBADF trap
  stubs; `adapter.wat` exports both over `wasi:filesystem@0.3.0`; `adapter-http-server-p1.wat`
  exports them as EBADF.
- **`--component` adapter**: `$ensure_preopens` caches the whole table — 16 slots of
  `{descriptor, name-len, name}` at `0x50500` — and `dirfd` MEANS `3 + preopen index`. The table is a
  COPY on purpose: `get-directories` and its name strings lift through `cabi_realloc` at the CORE's
  `HEAP_PTR`, which the core pops back after every resolution. A preopen name over 256 bytes is
  recorded with length 0, not truncated.
- **Not reached**: `file-write-date` nil on both WASM backends. Removing a DIRECTORY
  still signals there -- preview1's `path_unlink_file` cannot remove directories
  (that needs the `path_remove_directory` import, out of `.todo/257`'s scope).

Pinned by `WasmLispCompilerIntegrationTest#absoluteRuntimePathResolvesAgainstThePreopenThatCoversIt`
+ its `component` twin, ci-spec `runtime-absolute-path-open-probe-and-load`.

## WASM intern table and heap base are COMPUTED, not fixed
`_intern` appends 8-byte `(offset,len)` records to a table whose base it loads from
`RT_INTERN_BASE_ADDR` (152); the heap bump pointer is `HEAP_PTR_ADDR` (84). Both cells are seeded by
active data segments at instantiation (never in `_start` — hosts can call exports without running it)
from the final static-data size in `WasmLispCompiler.compile`:
`rtInternBase = max(RT_INTERN_MIN_BASE=8192, 16-aligned end of the string segment)`,
`heapBase = rtInternBase + RT_INTERN_REGION_SIZE (8192)`; the Preview 1 page count grows with
`heapBase` (minimum 4). With the old fixed 8192/16384 a large program's interned-string segment
overflowed and runtime interning silently overwrote static strings and eval registry records. Pinned
by `WasmLispCompilerIntegrationTest#runtimeInternTableSurvivesLargeStaticData`.

## String streams
`with-output-to-string` / `with-input-from-string` are `LispMacroExpander` expansions over
`%make-string-output-stream`, `%make-string-input-stream`, `%string-stream-contents`
(`PackageRegistry.CL_INTERNALS`), in the with-open-file let/close shape; same handle space as file
streams. Interpreter `StringWriter` / `BufferedReader(StringReader)`; JVM the same in `_streams`.

- WASM: a **negative i31 handle** whose absolute value is a 12-byte linear-memory record (a WASI fd is
  never negative). Output `[kind=1][slot][len]` over a per-stream `$str_bytes` GC byte buffer reached
  through a module-global table; input records hold `[cursor][end]` over a persistent linear copy of
  the source, consumed by a branch at the top of `_read_line` (making `_read` work for free). `_close`
  skips `fd_close` for negative handles (`WasmStringStreamRuntimeBuilder`).
- **A WASM string OUTPUT stream costs linear memory NOTHING per write.** Every append asks
  `_ostream_room(rec, n)` (`FUNC_OSTREAM_ROOM`, reusing `TYPE_RAT_NEW`) and `array.copy`s in; the
  buffer DOUBLES, so k bytes one at a time copies O(k) total. The buffer holds the frame quote `"` at
  index 0, content at `[1, 1+len)`. The old chunk list cost **15 bytes of linear memory per
  CHARACTER**, reclaimed only at `__ronto_alloc_reset`. Three traps if you move the layout: **the
  table is the GC ROOT** a linear-memory record cannot be (a `TYPE_HASH_BUCKETS` in the module's LAST
  global); **`_close` recycles the SLOT, not the record** (a free list threaded through the table's
  own entries, head `OSTREAM_FREE_ADDR` as slot index + 1 — the 12 bytes are NOT recycled, since an
  arena free list would alias the reset); **a closed stream is closed** (kind 2, slot -1, so a double
  close is a no-op and a write after one traps at the table read). Pinned by
  `WasmStringStreamArenaE2eTest`.
- **Public names, clear-on-read**: `make-string-output-stream`/`get-output-stream-string` are the CL
  spellings (`expandMakeStringOutputStream`/`expandGetOutputStreamString`; the interpreter registers
  both as real `LispFunction`s so `#'` and native-image mode work), and
  **`%string-stream-contents` CLEARS the stream as it answers** (CL's contract; the WASM BUFFER
  stays).
- `make-string-input-stream` (`expandMakeStringInputStream`) exists because a library needed the
  stream to OUTLIVE the form that made it: yason's `parse` makes one, so `lack/request` answered
  `400 Bad Request` to every JSON body (every `ningle` application). `&optional start end` routes
  through `(subseq string start end)`. **Trigger**: if another CL stream constructor is withheld for
  "the internal one covers every consumer", check whether a library now needs it as a VALUE.
- **The two macros take their full CL spec, as ONE expansion every backend shares** (2026-09-22).
  `with-input-from-string (var string &key index start end)`: `:start`/`:end` become the
  `subseq` above; `:index` binds string/start/end once (`let*`, source order) and, on a NORMAL exit
  only (`multiple-value-prog1` inside the `unwind-protect`), stores `(- (or end (length string))
  <chars still unread>)` -- counted by DRAINING the stream with `read-char` just before the close,
  because a string input stream's `file-position` answers nil on all four. `with-output-to-string
  (var &optional string &key element-type)`: a non-nil string gets the body's output appended with
  `vector-push-extend` when the body exits (in the `unwind-protect` cleanup, so on every exit where
  it compiles), and the form answers the BODY's values -- which is why
  `isSingleValuedOperator` classifies `with-output-to-string` by its spec, not by name (it used to
  clear the second value). The string does not see the output character by character -- see
  "Write-through: measured, not built" below.
  `:element-type` is evaluated when it is not a literal and otherwise dropped. A malformed spec is a
  CALL-time stub (`callTimeUnsupportedStub`), never an expansion-time throw, so a dead branch
  compiles. A spec without the new options expands byte for byte as before. Measured, ANSI `streams`
  (interpreter, suite `ca06bd9`): 447 -> 462 of 758 pass, errors 205 -> 186, lost forms 55 -> 55, no
  test regressed. **The premise that these refusals were LOST top-level forms was stale**: since the
  driver charges a raw exception inside a `deftest` to that test, all 19 were already counted
  ERRORS, so turning them into stubs moved nothing. Seven `WITH-INPUT-FROM-STRING` tests still fail
  on the lite `output-stream-p` (every stream answers t for both directions) -- fixed since, next
  bullet.
  Pinned by `LispEvaluatorTest#evalStringStreamMacroOptions`,
  `JvmLispCompilerTest#compileAndRunStringStreamMacroOptions`,
  `WasmLispCompilerIntegrationTest#stringStreamMacroOptionsCompileAndRun` and ci-spec
  `string-stream-macro-index-bounds-and-fill-pointer-string`.
- **The direction predicates answer the REAL direction on all four** (`.todo/929`, 2026-09-22;
  answers checked against sbcl). A string input stream, a request body and standard input are
  input; a string output stream, standard output and `*error-output*` output; a socket and an
  `:io` file stream both; an `:input` file stream input and an `:output` / `:append` /
  `:overwrite` one output (sbcl: an `:overwrite` output open is output only, although it runs the
  bidirectional stream kind); a CLOSED file stream neither (sbcl says the same); a synonym what
  its target says. **Kept lite**: the `t` designator answers both (it is a designator, not a
  stream: `*standard-output*` holds it, so `(input-stream-p *standard-output*)` stays t where sbcl
  says nil), and a closed STRING stream still answers its kind's direction on the compile paths
  (the kind is on the value; the interpreter's emptied table entry says neither -- untested,
  CL leaves a closed stream's direction open). Interpreter: `Environment.streamDirection`, off the
  value's KIND and, for a `:FILE`, off what the table holds (`RontoIoFileStream` both unless the
  handle is in `outputOnlyCursorStreams`, a `Reader` / `InputStream` input, a `Writer` /
  `OutputStream` output, nothing -- closed -- neither). Compile paths: `expandStreamDirectionP`
  lowers INLINE to a test of the value's kind keywords (a `while` resolving synonyms first, only
  where the program builds them), and a `:FILE` reads an ALIST, `%file-stream-directions` of
  `(handle . bits)`, that every literal open leaf registers into
  (`%file-stream-direction-register` around the leaf's stream value, `OpenModes.direction`) and
  every close forgets (`forgettingClose` with both registries' forgetters, element types first).
  **Three gates, each measured**: the inline test only in a program that NAMES a predicate
  (`Ctx.asksStreamDirection`) -- the dead `#'input-stream-p` wrapper every WASM module compiles
  and drops interned the kind keywords and moved every later string of every string-stream
  program by up to 8 bytes (`drain-by-hand` 5,972 -> 5,979 wasm) until the gate; gating the two
  WRAPPERS instead (`REFERENCE_GATED_FUNCTIONS`) also moved them, by -2 / -4 bytes (zlib), so it
  was not taken. The record only where the program can also open a file. A classifier DEFUN
  instead of the inline test cost +6.2 KB JVM / +0.9 KB wasm for one `output-stream-p` of a
  string stream (a first defun's function machinery on the JVM), a HASH-TABLE record +14.7 KB and
  a second class file (`RontoHashTable` travels); the inline test is +654 B JVM / +7 B P1 / +8 B
  component, the record on top of it (one `output-stream-p` of a file stream) +5,389 / +1,787 /
  +1,805 B. **The record's three names are `rontolisp::` internals** (the `%octets-join`
  shape: a prelude key without the package, a defined symbol with it), NOT `CL_INTERNALS`
  entries: `CL_SYMBOLS` is baked into every program that asks the runtime package API, and
  listing them there grew each such program by 92 bytes (`runtime-package-api`,
  `uiop-package-surgery`, the cffi-sqlite example). Identity, measured over 819 programs (every
  ci-spec case and every non-GUI example, JVM / Preview 1 / component, against develop at
  `241ced1`): 803 byte-identical; the 16 that differ all name a direction predicate,
  `file-position` or a composite constructor (13 ci-spec cases, the httpbin-jzon example --
  jzon asks `input-stream-p`), except two that print `rontolisp:version`'s build timestamp.
  The composite constructors now check their components' direction
  (`make-two-way-stream`, `make-echo-stream`, `make-concatenated-stream` signal `type-error`):
  with the lite answer every stream was an input stream, so ANSI `MAKE-TWO-WAY-STREAM.ERROR.5`
  and `MAKE-CONCATENATED-STREAM.ERROR.2` passed only by accident and REGRESSED when the answer
  became real, until the check.
- **`file-position` of a STRING stream is real on all four** (`.todo/929`), in characters. Input:
  counted from the stream's own start (a bounded stream starts at 0, as in sbcl), set to an
  index / `:start` / `:end`, an index past the end answering nil and leaving the cursor (sbcl
  moves past the end; CL says an error or false). Output: the characters written since
  `get-output-stream-string` last emptied it; a set succeeds only where the stream already is (so
  ANSI `FILE-POSITION.10` holds). A character `unread-char` parked counts as unread and a set
  drops it, through the same two pieces `.todo/925` built for a character FILE stream (the
  interpreter's wrapper over the pushback cell, the compile paths' `%unread-file-position(-set)`
  in `unread-char.lisp`), which subtract ONE for a string input stream -- its position counts
  characters -- and the character's UTF-8 length for a file stream. **Trap**: both sessions
  first wrote their own copy of that wrapper, and the two stacked subtract twice. Per backend:
  the interpreter's string input stream IS `runtime.RontoStringInputStream`, a `BufferedReader`
  subclass working on the string itself so its cursor is the logical position; the JVM builds
  that class only when the program calls `file-position` and can make a string input stream
  (`FileMeta.stringInputPositions`), and it then TRAVELS (+2.9 KB, a second class file) -- the
  `_filePosition` arms are `JvmStringStreamPositions`, ahead of the file arms (an output
  stream's position is the `StringWriter`'s code-point count, no class needed); both WASM
  backends answer in `_file_position` / `_file_position_set`'s string arm
  (`WasmStringStreamRuntimeBuilder.emitPositionQueryArm` / `emitPositionSetArm`), counting
  non-continuation bytes, and an input record keeps its START in a fourth word
  (`[kind][cursor][end][start]`, 16 bytes) only in a program that calls `file-position`.
  **`:end` on the compile paths is `(or (file-length s) -1)`** (`rewriteFilePositionArg`), and
  every string arm reads -1 as its own end: a string stream has no `file-length`, and the old
  fold handed the primitive nil -- a QUERY on the JVM. So a user's literal
  `(file-position s -1)` seeks a string stream's end on the compile paths where the interpreter
  signals; nothing else passes -1. **Limits**: `--no-wasi` keeps `file-position` the nil
  constant for every stream; a CLOSED string input stream still answers on both WASM backends
  (its record is never marked closed), nil elsewhere. The ANSI tests it fixed are OUTPUT ones --
  `PEEK-CHAR.18 .19` and `MAKE-BROADCAST-STREAM.6` (the zero-argument broadcast is a string
  output sink on the interpreter); `PEEK-CHAR.17` moved from error to fail (an echo stream
  echoes a PEEKED character, sbcl does not).
- **`:index` keeps DRAINING -- measured, not assumed** (2026-09-22). The premise was that a real
  position would make `:index` a read. Hand-written equivalents of the two lowerings, bytes
  JVM / Preview 1 / component: drain 38,085 / 5,972 / 9,496, position 42,579 (2 files) / 4,618 /
  8,226 -- the JVM grows +4.5 KB and gains the travelling class (plus the `_filePosition`
  machinery) where WASM saves 1.3 KB, and `--no-wasi` has no position at all, so it would need
  the drain anyway. Both answer the same index; the drain only consumes a stream that is closed
  right after. **Trigger**: a JVM string input stream that knows its position without a
  travelling class.
- **Write-through: measured, not built** (2026-09-22). A fill-pointer string that sees the output
  as it is written needs a stream kind of its own on every backend (a JVM `Writer` over the
  Lisp vector representation that travels, a WASM record kind every string-output write path
  branches on). Its benefit, counted: ZERO ANSI tests in the whole suite depend on it (the only
  string-argument tests, `WITH-OUTPUT-TO-STRING.3 .5 .6 .10`, read the string after the form and
  pass; `.4` / `.9` are `:nil-vectors-are-strings` notes), and no program in the corpus
  (examples, size-report, the library trees under `src/test/resources`) reads the string before
  the body exits -- cl-who's `with-html-output-to-string` passes its string through and reads it
  after. Also still sbcl-divergent for the same reason: `file-position` of that stream starts at
  0, where sbcl starts at the fill pointer. **Trigger**: a caller that reads the string mid-body.
- ANSI `streams` (interpreter, suite `ca06bd9`), 2026-09-22 (`.todo/929`): 599 -> 619 of 797
  (75.2% -> 77.7%), 20 fixed, 0 regressed: `WITH-INPUT-FROM-STRING.9 .11-.16`,
  `MAKE-STRING-INPUT-STREAM.1 .2`, `MAKE-STRING-OUTPUT-STREAM.1-.3 .5-.7`,
  `MAKE-SYNONYM-STREAM.1 .3`, `PEEK-CHAR.18 .19`, `MAKE-BROADCAST-STREAM.6`. The 599 is today's
  baseline, not the 582 recorded above: the code moved in between.
  Pinned by `LispEvaluatorTest#directionPredicatesAnswerTheStreamsRealDirection` /
  `#filePositionOfAStringStreamQueriesAndSeeks` /
  `#compositeStreamConstructorsRefuseAComponentOfTheWrongDirection`, their JVM twins,
  `WasmLispCompilerIntegrationTest#directionPredicatesAnswerTheStreamsRealDirectionOnPreview1` /
  `#componentDirectionPredicatesAnswerTheStreamsRealDirection` /
  `#filePositionOfAStringStreamQueriesAndSeeksOnPreview1` /
  `#componentFilePositionOfAStringStreamQueriesAndSeeks` (one text, `testsupport/StringStreamPrograms`),
  `JvmRuntimeClassFilesTest`, ci-spec `string-stream-direction-and-file-position`.
- `print`/`prin1`/`princ`/`terpri` take an optional stream on all three backends (interpreter shared
  `emitTo`; JVM `_writeStr(String, Object)`, where non-`Long` handles go to `System.out` and update
  `_col`; WASM `_write_stream_str`, whose stdout path delegates to `_write_str` keeping
  `LINE_START_ADDR`). `expandFormat` accepts a non-literal destination by building the string like
  `format nil` and emitting one `(write-string <string> __format_stream)`. Compiled print-family
  return values stay nil. Runtime `_eval` interpreters and `--no-gc` do not know string streams.

## Binary streams and binary standard I/O
`open` takes an optional third literal argument — `'character` (default) or an integer type; the
octet `'(unsigned-byte 8)` (and every spelling that is one unsigned octet: `'unsigned-byte`, `'bit`,
`'(unsigned-byte 3)`) is what this section describes, a wider or signed type the next section's.
Interpreter: `BufferedInputStream`/
`BufferedOutputStream` in the same table, `read-byte`/`write-byte` real `LispFunction`s with no
`BuiltinFunctionWrappers` entries. JVM: `_open`'s 4-way mode branch, `_readByte(handle, eofErrorP,
eofValue)`/`_writeByte`, a byte as a boxed `Long`. WASM: a WASI fd is element-type-agnostic, so
`WasmOpenCompiler` masks the mode with `& OUTPUT_BIT` (raw 2/3 would mis-select write oflags/rights)
and `_open`'s body is untouched; `_read_byte`/`_write_byte` move one raw byte through
`BYTE_SCRATCH_ADDR` (148), appended between `FUNC_P1_FUTURE_AWAIT` and `FUNC_USER_BASE`. Untyped fds
mean `read-byte` on a text-opened stream "works" there while interpreter/JVM signal — out of contract.

- **Binary stdin/stdout is the standard-stream DESIGNATOR**: `t` is the process standard stream, an
  explicit `nil` resolves through `*standard-input*`/`*standard-output*` via the shared
  `compiler.StreamDesignators` rewrite at the call site, handle 2 is stderr. **Runtime dispatch is
  "is it a handle", never "is it nil"**, because `t` is a value: WASM tests `ref.test (ref i31)` (a
  `ref.cast` on the `t` struct would trap), the JVM `instanceof Long`.
- **The JVM reads `System.in` directly, not the `_stdinReader` the character reads share** (the
  interpreter likewise reads its `in`): a shared `BufferedReader` would swallow bytes the next
  `read-byte` owes the caller.
- **The JVM needs an explicit flush; the other three do not.** A single-byte `write(int)` only
  flushes on `'\n'`, so `JvmLispCompiler` emits `System.out.flush()` before `main`'s `RETURN`, gated
  on the source naming `write-byte`/`write-sequence`; **any new path to `_writeByte`'s
  standard-output branch must join that gate** or its output truncates silently. The interpreter's
  twin is `out.flush()` at the end of `RontoLispCli.interpret`.
- **A raw octet moves the standard-output COLUMN like a character does** on all four backends
  (`LINE_START_ADDR`, `_col = b ^ 10`, `atLineStart`); without it `(write-byte 10 t)` + `fresh-line`
  emitted a second newline on three backends and none on the interpreter (ci-spec
  `binary-standard-output`).
- `--component`: a NON-async component reads fd 0 / writes fd 1 through the preview1 adapter; in an
  ASYNC one `%stdin-read-byte-or-raw-f` is a raw passthrough, so octets come from the adapter's
  stdin, not the chunk buffer. Documented limit: an async program reading stdin BOTH as bytes and as
  lines/characters holds two host stdin streams with implementation-specific interleaving.
  **Trigger**: if a pending BYTE read must suspend, route that nil designator to
  `%stdin-read-byte-f`.
- `read-sequence`/`write-sequence` are shared macro expansions into a `while` loop over
  `aref`/`%aset`/`length` with fixed `__rseq_`/`__wseq_` temp names, so no per-backend codegen
  exists for the loop. The keyword tail is read the way a lambda list reads one (CLHS 3.4.1.4,
  `parseSequenceArgs` over `keywordTailProblem`): the first `:start`/`:end` counts, a true
  `:allow-other-keys` admits other indicators, an unknown one without it is a `program-error`
  the CALL signals, and a non-symbol indicator a call-time "unsupported" -- never an
  expansion-time refusal, which lost the whole enclosing form (ANSI `streams` 2026-09-22:
  8 forms, `READ-`/`WRITE-SEQUENCE.STRING.8-.12` and `.ERROR.4 .5 .11 .12`). A packed buffer is first offered to
  `%read-sequence-packed`/`%write-sequence-packed` (raw little-endian, any rank;
  `.kb/binary-sequence-io.md`), a character buffer one `or` further along to
  `%read-sequence-chars` (a block of storage units per host read; `.kb/character-sequence-io.md`).
  A LIST is a sequence too: the element loop stores through `rplaca`/`nthcdr` and fetches
  through `nth` when the buffer is a cons (a byte-only site, a proven vector, has no list arm).
  **The stream picks the element** -- see the section of that name below.
- **A provably byte-only buffer skips the dispatch** (`compiler/SequenceIoNarrowing`,
  `.todo/338`): a sequence with `ArgumentShapes` `VECTOR` shape -- a numeric-typed or untyped
  `make-array`, a `(vector ...)`, a `subseq`/`copy-seq` preserving one, directly or through a
  `let`/`let*` binding with no rebinding, capture, shadowing or dynamic scope in between -- expands
  through the byte-only lowering (`expandReadSequence`/`expandWriteSequence` with `byteOnly`),
  which has no `read-char` arm and no `write-string` branch. **When a string stream can reach the
  site** (the program builds stream values and `%character-stream-p` is spliced) an element-type-`t`
  buffer may receive characters, so only a NUMERIC-typed buffer (a literal numeric
  `:element-type`, or a `subseq`/`copy-seq` of one) still narrows. The packed fast path stays
  first, so a packed buffer still moves in one transfer. A parameter, a `setq`'d variable, a
  captured or shadowed one, a special, a character buffer and a `stream-element-type`-derived
  buffer all stand down to the runtime test (ci-spec `read-sequence-into-a-character-buffer`).
  Runs backend-locally after the gate scans (the JVM and wasm-GC compile paths, next to
  `DeadTypeBranchPruner`), never in `CompileFrontend`: the narrowed expansion still attempts the
  packed primitive first, and the gates that emit that runtime key on the unexpanded spelling.
  **It sees only the top-level forms, never a defun** -- the spliced `%character-stream-p` and
  `%wide-width` included -- so both facts come from the backend's function table
  (`narrow(forms, characterStreams, wide)`). Reading `wide` off the forms made it always false,
  and a narrowed octet buffer read from an `(unsigned-byte 16)` stream answered its raw octets
  `(1 0)` on the three compile paths where the interpreter answers `(1 2)`
  (`JvmLispCompilerTest#compileAndRunANarrowedSiteKeepsTheWideGuard`, its WASM twin).
  Measured 2026-09-09: a byte-only read loop 6,744 -> 5,735 B (`-1,009`), the zlib
  `--optimize=size` row 125,738 -> 125,081 (`-657`, the `FUNC_READ_CHAR` the todo estimated at
  649). The same two on 2026-09-22 (the code has moved since): 7,126 B, unchanged by the stream
  rule below; zlib `--optimize=size` 80,863 -> 80,962 (`+99`).
- The `_eval` interpreters know none of this, nor `require`/`provide` (a file read by the runtime
  `load` of compiled output must not contain them — `.kb/load-inliner.md`). The `CiSpecE2eTest`
  driver passes `--dir . --dir /tmp` to both wasmtime invocations.

## The stream picks the element

`.todo/920`. CLHS: `read-sequence` / `write-sequence` move elements of the STREAM's element type.
The rule the expansions implement, on all four backends:

- a STRING buffer moves characters on any stream (unchanged: `(stringp seq)`);
- any other buffer -- a general vector, a fill-pointer vector, a LIST -- moves characters on a
  STRING stream (`%character-stream-p`: the open stream value's kind slot is `:STRING-INPUT` or
  `:STRING-OUTPUT`), bytes on every other stream.

Answers checked against sbcl 2026-09-22. It replaces "**the BUFFER, not the stream, picks the
element**", under which a general vector or a list read from a string stream signalled
`READ-BYTE expects a binary input stream` on the interpreter and the JVM and read GARBAGE on
wasm (`(0 #(0 0 0))`, `(3 #(108 108 108))` -- the string-stream record's bytes).

- **One prelude defun, not an inline test.** `LispPreludeLibrary` `%character-stream-p`, selected
  (synthesized entry, `LibraryDefunPruner.SYNTHESIZED_ENTRIES`) when the program names
  `read-sequence`/`write-sequence` (or the `%read-sequence-raw`/`%write-sequence-raw` aliases) AND
  can build a stream value (`mayCreateStreamValues`). The expansion calls it only when it is
  spliced and the backend builds stream values (`JvmExprCompiler`/`WasmExprCompiler`
  `characterStreams`); otherwise every stream is a bivalent standard stream, the buffer alone
  decides, and the expansion is the one it always was. The interpreter always asks (it loads the
  defun on first resolution), and keeps the stream VALUE for it: `evalSequenceWithGrayDispatch`
  quotes `synonymTarget`, not the unwrapped handle. So do gray.lisp's
  `%gray-read-sequence-dispatch` / `%gray-write-sequence-dispatch`, through which a program
  using the Gray protocol reaches EVERY site: they test the resolved target for an instance but
  hand the built-in the stream as given (handing it the handle made the ci-spec corpus red on the
  JVM, `JvmLispCompilerTest#compileAndRunAStringStreamPicksTheElementThroughTheGrayDispatchers`).
  Inlined per site the kind test cost ~400 B
  of wasm and ~1 KB of JVM bytecode a site (gguf +3.0 KB, geom +4.0 KB wasm); out of line
  +1.4 / +1.5 KB.
- **A standard stream keeps the buffer rule.** It is bivalent (sbcl answers characters for a
  general vector from `*standard-input*` and bytes for an octet vector); keeping the buffer rule
  there left every stdin/stdout program's behaviour, and its bytes, where they were.
- **A FILE stream keeps the buffer rule -- measured, not chosen.** Telling a character file from a
  binary one needs the element-type registry (next section), which is spliced only for a program
  that asks `stream-element-type` or opens a wide stream. The first cut spliced it for every
  program naming `read-sequence`/`write-sequence` and `open`: a binary loader with a parameter
  buffer grew +3.2 KB wasm / +4.5 KB JVM (registry +1.7 / +3.6 KB, the rest the inline test), the
  gguf corpus case +7.0 KB, geom +9.5 KB -- for ZERO ANSI tests (every failing test in the chapter
  reads a string stream). The cheaper shape -- a distinct kind keyword for a binary file stream,
  read by `%character-stream-p` -- was measured 2026-09-23 (`.todo/931`) and likewise declined:
  the kind rides in the value so a COMPUTED `:element-type` open can set it (the dispatch is
  already onto literal leaves; a computed-options probe emitted both kinds, one per leaf), but
  every program opening a binary file still changes bytes (two binary leaves: +7 B JVM / +4 B
  P1, the longer keyword alone), every `read-sequence`/`write-sequence` program naming a stream
  value changes (`%character-stream-p`'s third arm: +33 B JVM / +18 B P1), and every
  `typep 'file-stream` / direction-predicate site over a file stream changes (second kind arm:
  +510 B JVM / +47 B P1, the direction arm duplicating the assoc read) -- corpus-wide byte
  churn, again for zero ANSI tests. The interpreter (signals `READ-BYTE expects a binary input
  stream`) and the WASM bulk octet path underneath would need their own arms on top.
  **Trigger**: an ANSI test, or any corpus program, reading a general vector or a list from a
  character FILE stream.
- The first-class `#'read-sequence` / `#'write-sequence` / `#'write-string` wrappers
  (`BuiltinFunctionWrappers.boundedSequenceIo`) now expand ONE call with `:end (getf kw :end)` --
  a nil `:end` is the whole sequence -- instead of two copies of the inline expansion.
- **The argument check** (`.todo/932`): every `read-sequence` / `write-sequence` expansion runs
  one prelude call, `%check-sequence-bounds`, before its packed / chars / element arms, on all
  four backends at once -- a dotted-list buffer, a negative, non-integer or symbolic bound, and
  a range outside the buffer are `type-error`s, as in SBCL. The defun measures its own length
  (`stringp` -> `length`, rank-1 `arrayp` -> `length`, else `list-length`, which signals
  `type-error` on a dotted or non-list buffer itself) and signals static-message `type-error`s.
  One defun, not an inline test, for the same per-site reason as `%character-stream-p`; the
  Gray dispatchers (`gray.lisp`, and the interpreter's `evalSequenceWithGrayDispatch` branch,
  which bypasses the shared expansion) call the same defun. `constructsInstance` counts the two
  operators (and their raw aliases, and the `#'` spellings, which are reference-gated for the
  same reason), and wasm's layout scan keeps the `type-error` tag on their presence -- the
  `FILE_ERROR_SITES` situation. The hold-side gate needs nothing: the check constructs only
  to throw.

Cost and identity (2026-09-22, JVM / Preview 1 / component bytes, default optimize, over 778
programs: every ci-spec case, every non-GUI example, the size-report corpus): 1,965 artifacts
byte-identical, 224 differ -- 166 SMALLER (the wrapper change, up to -4.1 KB JVM on a program
carrying the builtin wrapper table), 58 larger, all naming `read-sequence`/`write-sequence`; the
largest `read-sequence-and-write-sequence-round-trip` +8.6 / +3.7 / +3.7 KB (untyped buffers that
no longer narrow), the packed-buffer case +5.3 / +2.6 / +2.6 KB, gguf +3.2 / +1.4 / +1.4 KB, zlib
+480 / +117 / +117 B. The Gray dispatcher change on top: of the 10 corpus programs that use the
Gray protocol, one (`http-buffered-body-stream`) changes, at the same size.

ANSI `streams` (interpreter, suite `ca06bd9`), 2026-09-22: the keyword tail (above) 789 -> 797
tests counted, lost forms 24 -> 16, 530 -> 545 passing (15 fixed: `READ-`/`WRITE-SEQUENCE.STRING.8-.12`,
`.ERROR.4 .5 .11 .12`, `WRITE-SEQUENCE.BV.6`); then the stream rule 545 -> 582 (68.4% -> 73.0%),
37 fixed, 0 regressed: `READ-SEQUENCE.LIST.1-.5 .7`, `.VECTOR.1-.5 .7`, `.FILL-VECTOR.1-.6 .8`,
`WRITE-SEQUENCE.LIST.1-.5 .7`, `.SIMPLE-VECTOR.1-.5 .7`, `.FILL-VECTOR.1-.5 .7`.
`READ-SEQUENCE.ERROR.7` (a dotted-list buffer must signal `type-error`) moved from error to fail.

Pinned by `LispEvaluatorTest#readAndWriteSequenceMoveTheElementTheStreamCarries`,
`#readAndWriteSequenceReadTheirKeywordTailTheWayALambdaListDoes`, their JVM twins,
`WasmLispCompilerIntegrationTest#readAndWriteSequenceMoveTheElementTheStreamCarriesOnPreview1` /
`#componentReadAndWriteSequenceMoveTheElementTheStreamCarries`, `SequenceIoNarrowingTest`, ci-spec
`read-and-write-sequence-move-the-element-a-string-stream-carries`. The argument check by
`LispEvaluatorTest#readAndWriteSequenceSignalTypeErrorForABadSequenceOrBound` /
`#readSequenceOnAGrayStreamValidatesItsBoundsToo`, their JVM twins, the Preview 1 and component
twins of the first, `SequenceBoundsFixture`, and ci-spec
`read-and-write-sequence-signal-type-error-for-a-bad-sequence-or-bound` -- the shapes
`READ-SEQUENCE.ERROR.7 .8 .10` and `WRITE-SEQUENCE.ERROR.3-.10` failed on.

Cost of the argument check (2026-09-23, JVM / Preview 1 / component bytes, default optimize
unless noted; base = the same tree with the check stashed): the cost is almost entirely a FIXED
per-program one -- the `type-error` signal machinery plus the defun and its callees -- into
programs that never otherwise create a condition. A one-site micro program: +28.4 / +19.3 KB;
the ci-spec round-trip shape +24.7 / +19.0 / +19.2 KB, the packed shape +25.9 / +20.2 / +20.4 KB,
the character-buffer shape +28.0 / +19.0 / +19.2 KB; `hello` unchanged. The same program with a
pre-existing `type-error` signal elsewhere pays only the defun: +3.6 / +1.3 KB, and each further
site ~+0.1 KB. Real programs already carry the machinery: `checkpoint-tokenizer` +478 B JVM /
+335 B P1 (`--optimize`), `llm` -804 B P1 (tree-shake noise), `zlib` +4.9 KB JVM / +3.1 KB P1.
No narrower gate exists without losing the guarantee -- any call can receive a bad sequence or
bound at runtime -- so the numbers above are the accepted shape, not a problem to fix.

Two follow-ups the full suite caught on 2026-09-23 (the check as first written broke five
suites: the direct-compile `binaryStandardStreamDesignators` on JVM and Preview 1, and the three
quantized-matrix transfers). First, the call runs only where the defun is spliced: a direct
backend compile of reader output (a backend unit test without the front end) never spliced it,
so the expansion keeps the `__rseq_chk` / `__wseq_chk` binding only when
`%check-sequence-bounds` is in the function table -- the `%character-stream-p` shape, absent
rather than dangling. Every front-end program splices it, so the four backends still check as
one. Second, a quantized-matrix buffer is neither `stringp` nor `arrayp` and fell into
`list-length`: the defun now answers it a nil length, like a circular list's -- its transfers
move block bytes (`:start`/`:end` count bytes) no Lisp-level reader measures, so the range
check stays the transfer arm's while a bad bound type still signals `type-error` here.

## Element types wider and narrower than one octet

`.todo/919`. An integer `:element-type` opens a binary stream whose ELEMENT is a fixed number of
little-endian octets, two's complement when the type admits negatives; `stream-element-type`
answers the widened type; `file-length` / `file-position` count elements. One classification,
`macro/StreamElementType` (over `macro/IntegerTypeRange`), read by all four backends.

**Measured against sbcl 2026-09-22, and it overturned the plan's premise.** The todo assumed sbcl
PACKS a sub-octet type (N <= 8 several per octet) and biases `(integer lo hi)`. It does neither:
a type needing b bits takes 1 / 2 / 4 / 8 octets for b <= 8 / 16 / 32 / 64 and `ceil(b/8)` beyond
(`(unsigned-byte 100)` -> 13 octets, answered as `(unsigned-byte 104)`); `(unsigned-byte 1)` and
`(integer 100 200)` both write the raw value in one octet; `bit`, `unsigned-byte` and `(mod n)` are
`(unsigned-byte 8)`, `signed-byte` is `(signed-byte 8)`, an `(or ...)` of integer types takes its
hull. `file-length` / `file-position` are in elements (3 two-octet elements -> 3). So no
bit-packing stream exists anywhere: every backend's descriptor keeps moving octets and the element
is composed above it. sbcl signals on `stream-element-type` of a CLOSED stream; ours answers
`character` on all four, because close forgets the entry (below).

- **Interpreter** (`Environment`): `open` classifies the EVALUATED type and records it per handle
  (`streamElementTypes`, removed by `close`, in step with the compile paths). `read-byte`,
  `write-byte`, `file-length`, `file-position` and the two packed sequence primitives are
  re-defined as wrappers over the octet built-ins: a wide handle composes / scales / declines, any
  other passes straight through. `stream-element-type` reads the table.
- **Compile paths: a REGISTRY in prelude Lisp, not a runtime mode.** No backend learned a width.
  `LispPreludeLibrary` entries `%file-stream-entry` / `%file-stream-register` /
  `%file-stream-element-type` / `%file-stream-forget` (a hash table keyed by the HANDLE, entry =
  `(octets signed spec)`) and `%wide-width` /
  `%wide-read-byte` / `%wide-write-byte` / `%wide-elements` / `%wide-position-octets`. The
  backends (`Jvm/WasmExprCompiler`) lower ONLY when the entry is spliced (`ctx.functions`): a
  literal binary `open` leaf becomes `(%file-stream-register (%obj-new '%STREAM <checked open>
  :FILE) n signed 'spec)` (`LispMacroExpander.registeredOpen`), `read-byte` / `write-byte`
  (and the component socket aliases `%read-byte-raw` / `%write-byte-raw`) call the wide helpers,
  every `close` (and `%close-raw`) forgets the entry first (`LispMacroExpander.forgettingClose`),
  `file-length` / `file-position` go through the scaling call-site shapes
  (`expandWideFileLength` / `expandWideFilePosition`, after `rewriteFilePositionArg`), and the
  packed arm of every `read-sequence` / `write-sequence` expansion -- the backends' own and
  `SequenceIoNarrowing`'s -- is guarded by `%wide-width` (`guardPackedSequenceForWideStreams`),
  because the packed primitive moves raw octets. The helpers reach the primitive through four
  internal names the lowering does not rewrite again: `%read-octet`, `%write-octet`,
  `%file-octet-length`, `%file-octet-position`. **Every name-keyed runtime gate still sees the
  public name** (the JVM `FileMeta` / stdout-flush gates, the WASM `file-position` import), because
  a raw call appears only in the lowering of a call the source wrote.
- **Keyed by the handle, forgotten at close -- both measured, not chosen.** The first cut keyed
  entries by handle AND checked the stream VALUE with `eq` (to survive a reused WASM descriptor
  without a close hook). It went red in the ci-spec E2E corpus only: a program that uses the Gray
  protocol reaches every built-in through gray.lisp's dispatchers, which resolve `%stream-target`
  FIRST and pass the raw handle -- no value to compare. So the lookup takes the handle alone, and
  a stale entry on a reused descriptor is prevented where it arises: close. Closing a SYNONYM
  forgets nothing (its target stays open); a raw handle, which the Gray close dispatcher can pass,
  forgets.
- **Two selection facts, both SURFACE** (`referencedBySurfaceForm`, `LibraryDefunPruner`'s
  synthesized list): the wide half on `LispMacroExpander.opensWideElementStream` (a literal wide
  `:element-type` on an `open` / `with-open-file`); the registry also on "names
  `stream-element-type` AND names `open` / `with-open-file`" -- which is what makes an octet
  stream answer `(unsigned-byte 8)` instead of the old `CHARACTER` constant. A wide leaf compiled
  without the registry (a pipeline that skipped prelude selection) signals at call time
  (`wideElementTypeUnavailableStub`) rather than open octets.
- **Literal-only on the compile paths, by the `:io` trade.** A computed wide type would put every
  uiop wrapper and `#'open` behind the helpers. `uiop:with-temporary-file` opens through a computed
  element type, so a wide literal there is refused at call time too. Measured 2026-09-23
  (`.todo/926`): forcing the wide helpers + the registry into computed-`:element-type`
  programs costs +17.0K JVM / +14.4K Preview 1 on the `computed-stream-options` corpus program
  (which passes only `'character` and `(unsigned-byte 8)` down) and +9.3K / +6.8K on an
  `(apply #'open ...)` wrapper -- and that is WITHOUT the runtime twin of
  `macro/StreamElementType.of` the lowering would also splice (the `covering` interval logic
  over every integer spelling, plus the per-site binary-or-not dispatch growth). Four ANSI
  tests want it (`FILE-POSITION.7` `.8`, `OPEN.65`, `MAKE-TWO-WAY-STREAM.13`); no corpus caller
  passes a wide type computed. Declined like the computed `:io` before it.
  **Trigger**: a real caller that computes a wide `:element-type`.
- **An octet spelling emits exactly `'(unsigned-byte 8)`** (`elementTypeLiteral` writes the widened
  spec), so `bit` / `(unsigned-byte 3)` / `(integer 0 200)` compile to the class the octet always
  did (`JvmLispCompilerTest#theOctetElementTypeSpellingsCompileToTheSameBytesAsUnsignedByte8`).

Cost and identity (2026-09-22, bytes JVM / Preview 1 / component, default optimize): every program
without a wide literal and without the `stream-element-type` + `open` pair is byte-identical --
measured over the 94 ci-spec cases and 33 examples that name a stream/byte/subtypep operator and
the size-report corpus, 357 artifacts. A two-line ub8 write + read 38,994 / 19,735 / 25,360; the
same at `(unsigned-byte 16)` 47,613 / 27,709 / 33,353 (+8.6K / +8.0K / +8.0K, the helpers); a
`stream-element-type` of an ub8 file stream 9,128 / 5,556 / 10,516 -> 12,871 / 9,455 / 14,455 (the
registry).

ANSI `streams` (interpreter, suite `ca06bd9`), 2026-09-22: the call-time `with-open-file` refusal
(commit 1) 758 -> 789 tests counted, lost forms 55 -> 24, passes 447 unchanged; then 447 -> 530 of
789 (56.7% -> 67.2%), 83 fixed, 0 regressed -- `OPEN.4 .29-.58 .61 .62`, `OPEN.OUTPUT.5-.19`,
`OPEN.IO.5-.19`, `OPEN.PROBE.25-.27 .29-.36`, `FILE-LENGTH.2-.5`, `READ-BYTE.3 .4`,
`STREAM-ELEMENT-TYPE.2-.4` (the integer-interval `subtypep` rule in
[declarations-type-checks.md](declarations-type-checks.md) is what their `(subtypep etype
(stream-element-type s))` assertions needed). Still failing and this area's: `FILE-POSITION.7
.8`, `OPEN.65`, `MAKE-TWO-WAY-STREAM.13` -- a COMPUTED wide type in `with-open-file`, refused on
every backend by the literal-only rule above.

Pinned by `LispEvaluatorTest#wideAndNarrowElementTypesRoundTripTheWaySbclStoresThem`,
`#aClosedFileStreamForgetsItsElementTypeButClosingASynonymDoesNot`,
`JvmLispCompilerTest#compileAndRunWideAndNarrowElementTypes` /
`#compileAndRunStreamElementTypeOfAnOctetFileStream` / `#compileAndRunWideElementTypesThroughTheGrayDispatchers`,
`WasmLispCompilerIntegrationTest#wideAndNarrowElementTypesOnPreview1` /
`#componentWideAndNarrowElementTypes` / `#streamElementTypeOfAnOctetFileStreamOnPreview1`,
`StreamElementTypeTest`, ci-spec `wide-and-narrow-stream-element-types`.

## Component stdin (stdin.lisp over wit-imported `wasi:cli/stdin@0.3.0`)
On `--component`, an ASYNC program that reads stdin (a read referenced + an async form referenced +
not serve mode) gets `stdin.lisp` + `stdin-dispatch.lisp` spliced by `eval/StdinLibrary` (right after
`SocketsLibrary`). The interface is bound FROM the fixed import block
(`WasmComponentBuilder.FIXED_BLOCK_IFACES`; `validateFixedMembers` admits async type-alias built-ins
while drops/task-returns stay rejected), so the emitted WIT world is unchanged and no new `-S` flag
exists.

Mechanics = the preview1 adapter's stdin cache in Lisp: ONE `read-via-stream` stream cached in a
defvar (its result future dropped immediately; EOF is the stream status), chunk buffer + cursor + eof
defvars, and `%stdin-read-line-f`/`-read-char-f` async-defuns so `WasmSocketsRewrite`'s promotion
makes a pending stdin read SUSPEND the task. EOF parity: `read-line` -> nil; the 0/1-arg `read-char`
signals `(error 'end-of-file)` — the CLASS the native lowering signals
(`LispMacroExpander.endOfFileSignal`), not a look-alike message.

**A NON-async stdin program is deliberately NOT migrated**: it keeps the adapter's `fd_read` branch,
so its component is byte-identical (`componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags`). When
sockets.lisp is spliced, `StdinLibrary` supplies only the or-raw helpers' backing (real `stdin.lisp`,
or `stdin-stub.lisp` raw passthroughs under serve — the wasi:http service world has no stdin).
Documented limits: reads buffer one host chunk; a migrated program that ALSO consumes stdin through
forms the rewrite leaves native holds TWO host stdin streams.

## The stream table is CONCURRENT on the interpreter and the JVM
One allocator, and it is atomic. `http-handler`/`serve` put one virtual thread per request on both
backends (`.kb/mutexes.md`) and the table is process-wide. The old "reserve `count`, then store" shape
handed two concurrent requests the SAME handle: one stream leaked and both Lisp handles denoted the
survivor, so two conversations interleaved on one connection (against PostgreSQL this read as random
connection loss inside the trust-auth handshake).

- Interpreter (`Environment.createGlobal`): a `ConcurrentHashMap` plus an `AtomicLong`. Never put a
  `null` value in it — `close` REMOVES instead of nulling.
- JVM: every producer (`_open`, `_makeStringOutputStream`, `_makeStringInputStream`, every socket
  constructor) appends through the ONE allocator `_addStream(Object) -> Long`
  (`ACC_SYNCHRONIZED`), so **a new stream-producing built-in MUST call it** rather than grow its own
  reserve/store pair. `_closeStream` is synchronized with it and `_streams` is `ACC_VOLATILE`.
  Producers build the stream BEFORE calling the allocator, so a connect or file open stays outside
  the lock.
- WASM: both backends are single-threaded by construction, so nothing to do.

Wider rule `.kb/concurrent-served-requests.md`. Pinned by
`HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` and its `HttpHandlerJvmTest` twin
(fixture `StreamHandleConcurrencySupport`: 24 simultaneous requests x 3 rounds).

## `file-length`, `file-write-date`, and the three write-side operators
**`file-length` is REAL on ALL FOUR backends, and `nil` only where a stream genuinely has no length.**
A `Reader`/`Writer` does not remember its path, so interpreter and JVM keep a side table
(`Map<Long, String> streamPaths` / `Object[] _streamPaths` written by `_setStreamPath`, cleared by
close), and `_fileLength` runs the handle through `_forceOutput` first so an output stream's answer
counts what was WRITTEN. Only `open` fills the table, so every other stream kind is nil.

**The two WASM backends need no side table**: a stream value there IS its WASI descriptor, so
`_file_length` (`FUNC_FILE_LENGTH` after `FUNC_EQUALP_KEY`, called by `WasmFileLengthCompiler`) stats
the fd through the TWELFTH preview1 import, `fd_filestat_get`. Nil for exactly the set the other two
give: a non-i31 designator, a NEGATIVE handle, a handle below `StreamDesignators.FIRST_USER_HANDLE`,
a non-zero errno, and any `filetype` that is not `regular_file`. No flush first.

**Trap: the 64-byte `filestat` staged at `HEAP_PTR` must be rounded UP to 8 first** — preview1's
`filestat` has u64 fields, so wasmtime REFUSES an unaligned buffer (`Pointer not aligned to 8`)
instead of writing it. The pointer is advanced over for the call and popped after (the `_open`
discipline, load-bearing under `--component` because the adapter allocates through `cabi_realloc` at
that cell). `adapter.wat`'s `$fd_filestat_get` re-encodes the preview1 `filestat` from a SYNC-lowered
`descriptor.stat` (`result<descriptor-stat, error-code>` at `0x51600`: disc @0, `type` @8,
`link-count` @24, `size` @32); dev/ino and timestamps are zero-filled.

**`file-write-date` still answers nil on both WASM backends** — it names a PATH, not an open stream,
and the adapter zero-fills the timestamps. **Trigger**: lifting `data-modification-timestamp` out of
`descriptor-stat` plus a path-stat runtime beside `_probe_file` is the whole remaining change.

The JVM side is GATED per operator (`JvmIoRuntimeBuilder.FileMeta`); `#'file-length` /
`#'file-write-date` are in `REFERENCE_GATED_FUNCTIONS` because their wrapper bodies call those
helpers and the gate scans the SOURCE program. The WASM side is not gated — `--optimize` drops the
body and the import when unused.

**Three write-side operators share one shape: prelude Lisp over one primitive.** CL's extra values are
not returned — a prelude defun's secondary value does not cross the function boundary on the compile
paths (`.todo/212`).

- `ensure-directories-exist` over `%make-directories` (the "directory component is everything up to
  and including the last slash" rule has one definition in `LispPreludeLibrary`): interpreter/JVM
  `Files.createDirectories`/`File.mkdirs`, both WASM backends `_make_directories`
  (`FUNC_MAKE_DIRECTORIES` after `FUNC_C_SIGNUM`, called by `WasmMakeDirectoriesCompiler`)
  over the THIRTEENTH preview1 import, `path_create_directory`. Preview 1 creates ONE
  level per call, so the body walks the slash-separated prefixes and creates each --
  the recursive answer the other two give. **Answers nil rather than signalling** (the
  `%delete-file` / `%rename-file` shape below, since `.todo/900`: before that the
  interpreter and WASM signalled a bare `SIMPLE-ERROR` at the primitive and the JVM
  ignored `mkdirs`' result outright and answered success for a directory it never made).
  A nonzero final WASM errno is VERIFIED by opening the path as a directory, which turns
  "already there" into T whatever errno the host used; the JVM re-checks with
  `File.isDirectory()` after `mkdirs()` for the same reason (`mkdirs` answers false both
  for "already there" and for "refused", so the boolean alone cannot tell them apart).
- `delete-file` over `%delete-file`, which answers nil rather than signalling when the file is absent
  or the host refused, so "a missing file is an error" lives once in the Lisp above it -- a
  `file-error` through `%file-error`, as are `rename-file`'s, `truename`'s and, since
  `.todo/900`, `ensure-directories-exist`'s. Both WASM backends unlink for real now
  (`_delete_file` over the FOURTEENTH preview1 import, `path_unlink_file`, called by
  `WasmDeleteFileCompiler`). mito's `generate-migrations` deletes superseded migration
  files on all four. Removing a DIRECTORY still signals: unlink cannot rmdir (above).
- `rename-file` over `%rename-file` (same nil-not-signal rule); the new name is MERGED with the old
  one, so a bare file name keeps the directory. Both WASM backends move for real
  (`_rename_file` over the FIFTEENTH preview1 import, `path_rename` -- the one new
  six-`i32` type, `TYPE_PATH_RENAME` -- called by `WasmRenameFileCompiler`; both paths
  stage and resolve against the preopen table on their own).
- **Import surface**: `IMPORT_FUNC_COUNT` went 12 -> 15 and `FUNC_START` with it, so
  every emitted WASM function index shifted (the `fd_readdir` precedent). In step:
  `--no-wasi` defines three more errno stubs (same type indexes as the imports);
  `adapter.wat` implements all three over `wasi:filesystem@0.3.0`
  (`create-directory-at` / `unlink-file-at` / `rename-at`, SYNC-lowered like `open-at`,
  sharing its `0x50050` result cell); `adapter-http-server-p1.wat` exports them as
  errno 76 -- the serve world has no filesystem, so `%make-directories`/`%delete-file`/
  `%rename-file` all read a nonzero errno as nil there, and the Lisp callers above them
  signal identically.

**`uiop:read-file-string` must NOT size its buffer from `file-length`**: prelude Lisp over
`with-open-file` + a CHUNKED `read-sequence` loop, both properties load-bearing. **The loop stops on
the first SHORT read, so EOF is read at most ONCE** — a SECOND read past EOF traps on the
`--component` backend alone, because `adapter.wat`'s `$fd_read` calls `stream.read` after the
writable end dropped (`cannot read after being notified that the writable end dropped`). Any slurp
loop hits it. **Trigger**: the adapter answering 0 bytes/EOF idempotently.

Pinned by `LispEvaluatorTest#evalFileWriteDateAndFileLength`/`#fileLengthOverEveryStreamKind`,
`#renameFileMovesTheFileAndSignalsWhenItIsNotThere`, their JVM twins,
`WasmLispCompilerIntegrationTest#fileWriteDateAnswersNilAndFilesystemWritesRunForReal`/
`#fileLengthAnswersTheSizeOfARealFile`/`#componentFileLength`/
`#uiopFilesystemProbeReadsAndMutations`, ci-spec
`file-length-of-a-file-of-a-known-size` and
`filesystem-write-create-rename-delete-and-probe`.

## `file-position` is REAL on ALL FOUR backends for every FILE stream

Binary, character and bidirectional alike: the query answers the offset and the set moves
it, so a caller can seek and read the sought bytes rather than walk front to back. A
binary stream counts in elements (next-but-one section), a character stream in BYTES --
sbcl's answer, measured 2026-09-22: a character advances it by its UTF-8 length, a line
`read-line` returned ends after its terminator (both bytes of a CRLF, although the line
itself drops the CR), a character `peek-char` looked at or `unread-char` pushed back is
not consumed yet, and an `:if-exists :append` stream starts at the end of the file (a
binary one too). A socket, a standard stream and a closed handle answer `nil` (Common
Lisp's "cannot be determined"); a STRING stream answers its character position
(`.todo/929`, section "String streams"). The served-request body keeps its own REAL
`file-position` through `HttpRequestBodyStream` on all four.

- **Interpreter.** A binary stream: the per-handle `streamPositions` map (`Environment`),
  advanced by the byte primitives through `merge` and repositioned by `binaryFileStreamSet`
  (re-open at the offset). A character stream: `open` builds `runtime/RontoCharFileReader`
  / `RontoCharFileWriter` for EVERY character file (no gate -- an interpreter has no size),
  and `file-position` asks them. A `:io` / `:overwrite` stream: `RontoIoFileStream`
  (section after next). Then two wrappers: the wide element types scale, and a character
  parked in the `unread-char` cell is subtracted (the query) or dropped (the set).
- **JVM.** Binary: the mirrored `Object[] _streamPositions` side table, advanced by
  `_bumpStreamPosition` (called by `_readByte`, `_writeByte`, `_readSeqPacked`,
  `_writeSeqPacked`), queried/set through `_filePosition`, which re-opens via
  `FileChannel.position`. Character: `_open`'s modes 0/1/5 construct the SAME two runtime
  classes the interpreter runs, and `_filePosition` has one own-cursor arm each
  (`emitOwnCursorArm`, shared with the `:io` arm's shape). Gated on
  `JvmIoRuntimeBuilder.FileMeta`: `position` for the side table and the binary arms,
  `characterPosition` (= names `file-position` AND `LispMacroExpander.mayOpenCharacterFileStream`)
  for the two classes, which TRAVEL (`CHAR_FILE_RUNTIME_CLASS_FILES`). The `#'file-position`
  wrapper is `REFERENCE_GATED` like `#'file-length`.
- **The two classes carry the whole mechanism**: each extends `BufferedReader` /
  `BufferedWriter`, so every existing dispatch (`read-line`, `read-char`, `peek-char`'s
  `mark`/`reset`, `listen`'s `ready`, `read-sequence`'s block read, the print family, the
  end-of-program flush, `close`) takes them with no arm, but never uses the superclass's
  buffer: each decodes / encodes UTF-8 over its OWN byte buffer, so the position is the
  channel's less (reader) or plus (writer) what is buffered. There is no per-character
  counter anywhere. A `BufferedReader` over an `InputStreamReader` cannot answer this: the
  decoder reads ahead and neither layer says how many bytes the characters handed out took,
  and `readLine` hides whether the terminator was `\n` or `\r\n`. The reader consumes a CRLF
  WHOLE (it looks at the byte after a CR), decodes a malformed sequence to U+FFFD over its
  valid prefix, and drops its mark past the limit a `mark(n)` asked for (else a `peek-char`
  would pin every later byte in the buffer); the writer encodes an unpaired surrogate as
  `?` -- `FileReader` / `FileWriter`'s replacements.
- **Both WASM backends: the descriptor offset IS the position**, through ONE injected
  import each -- gated on the program naming `file-position`, so a program that does not is
  byte-identical to a build that never knew about the feature ([[wasm-import]], "This, not
  a new index-pinned preview1 slot"). `WasmFilePositionCompiler` calls the
  `_file_position` / `_file_position_set` pair (`FUNC_FILE_POSITION` after
  `FUNC_FILE_LENGTH`), which stage 8 bytes at `HEAP_PTR` with the `_open` advance-then-pop
  discipline and answer `nil` for what `_file_length` answers `nil` for, plus a non-zero
  errno. Nothing buffers ahead of it: `_read_line` / `_read_char` read ONE BYTE per
  `fd_read`, writes go straight through, and `read-sequence`'s bulk character path asks
  for at most one byte per character still wanted and completes a split sequence with
  exactly its missing bytes (`WasmCharIoRuntimeBuilder`). Two corrections remain: the
  query subtracts the UTF-8 length of a code point `peek-char` parked on this fd
  (`PEEK_FD_ADDR` = fd + 1) and the set drops it; and an APPENDING open moves the
  descriptor to the end at the `open` call site (`WasmOpenCompiler`:
  `_file_position_set(fd, _file_length(fd))`), because an append descriptor sits at 0
  until its first write.
- **Preview 1** (`.todo/876`) has a real moveable cursor, so ONE appended
  `wasi_snapshot_preview1.fd_seek` serves both directions: `(fd, 0, cur)` reads the
  position, `(fd, n, set)` moves it. **The read-mode `path_open` asks for `FD_READ`
  alone and `fd_seek` still works** -- wasmtime's preview1 does not enforce rights (the
  `fd_filestat_get` behind `file-length` has been riding the same fd for longer); a host
  that did would answer `ENOTCAPABLE`, which reads as `nil`, and the fix would be widening
  the read rights to `FD_READ|FD_SEEK|FD_TELL` = 38.
- **`--component`** (`.todo/877`) has NO cursor -- WASI 0.3 reads are offset-based -- so
  the adapter tracks a per-fd byte offset (advanced by every read and write) and exports
  the `file_position_get` / `file_position_set` pair over it, answering EBADF for a
  descriptor below 100 or a slot that is not live. Serve implies `--component`, so the
  serve bridge sees the same two.
- **`--no-wasi`** has no filesystem, so the operator keeps compiling to the `nil` constant.

**The per-fd "binary" flag table is gone (`.todo/925`).** Both WASM backends used to write
a byte per descriptor at every `open` call site -- Preview 1 into a 1024-byte table the
module reserved at `DATA_BASE_OFFSET`, `--component` into the adapter's page-5 table at
`0x51a00` -- so that `_file_position` could answer `nil` for a character stream. With every
file descriptor's offset real there is nothing to tell apart: the guard, the call-site
writes and the Preview 1 reservation were removed. The adapter's `path_open` still resets
its (now unread) slot; that is left alone because touching `adapter.wat` means
regenerating the adapter blobs for no behavioral change.

**Size, measured 2026-09-22** (bytes JVM / Preview 1 / component, default optimize).
Every program that does not name `file-position` is byte-identical on all three targets --
over every ci-spec case, every example in `examples/examples.yaml` and the size-report
corpus, 707 programs and 2,121 artifacts: 2,092 identical, 6 differing only in the jar's
embedded build timestamp (two cases that print `lisp-implementation-version`), and 23 from
the 9 programs whose COMPILED program names `file-position` -- the 8 ci-spec cases that
call it and `examples/net/httpbin-jzon.lisp`, through jzon's own call (+8.2 KB JVM, the
classes; -510 / +38 B WASM). The WASM side of those moved by the removed flag writes,
mostly down (`wide-and-narrow-stream-element-types` -638 / -429 B). The cost therefore
falls only on a program that names `file-position`: a character write + read with two `file-position` calls 15,832 / 13,067 /
18,681 -> 24,067 / 13,073 / 18,717 -- the JVM's +8,235 is the two travelling classes
(4,788 + 3,267 bytes, the output goes from one class file to three) plus 180 bytes of
`_open` / `_filePosition` arms; a binary-only one 40,870 / 20,236 / 25,957 -> 40,932 /
20,231 / 25,937 (the JVM's `:append` start, and the flag writes the WASM backends no longer
make). That is why the JVM gate is two facts rather than one: a program whose every `open`
spells a literal binary `:element-type` keeps its single class file.

**Follow-up: can the two classes cost less? (`.todo/938`, measured 2026-09-23 -- the finding
is the deliverable, no change forced.)** Re-measured on current code (bytes JVM / Preview 1 /
component, default optimize, output file count beside the bytes): a character write + read
with two `file-position` calls 24,442 B in 3 files (`Prog` 16,387 + `RontoCharFileReader`
4,788 + `RontoCharFileWriter` 3,267 -- the class sizes are unchanged) / 13,602 / 19,246; a
program whose only `file-position` reference is inside a spliced library (`uiop/os:parse-
file-location-info` on a binary file, plus a character `open` elsewhere -- the httpbin-jzon
shape, no network needed) 61,628 B in 3 files / 32,244 / 38,483, reproducing the complaint;
a binary-only `file-position` program 41,100 B in 1 file; `hello` 4,750 / 480 / 1,635 in 1
file. The gate scans post-splice, so a library-internal reference ships the classes -- and
that is load-bearing, not conservatism: the library call takes its stream from ITS caller,
so a user passing a character file stream in gets the real byte offset only through them.
Excluding library references would silently break that caller. The two sharper gates do not
pay either: per-call-site stream dataflow (which `file-position` calls can actually receive
a character FILE stream, as opposed to binary / string / standard / socket ones) is an
analysis the compiler does not have, for at most 8 KB on a handful of programs; folding the
two classes into one is impossible without rewriting every dispatch site that takes them
with no arm today (one extends `BufferedReader`, the other `BufferedWriter`); emitting the
offset tracking as `Prog` methods duplicates ~8 KB of hand-rolled UTF-8/position logic in
generated bytecode while the classes stay in the jar for the interpreter anyway -- net
saving ~0. The ceiling of the one sound cut left (ship reader/writer by open direction) is
one class, 3.3-4.8 KB, on one-directional programs only. Verdict: the cost cannot be cut
without losing the byte-offset answer or taking on disproportionate machinery; the numbers
above stand as the accepted shape.

**Worth it -- decided on both halves, not output only.** The ANSI value is one test
(`FILE-POSITION.5`, output side; `.1`-`.4` need a `file-position.txt` the suite does not
ship), but the output half alone would have left the four backends DISAGREEING on the input
side of the same stream, the cost is paid only by programs that name the operator, and the
input half costs nothing extra on WASM and one class on the JVM. ANSI `streams`
(interpreter, suite `ca06bd9`), 2026-09-22: 599 -> 600 of 797, fixed `FILE-POSITION.5`,
regressed none; `files` and `reader` unchanged (402 / 662 both ways).

Pinned by `LispEvaluatorTest#binaryFileStreamPositionQueriesAndSeeks` /
`#characterFileStreamPositionIsTheByteOffset`,
`JvmLispCompilerTest#compileAndRunBinaryFileStreamPositionQueriesAndSeeks` /
`#compileAndRunCharacterFileStreamPositionIsTheByteOffset`,
`WasmLispCompilerIntegrationTest#filePositionQueriesAndSeeksOnPreview1` /
`#componentFilePositionQueriesAndSeeks` (one `FILE_POSITION_PROGRAM`) /
`#characterFileStreamPositionIsTheByteOffsetOnPreview1` /
`#componentCharacterFileStreamPositionIsTheByteOffset` (the character program is ONE
`CharacterFilePositionFixture`, sbcl's answers), `compileAndRunLiteStreamBuiltins`, the
Gray rewrite case, `JvmRuntimeClassFilesTest`, and ci-spec
`file-position-round-trips-on-a-binary-file-stream` /
`file-position-of-a-character-file-stream-is-its-byte-offset`. **Test trap**: a
compiled test of a program that uses `unread-char` must go through the front end
(`CompileFrontendAccess.withSystemPath`) -- the pushback is a library splice, and the
bare compiler refuses `unread-char` as "not supported as a function value".

## `:direction :io` and `:if-exists :overwrite`: ONE stream kind that owns its cursor

`.todo/918`. The two open modes the existence guard could not express, because each opens
the file DIFFERENTLY: `:io` reads and writes one file through one cursor, `:overwrite`
writes from 0 without truncating. Both are the same stream (an `:overwrite` open is an
`:io` one whose read half goes unused), and `file-position` on it -- query, set, `:start`,
`:end` -- is real whatever its element type. Measured against sbcl 2026-09-20: a fresh `:io`
open answers position 0, element type `CHARACTER`, `input-stream-p` and `output-stream-p`
both `t`; `write-sequence "wxyz"` over `abcdefghij` under `:overwrite` leaves `wxyzefghij`;
a character's position advances by its UTF-8 length (`file-position` after one 3-byte
character is 3).

- **Interpreter and JVM run ONE class**, `runtime/RontoIoFileStream` (a `RandomAccessFile`,
  UTF-8 decoded and encoded by hand so the byte cursor is never ahead of what was consumed).
  It extends `Writer`, so every OUTPUT dispatch (print family, `write-string`,
  `write-line`, `fresh-line`, `force-output`, `close`, the end-of-program flush) takes it
  with no arm; the read, byte and position sides are explicit arms (interpreter:
  `read-line`, `read-char`, `%peek-char`, `read-byte`, `write-byte`, `listen`,
  `file-position`; JVM: `_readLineStream`, `emitIoCharArm` ahead of `emitResolveReader` in
  `_readChar`/`_peekChar`, `_readByte`, `_writeByte`, `_listen`, `_filePosition`, and one
  `IO_BIT|OVERWRITE_BIT` test at the head of `_open`'s mode chain). End of file is
  `ready()`, never a null line: the class cannot spell `@Nullable`. The JVM backend calls
  the class and it TRAVELS (`JvmIoRuntimeBuilder.RUNTIME_CLASS_FILES`, 3,592 bytes).
- **Preview 1** opens ONE descriptor: `WasmOpenCompiler.wasmMode` 0 read / 1 write / 2
  append / 3 overwrite (neither `O_CREAT` nor `O_TRUNC`) / 4-6 the same three for `:io`,
  which adds `FD_READ` to the write rights (102). Reads and writes share the fd cursor and
  `fd_seek` moves it.
- **`--component`** has no cursor, so the adapter writes through
  `descriptor.write-via-stream` AT its tracked per-fd offset (a new `"w"` member
  `file-write`, a `BLOCK_FUNCS` entry and a `core.wat` import; `adapter.wasm` and
  `import-block.bin` regenerated by `regen.sh`, every other blob came out byte-identical).
  Only an fd opened with fdflags APPEND keeps `append-via-stream` (the page-5
  `$append_cell` table at `0x51b00`). After every write the cached readable stream is
  dropped and the EOF latch cleared, as `$file_position_set` does, or the read after a seek
  replays stale bytes. `$path_open` now derives descriptor-flags from the requested RIGHTS
  (FD_READ -> read, FD_WRITE -> write, both -> 3) rather than from oflags, since
  `:overwrite` writes with oflags 0.
- **`file-position`'s `:start` / `:end` are ONE shared call-site rewrite on the compile
  paths** (`LispMacroExpander.rewriteFilePositionArg`, applied by both `Jvm/WasmExprCompiler`
  `file-position` cases); the interpreter's primitive reads the keywords at run time. A
  literal folds (`:start` -> 0, `:end` -> `file-length`); a COMPUTED position is bound and
  resolved at run time, because the Gray streams dispatcher and `#'file-position` pass the
  designator down as a value -- the literal-only first cut threw `ClassCastException`
  (String -> Long) inside `_filePosition` in `JvmClassShakerCorpusTest`, whose corpus
  splices gray.lisp. `:end` introduces a `file-length` call the source never names, so the
  JVM's `FileMeta.fileLength` gate also keys on `filePositionMayNeedLength`.
- **`:io :append` only STARTS the cursor at the end** on the interpreter/JVM (one cursor
  serves the reads too, and `file-position` moves it), where CL's `:append` sends every
  write to the end; the two WASM backends append for real (fdflags APPEND). No test writes
  after seeking an appending `:io` stream.

**Gate, and the measured trade that made computed values literal-only.** The JVM arm, the
travelling class and the WASM `_open` body key on `LispMacroExpander.opensBidirectionally`:
a LITERAL `:direction :io` / `:if-exists :overwrite` on an `open` / `with-open-file`. The
first cut also accepted a COMPUTED `:io` / `:overwrite`, which made every computed spec and
`#'open` open bidirectionally-maybe. Measured 2026-09-22 (bytes, JVM / Preview 1 /
component, `-o` default optimize):

| program | before | computed accepted | literal-only (landed) |
|---|---|---|---|
| literal `with-open-file` write + read | 14,267 / 12,627 / 17,979 | 14,267 / 12,627 / 18,237 | 14,267 / 12,627 / 18,237 |
| options passed as arguments | 18,334 / 15,381 / 20,794 | 23,579 (2 files) / 16,696 / 22,388 | 18,334 / 15,381 / 21,052 |
| `uiop:with-output-file` + `read-file-string` | 45,793 / 31,940 / 37,379 | 50,416 (2 files) / 32,582 / 38,287 | 45,793 / 31,940 / 37,637 |
| binary `file-position` | 11,155 / 6,134 / 10,886 | -- | 11,155 / 6,134 / 11,144 |

The dispatch grows from six literal leaves to fourteen and every uiop file wrapper would
carry the class; nothing observed passes `:io` or `:overwrite` as a computed value. So a
computed one is refused at call time, like any other unsupported value. The +258 bytes on
every component program that writes a file is the adapter's write path -- the price of
positioned writes, not of the gate. **Trigger**: a real caller that computes `:io` /
`:overwrite` -- widen the two `unlessValueIn` sets and the gate together.

ANSI `streams` (interpreter, suite `ca06bd9`), 2026-09-22: 423 -> 447 of 758 (55.8% ->
59.0%), fixed `OPEN.IO.1 .2 .4 .20 .22-.29 .28A .31-.35`, `OPEN.OUTPUT.24`,
`OPEN.ERROR.3 .9 .11 .13 .14`; regressed none. Still failing and NOT this mode's: `OPEN.IO.5
-.19` except 13 (element types beyond `(unsigned-byte 8)`, and `OPEN.IO.13`'s
`stream-element-type` answering `CHARACTER` for a binary stream -- `.todo/919`), `.3` (logical
pathnames), `.21` / `.30` (`:notes`), `OPEN.66` (a stream as the filespec).

Pinned by `LispEvaluatorTest#openDirectionIoReadsBackWhatItJustWroteThroughOneCursor` /
`#openDirectionIoTruncatesByDefaultAndCarriesTheByteHalf`,
`JvmLispCompilerTest#compileAndRunOpenDirectionIoAndOverwrite`,
`WasmLispCompilerIntegrationTest#openDirectionIoAndOverwriteOnPreview1` /
`#componentOpenDirectionIoAndOverwrite`, `JvmRuntimeClassFilesTest`, ci-spec
`open-direction-io-and-if-exists-overwrite`; byte identity of the rest by
`JvmLispCompilerTest#aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore`.

**`uiop/os:parse-windows-shortcut` / `parse-file-location-info` run unguarded on all four
backends (`.todo/916`)**: the `:rontolisp-wasm` gate they used to open with was checking a
premise this section already disproves, so both parsers now navigate the `.lnk` with
`file-position` on every backend, WASI included. Pinned against a fixture built at run time
with `write-byte` (no shipped binary needed for the WASM leg) by
`WasmLispCompilerIntegrationTest#uiopOsHostIdentityAndGetenvOverrideCompileAndRun`, and
against the shipped `src/test/resources/lnk/sample.lnk` by
`LispEvaluatorTest#evalUiopOsWorkingDirectoryAndTheWindowsShortcutFamily`,
`JvmLispCompilerTest#compileAndRunUiopOsHostIdentityAndGetenvOverride` and ci-spec
`uiop-os-host-identity`.

## A stream is a VALUE, not a handle
**Every OPEN stream is an instance of the fixed `LispLayout.STREAM` layout** — tag `%STREAM`,
declared slots `HANDLE` and `KIND` — so `streamp` answers off the value, and `file-stream` /
`string-stream` are exact everywhere. The `%PATHNAME`/`%SYNONYM-STREAM` precedent: a LAYOUT ONLY in
`ClosRegistry`, so `%obj-new`/`%obj-is` resolve the tag on all four backends while the type joins no
`typep` tag table, no `structure-object`/`standard-object` enumeration and no `%class-slot-defs`
answer.

- **The HANDLE is a declared slot, not machinery**: `equal` on two instances is structural over
  DECLARED slots and CL's `equal` on streams is `eq`, so with the kind alone declared any two file
  streams would compare `equal`. The number is nevertheless backend-local, so the layout is kind
  `OPAQUE` and a stream PRINTS as the plain `#<STREAM>` on every backend — the handle never reaches
  the output (`.kb/emitted-output-determinism.md`, `.kb/instance-syntax.md`). A test that must tell
  two streams apart uses `equal` in-program (see `StreamHandleConcurrencySupport`), not the text.
- **The KIND is a keyword** (`LispLayout.Kinds`): `:FILE`, `:STRING-INPUT`, `:STRING-OUTPUT`,
  `:SOCKET`, `:SOCKET-SERVER`, `:BODY`, `:STANDARD`. Compared with `equal`, not `eq`.
- **`*error-output*` holds one** (`:STANDARD` over reserved handle 2). `*standard-output*` /
  `*standard-input*` keep the `t` DESIGNATOR: it is not a value and does not become one.
- **One gate, both halves**: `LispMacroExpander.mayCreateStreamValues(program)` scans for the
  constructor names (plus `*error-output*`) and answers `Ctx.usesStreamValues`, gating the WRAP a
  producer emits AND the UNWRAP a consumer emits, so the two can never disagree. It also forces
  `mayCreateInstances` on.
- **Producers wrap in the BACKEND, not in an expansion** (`JvmObjCompiler.emitWrapStream`,
  `WasmInstanceCompiler.emitWrapStream`), so the I/O runtime helpers keep their exact bodies — which
  matters on wasm, where their `FUNC_*` indices are fixed and the `--component` adapter blobs depend
  on them.
- **Consumers unwrap at ONE seam per backend, plus stragglers**: `StreamDesignators.throughStream`
  wraps a designator in `(%stream-target D)`, applied by
  `JvmStringStreamCompiler.streamArg`/`inputStreamArg` and `WasmEmitHelper`'s twins to every
  print/read/byte/sequence operator. `streamDesignator(ctx, expr)` is the same resolution WITHOUT the
  `*standard-output*` designator rule, for consumers taking their stream argument as written
  (`close`, `open-stream-p`, `force-output`, `%string-stream-contents`, `file-length`, `warn`'s
  `*error-output*` read, the whole `tcp-*` family). Interpreter twin `Environment.streamTarget`.
- **The prelude splice of `%STREAM-TARGET` is best effort, so the seams have a fallback**: a stream
  value can arrive from a form injected AFTER selection (the generated condition renderer, the
  print-object seam), and both seams then emit `StreamDesignators.throughStreamInline` — the
  `%obj-is`/`%obj-ref` unwrap written out of the primitives. **`sockets.lisp` carries its own
  `%sock-handle`** and the WHOLE resolution, because the component socket splice is used by pipelines
  that do not run prelude selection.
- **Stream TYPE names are all EXACT.** `synonym-stream` is `(%obj-is x '%SYNONYM-STREAM)`;
  `file-stream`/`string-stream` are `LispMacroExpander.makeStreamKindTest` — `(%obj-is x '%STREAM)`
  then an `equal` against `KIND`. **The test must be a `let` plus a nested `if` in that order**:
  `%obj-ref` on a non-instance is undefined on the compile paths, so the tag test comes first.
  `readtable` lowers to `null`. All four are in `PackageRegistry.CL_TYPES` and
  `LispMacroExpander.makeTypeTest`; **a name in the first without a case in the second is a hard
  expansion error in `typecase`, not a silent nil.**

Pinned by `LispEvaluatorTest#evalStreamp`/`#theStreamAndReadtableTypeNamesResolve`,
`JvmLispCompilerTest#compileAndRunTheMissingStandardNames`, its WASM twin, ci-spec
`a-stream-is-a-self-describing-value`.

## Synonym streams
**A synonym stream is a distinct VALUE forwarding EVERY operation to the current value of the symbol
it names — for any symbol.** An instance of `LispLayout.SYNONYM_STREAM` (tag `%SYNONYM-STREAM`): ONE
declared slot holding the symbol, ONE RESERVED cell (`capacity` 2) holding the per-operation READER,
a zero-argument closure over a read of that variable. `(make-synonym-stream '*out*)` ->
`(%obj-new '%SYNONYM-STREAM '*out* (lambda () *out*))` (`expandMakeSynonymStream`; a COMPUTED symbol
falls back to `(lambda () (symbol-value sym))`). Nothing needs the symbol's NAME at run time, which
is why `symbol-value` — a force of the whole eval runtime (`.kb/symbol-runtime-api.md`) — is not in
the lowering. The reader is deliberately OUTSIDE `slotNames`, so it reaches neither the printers
(`#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>` on all four) nor `equal`.

**`%STREAM-TARGET` resolves BOTH kinds** (the old `%SYNONYM-TARGET`, renamed): a synonym answers
`(funcall (%obj-ref s 1))`, recursively; an open stream answers its handle slot; anything else answers
itself. A cycle is the only thing that cannot resolve. Callers: both compile-path seams (above);
interpreter `Environment.synonymTarget`, applied by `resolveOutputDest`/`resolveInputSrc` and —
BEFORE the `instanceof LispInstance` test — by every Gray-dispatching built-in wrap
(`resolveSynonymArg`); **`gray.lisp`'s `%gray-*-dispatch` helpers**, which resolve their stream FIRST
because a synonym is an instance too and would otherwise take the CLOS arm and die on "no applicable
method" (rove's composition); the predicates and `close`, where **the `close` guard exists TWICE on
wasm** — at the `close` case and at the `%CLOSE-RAW` alias the `--component` socket rewrite falls
through to, or a component hands the synonym to the handle-typed close and traps; and **the
`--component` spliced dispatchers**, whose `(or s *standard-input*)` binding is wrapped in the
resolution, because that rewrite REPLACES the read built-ins so the compiler's seam never sees those
call sites.

**Two exceptions in gray.lisp**: `%gray-close-dispatch` does not resolve a synonym and so tests
`%STREAM` by tag ahead of `%obj-p`; and the three PREDICATE dispatchers hand the ORIGINAL designator
to the built-in rather than the resolved handle.

**Gating**: the SYNONYM arm is gated on `make-synonym-stream` appearing in the source
(`Ctx.usesSynonymStreams`) — the only constructor, no read syntax. The `%STREAM-TARGET` call itself is
gated on `usesSynonymStreams || usesStreamValues`. **Two LIBRARY splices pay unconditionally**:
gray.lisp's dispatch helpers and the `--component` I/O dispatchers. Hence
`LispPreludeLibrary.referencedBySurfaceForm` splices `%STREAM-TARGET` for a program that merely uses
the GRAY protocol, and **a pipeline that splices gray.lisp must run `LispPreludeLibrary.process`
too** (the backend test harnesses grew a `compileAndRunGray`). The component splices run BEFORE
prelude selection.

Pinned by `makeSynonymStreamResolvesTheNamedVariable`/`makeSynonymStreamIsAStreamValue`/
`synonymStreamOverStandardOutputFollowsALaterBinding`/
`makeSynonymStreamOverStandardInputFollowsALaterBinding`/
`synonymStreamOverAUserSpecialFollowsALaterBinding` (JVM + WASM), their `eval*` twins, ci-spec
`synonym-stream-value`.

## Load-context variables and `load` options
`*load-pathname*` / `*load-truename*` hold the file being loaded on EVERY backend: the interpreter's
`loadFile` binds them dynamically per file (a COMPONENT by its resolved path, a plain `load` by the
spelling it was called with; nothing absolutizes); the compile paths ASSIGN them per SPLICED file
from `LoadInliner`'s `%begin-file` brackets, so the value agrees byte for byte and equals
`asdf:component-pathname`. **Established at READ time too**: `loadFile` binds the pair BEFORE its
marker read and `UserMacroExpander` pushes the same two strings around the spliced file's forms —
otherwise a `#.` datum reading `*load-truename*` answers nil, since the bracket lowers to `setq`
running long after the datum resolves. Mechanics/gate/tests `.kb/load-inliner.md`; the variable
family `.kb/asdf.md`.

`*load-verbose*` / `*load-print*` are bound and nil on every backend, proclaimed special so
`(let ((*load-verbose* nil)) (load f))` binds dynamically. `load` takes CL's keyword options:
`:if-does-not-exist` is REAL (a false value answers nil instead of signalling) while
`:verbose`/`:print`/`:external-format` are accepted and dropped; every option value is still BOUND,
in written order. The compile paths lower in `LispMacroExpander.lowerLoadOptions` (a `let*` plus an
`(or <value> (probe-file path))` guard); the interpreter reads the same four against a `sourceLoader`
probe — an UNREADABLE file, not merely a missing one, answers nil on both. Because the guard is built
inside the expression compilers long after prelude selection, `referencedBySurfaceForm` splices
`probe-file` on the SURFACE fact (`LispMacroExpander.callsLoadWithIfDoesNotExist`).

The SUCCESS value is `t` on every backend: JVM `_load` returns the symbol String `"T"`, the WASM
`_load` calls `_t_sym`. Both once answered the integer 1 — truthy, so only reading the value showed
it. Pinned by `LispEvaluatorTest#loadAcceptsTheKeywordOptions`, ci-spec `computed-stream-options`.
