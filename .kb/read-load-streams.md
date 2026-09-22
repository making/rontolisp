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
  `PEEK_CP_ADDR`). **WASM-only limit, documented not fixed**: only `read-char` drains it, so mixing
  `peek-char` with `read-line`/`read` on a FILE or STDIN stream loses the peeked character.

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
  `aref`/`%aset`/`length` with fixed `__rseq_`/`__wseq_` temp names and literal-only `:start`/`:end`,
  so no per-backend codegen exists for the loop. A packed buffer is first offered to
  `%read-sequence-packed`/`%write-sequence-packed` (raw little-endian, any rank;
  `.kb/binary-sequence-io.md`), a character buffer one `or` further along to
  `%read-sequence-chars` (a block of storage units per host read; `.kb/character-sequence-io.md`).
  **The BUFFER, not the stream, picks the element**: both dispatch on
  `(stringp seq)`, so a character vector moves CHARACTERS and anything else moves bytes — a RUNTIME
  test because the buffer arrives in a variable, which is also why `make-array`'s `:element-type`
  accepts a computed designator (`lowerRuntimeElementTypeMakeArray`).
- **A provably byte-only buffer skips the dispatch** (`compiler/SequenceIoNarrowing`,
  `.todo/338`): a sequence with `ArgumentShapes` `VECTOR` shape -- a numeric-typed or untyped
  `make-array`, a `(vector ...)`, a `subseq`/`copy-seq` preserving one, directly or through a
  `let`/`let*` binding with no rebinding, capture, shadowing or dynamic scope in between -- expands
  through the byte-only lowering (`expandReadSequence`/`expandWriteSequence` with `byteOnly`),
  which has no `read-char` arm and no `write-string` branch. The packed fast path stays first, so a
  packed buffer still moves in one transfer. A parameter, a `setq`'d variable, a captured or
  shadowed one, a special, a character buffer and a `stream-element-type`-derived buffer all stand
  down to the runtime test (ci-spec `read-sequence-into-a-character-buffer`). Runs backend-locally
  after the gate scans (the JVM and wasm-GC compile paths, next to `DeadTypeBranchPruner`), never
  in `CompileFrontend`: the narrowed expansion still attempts the packed primitive first, and the
  gates that emit that runtime key on the unexpanded spelling. Measured 2026-09-09: a byte-only
  read loop 6,744 -> 5,735 B (`-1,009`), the zlib `--optimize=size` row 125,738 -> 125,081
  (`-657`, the `FUNC_READ_CHAR` the todo estimated at 649).
- The `_eval` interpreters know none of this, nor `require`/`provide` (a file read by the runtime
  `load` of compiled output must not contain them — `.kb/load-inliner.md`). The `CiSpecE2eTest`
  driver passes `--dir . --dir /tmp` to both wasmtime invocations.

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
is composed above it. sbcl signals on `stream-element-type` of a CLOSED stream; ours answers the
type (a narrower-than-sbcl refusal is not worth a table walk at close).

- **Interpreter** (`Environment`): `open` classifies the EVALUATED type and records it per handle
  (`streamElementTypes`, never cleared -- handles are never reused here). `read-byte`,
  `write-byte`, `file-length`, `file-position` and the two packed sequence primitives are
  re-defined as wrappers over the octet built-ins: a wide handle composes / scales / declines, any
  other passes straight through. `stream-element-type` reads the table.
- **Compile paths: a REGISTRY in prelude Lisp, not a runtime mode.** No backend learned a width.
  `LispPreludeLibrary` entries `%file-stream-entry` / `%file-stream-register` /
  `%file-stream-element-type` (a hash table keyed by the handle, entry = `(stream octets signed
  spec)`, VALIDATED against the stream value with `eq` -- a WASM descriptor is reused after close,
  and a socket on a reused fd must not inherit a file's width) and `%wide-width` /
  `%wide-read-byte` / `%wide-write-byte` / `%wide-elements` / `%wide-position-octets`. The
  backends (`Jvm/WasmExprCompiler`) lower ONLY when the entry is spliced (`ctx.functions`): a
  literal binary `open` leaf becomes `(%file-stream-register (%obj-new '%STREAM <checked open>
  :FILE) n signed 'spec)` (`LispMacroExpander.registeredOpen`), `read-byte` / `write-byte`
  (and the component socket aliases `%read-byte-raw` / `%write-byte-raw`) call the wide helpers,
  `file-length` / `file-position` go through the scaling call-site shapes
  (`expandWideFileLength` / `expandWideFilePosition`, after `rewriteFilePositionArg`), and the
  packed arm of every `read-sequence` / `write-sequence` expansion -- the backends' own and
  `SequenceIoNarrowing`'s -- is guarded by `%wide-width` (`guardPackedSequenceForWideStreams`),
  because the packed primitive moves raw octets. The helpers reach the primitive through four
  internal names the lowering does not rewrite again: `%read-octet`, `%write-octet`,
  `%file-octet-length`, `%file-octet-position`. **Every name-keyed runtime gate still sees the
  public name** (the JVM `FileMeta` / stdout-flush gates, the WASM `file-position` import), because
  a raw call appears only in the lowering of a call the source wrote.
- **Two selection facts, both SURFACE** (`referencedBySurfaceForm`, `LibraryDefunPruner`'s
  synthesized list): the wide half on `LispMacroExpander.opensWideElementStream` (a literal wide
  `:element-type` on an `open` / `with-open-file`); the registry also on "names
  `stream-element-type` AND names `open` / `with-open-file`" -- which is what makes an octet
  stream answer `(unsigned-byte 8)` instead of the old `CHARACTER` constant. A wide leaf compiled
  without the registry (a pipeline that skipped prelude selection) signals at call time
  (`wideElementTypeUnavailableStub`) rather than open octets.
- **Literal-only on the compile paths, by the `:io` trade.** A computed wide type would put every
  uiop wrapper and `#'open` behind the helpers. `uiop:with-temporary-file` opens through a computed
  element type, so a wide literal there is refused at call time too.
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
`JvmLispCompilerTest#compileAndRunWideAndNarrowElementTypes` /
`#compileAndRunStreamElementTypeOfAnOctetFileStream`,
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

## `file-position` is REAL on ALL FOUR backends for a BINARY file stream

The byte primitives advance a per-handle position and the set re-opens the file at the
offset, so a caller can seek and read the sought bytes rather than walk front to back.
Interpreter: per-handle `streamPositions` map (`Environment`), advanced by the byte
primitives through `merge` and repositioned by `binaryFileStreamSet`. JVM: the mirrored
`Object[] _streamPositions` side table ([jvm-export.md](jvm-export.md) / this file's
file-length section), advanced by `_bumpStreamPosition` (called by `_readByte`,
`_writeByte`, `_readSeqPacked`, `_writeSeqPacked`) and queried/set through
`_filePosition`, which re-opens via `FileChannel.position`. Both are gated per operator
(interpreter: nothing; JVM: `JvmIoRuntimeBuilder.FileMeta.position`) so a program that
never calls `file-position` pays nothing. A CHARACTER file stream, a socket, a string
stream, a standard stream and a closed handle answer `nil` (Common Lisp's "cannot be
determined") on both; the JVM `#'file-position` function-value wrapper is
`REFERENCE_GATED` like `#'file-length`, because its body lowers to the gated
`_filePosition`. The served-request body keeps its own REAL `file-position` through
`HttpRequestBodyStream` on all four.

**Both WASM backends answer real too, through ONE injected import each** -- gated on the
program naming `file-position`, so a program that does not is byte-identical to a build
that never knew about the feature ([[wasm-import]], "This, not a new index-pinned preview1
slot"). `WasmFilePositionCompiler` resolves the stream to its raw handle and calls the
`_file_position` / `_file_position_set` pair (`FUNC_FILE_POSITION` after
`FUNC_FILE_LENGTH`), which stage 8 bytes at `HEAP_PTR` with the `_open` advance-then-pop
discipline and answer `nil` for the set of designators `_file_length` answers `nil` for,
plus a non-zero errno.

- **Preview 1** (`.todo/876`) has a real moveable cursor, so ONE appended
  `wasi_snapshot_preview1.fd_seek` serves both directions: `(fd, 0, cur)` reads the
  position, `(fd, n, set)` moves it. `fd_read`/`fd_write` advance that same cursor, so
  nothing has to be tracked in-module. **The read-mode `path_open` asks for `FD_READ`
  alone and `fd_seek` still works** -- wasmtime's preview1 does not enforce rights (the
  `fd_filestat_get` behind `file-length` has been riding the same fd for longer); a host
  that did would answer `ENOTCAPABLE`, which reads as `nil`, and the fix would be widening
  the read rights to `FD_READ|FD_SEEK|FD_TELL` = 38.
- **`--component`** (`.todo/877`) has NO cursor -- WASI 0.3 reads are offset-based -- so
  the adapter tracks a per-fd byte offset and exports the `file_position_get` /
  `file_position_set` pair over it. Serve implies `--component`, so the serve bridge sees
  the same two.
- **`--no-wasi`** has no filesystem, so the operator keeps compiling to the `nil` constant
  and neither import nor flag table exists.

**SBCL answers the byte offset for a character file stream where all four of ours answer
`nil`** (2026-09-19: the ci-spec program's last form prints `0` under `sbcl --script`, `NIL`
here). `nil` is CL-sanctioned -- "cannot be determined" -- and is at least the SAME answer on
every backend, which a number would not be: a JVM `Reader` buffers and does not remember its
path, and Preview 1's read buffer puts the descriptor ahead of the logical position. Closing
the gap means all four learning the buffered offset at once.

**Decided in `.todo/906` (2026-09-20), measured, not assumed**: on its own the gap is worth
exactly ONE ANSI test (`FILE-POSITION.5`, `Expected integer, got: NIL`) -- every other
character-stream `file-position` test in the chapter needs a file the suite does not ship.

**The premise that tied it to `:io` did not survive `.todo/918` (2026-09-22).** It assumed
an `:io` stream would be a character stream of the existing kind and so need the logical
offset first. It is not: `:io` is its own stream kind that owns its cursor (next section),
so its character `file-position` is real on all four with no offset tracking at all, and
the ordinary `:input` / `:output` character stream keeps answering nil -- still worth that
one test, split out on its own. Two corrections to the old mechanism note while measuring:
Preview 1's `_read_line` / `_read_char` read ONE BYTE per `fd_read`, so the descriptor
offset already IS the logical position for them (`READ_CURSOR_ADDR`..`READ_END_ADDR` belong
to the READER over `load` / `read-from-string`, not to a stream); the one read-ahead on a
character stream is `read-sequence`'s 64 KiB bulk path (`WasmCharIoRuntimeBuilder`).

**A CHARACTER file stream answers `nil` on both, off a per-fd binary flag byte written at
the `open` call site** (the element type is a compile-time literal, so nothing else can
know it). Preview 1's read buffer (`READ_CURSOR_ADDR`/`READ_END_ADDR`) puts the descriptor
AHEAD of the logical character position, so a number there would be a wrong answer, not a
useful one. The two backends differ in who owns the table:
`--component` reads the adapter's (page 5, `STREAM_BINARY_FLAGS_ADDR`, indexed `fd - 100`),
whose `path_open` resets a reused slot, so only a binary `open` writes. Preview 1 owns its
whole linear memory and its heap grows through page 5, so the table is the module's own:
`STREAM_BINARY_FLAGS_SLOTS` (1024) bytes at `DATA_BASE_OFFSET`, indexed by the RAW
descriptor, with the interned-string base moved up by exactly that much. **That address has
to be a constant known BEFORE Pass 2** -- `WasmOpenCompiler` emits the write while bodies
compile, and the static-data END is not known until every string is interned, which is why
it sits below the data rather than above it. Nothing seeds it: zero-initialized memory
already means "a character stream". With no adapter to reset a slot, EVERY Preview 1 `open`
writes its own byte (1 binary / 0 character) and a descriptor at or above the slot count
answers `nil`; the ci-spec case re-opens the file as a character stream after every binary
handle is closed, precisely so a stale flag on a reused descriptor prints a number.

Pinned by `LispEvaluatorTest#binaryFileStreamPositionQueriesAndSeeks`,
`JvmLispCompilerTest#compileAndRunBinaryFileStreamPositionQueriesAndSeeks`,
`WasmLispCompilerIntegrationTest#filePositionQueriesAndSeeksOnPreview1` /
`#componentFilePositionQueriesAndSeeks` (one `FILE_POSITION_PROGRAM`),
`compileAndRunLiteStreamBuiltins`, the Gray rewrite case and ci-spec
`file-position-round-trips-on-a-binary-file-stream`.

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
  `fd_seek` moves it; the per-fd flag byte `_file_position` reads now means "position is
  real" (binary OR `:io`/`:overwrite`), not "binary".
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
