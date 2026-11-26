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

- **Escapes** (`\c`, `|...|`, CLHS 2.1.4.6-7): a token holding one is a symbol, upcased only where
  unescaped, so what `prin1` prints reads back. A text ending inside one records end-of-file. The
  standard qualifier drop applies (`keyword::foo` is `:foo`, in `LispReader` too); `|NIL|` is nil.
  ci-spec `the-empty-name-is-a-symbol-like-any-other`.
- **Only an unescaped colon is a package marker** (CLHS 2.3.5): `|a:b|` and `a\:b` are one
  symbol of the current package, spelled `CL-USER::a:b` in `cl-user` (`.kb/symbol-runtime-api.md`, "The
  empty name"). JVM `_rdEscaped` / wasm `_rd_token` count the escaped and marker colons. The source
  lexer marks such a token `PackageRegistry.CURRENT_PACKAGE_QUALIFIER`, which the resolver and the
  interpreter's run-time read take as a bare token of the whole name. Limit: `|a:b|::c` splits at its
  first colon, where SBCL looks for the package `a:b`. ci-spec
  `a-colon-inside-a-multiple-escape-is-a-constituent` (SBCL-checked).
- Unclaimed tokens fall to the atom path like `readSymbol`: `#foo` -> `#FOO`, `#16r1f` -> a symbol.
  Only the interpreter's reader refuses `..`/`...`, `#:a:b`, `#<`, a `#n*` over/under-fill and a
  constituent behind `#*` bits (symbols on the compiled readers); its `#n*` repeat-last-bit fill has
  no compiled twin.
- PERMANENT limits: `#.`, `#+`/`#-`, `#n=`/`#n#` signal a catchable error (`#+`/`#-` only in the
  one-argument `read-from-string`: `read` and the full defun resolve guards in the prelude scanner).
  The interpreter's runtime read still resolves `#.` (via `Environment.setReadTimeEvalResolver`,
  gated on `*read-eval*`, `.kb/reader-features.md`) and `#+`/`#-`, and reads labels — one documented
  interpreter/compiled divergence.
- Errors: JVM `simple-error` with the frontend's EXACT messages; WASM `unreachable`, or in EH mode a
  catchable throw whose message is STATIC (`WasmReadRuntimeBuilder.emitErr`). The interpreter signals
  TYPED `reader-error` / `end-of-file` carrying the stream (from `LispReadException.isEndOfFile`, in
  `LispEvaluator.foldStructLiteralsOf`); only a clause naming the type sees the difference. Running
  out, a stray `)` and a malformed dotted list are typed on every backend ("The one-argument
  `read-from-string` refuses malformed text").
- `#S`: an omitted slot takes a nil initform, re-reads a baked `EmittedReaderInitforms` constant text,
  or signals — never a silently wrong value. WASM decimal floats are the double nearest the token
  (`.kb/wasm-bignum.md`, "The runtime reader's decimal floats").
- Trailing dot: `5.`, `-5.`, `+5.` are the decimal INTEGER (CLHS 2.3.1) on all four; `1.e5` and `.5`
  floats; `5.e`, `5.x`, `1.2.` symbols. ci-spec `runtime-read-trailing-dot-integers`.
- Explicit `+`: dropped when a digit, or `.` and a digit, follows; a token the number attempts then
  refuse (`+5x`, `+1+`) is the symbol WITH its sign, as in the source lexer (`readNumber("+")`).
  ci-spec `runtime-read-explicit-plus-numbers`, `explicit-plus-number-shaped-symbols`.
- `read-from-string` reads the FIRST datum only. The interpreter parses the whole text and, only when
  that signals, cuts it at `LispLexer.datumEnd`, so `"5.)"`, `"abc)"`, `"1 (2"` answer the first datum
  like SBCL while a stray `)` ALONE or an unfinished datum still signals. ci-spec
  `runtime-read-stops-after-the-first-datum`.
- Dotted pairs: `.` is a dot token only when the next byte is a delimiter (whitespace `( ) ' " ;`) or
  EOF (`LispReader.readList`, `buildReadList`, `buildReadListBody`).

Pinned by `Jvm/WasmLispCompilerTest#compileReadFromString{CharLiterals,VectorsAndArrays,StructLiterals,SymbolParityAndBlockComments,ReaderErrors*}`,
ci-spec `runtime-read-*`, `read-from-string*`.

## A symbol token reads in the current package
**Invariant: a compiled run-time read interns as CL's reader does, on every path into the emitted
readers: `read-from-string`, `read`, a computed `load`, a `#S` type name, a slot value.** A bare token
interns in `*package*`; a qualified one in the package the qualifier names, the current package's
nicknames read first.

- **One definition**, `(%read-home symbol qualifier)` (`LispMacroExpander.injectReadHome`) over
  `intern`'s computed-designator lowering, injected after the prelude and the pruner so a dead read
  costs nothing. The readers call it for a TOKEN only (the interpreter's `symbolHome` rule): not for
  `quote`/`function`, a standard-qualified token (`cl-user::foo` stays bare under `P`), a keyword, or a
  token with a colon past a qualifier's (`p:out:x`, which SBCL refuses).
- **Why not a walk over the datum**: `cl-user::foo` and `foo` leave one spelling, `quote` under a
  package using no `cl` would be re-homed, and `#S` looks its type up during the read.
- **The gate** (`readsUnderAPackage`): the program spells `read-from-string`, `read` or `load`, and
  assigns a package other than `cl-user` at top level, mentions `*package*` anywhere (not counting
  `with-standard-io-syntax`, which binds `cl-user`), defines a package a qualifier can name, names a
  local nickname, or widens `cl-user`. A pristine `cl-user` answers a bare token at once
  (`clUserIsPristine`). What a package reaches is one answer for the whole compile, as for `intern`.
- **A baked initform's text must not home** (`EmittedReaderInitforms.initTexts`): a homing program's
  text spells a bare symbol `CL-USER::CAR`, which the reader takes as spelled.
- Cost (2026-11-05): `intern`'s lookup with the 7.4 K standard names, about +20 KB class / +27 KB wasm.
  100,000 tokens under `P` vs `cl-user`: 165 / 38 ms JVM, 400 / 34 ms wasm, 97 / 67 ms interpreter;
  the first read decodes the tables (about 1 s on wasm). No per-package memo: nothing reads that much.
- Limits: a qualifier naming no package keeps the spelling, and a single colon on an internal name
  interns as `::` does, on all four, where SBCL signals; `t` and `nil` read ahead of any package
  (`.kb/pretty-printer.md`). A run-time `load`'s package directives stay unprocessed
  (`EvalReferences.UNPROCESSED_DIRECTIVES`), so a file reads in the package current when `load` runs.

Pinned by ci-spec `a-run-time-read-interns-in-the-current-package` (SBCL-checked),
`a-run-time-read-in-a-cl-user-that-uses-a-package`,
`LispMacroExpanderTest#onlyAProgramThatCanReadUnderAnotherPackageCarriesTheReadersHome`.

## A list's length costs the reader no stack
`_readList` / `_read_list` loop down the list, so depth is nesting, as in `LispTrees`
(`.kb/interpreter-stack.md`). ci-spec `runtime-read-of-a-long-list`.

## `read` is PRELUDE RONTOLISP, not a primitive on any backend
One `LispPreludeLibrary` entry (`read` + the `%rd-*` family), so all four backends run one code path.
It consumes exactly ONE datum's characters and leaves the stream after them.

- **The scanner only DELIMITS** (mirroring `LispLexer.skipDatum`); `read-from-string` parses the
  text, so a syntax gap of the emitted reader is identical for `read`.
- **Except `#+`/`#-`, which the scanner RESOLVES** (`%rd-guard`), at top level and at a token start
  inside a list or vector, so the compiled backends -- whose emitted readers know no `#+` -- read it
  too. The feature expression is read with a `:` prefixed to every unqualified token (the
  `*package*`-is-`KEYWORD` rule, which no backend's reader can be told) and tested against the live
  `*features*`; a qualified name matches a non-keyword entry by NAME, since a symbol read back is not
  `eq` to the one pushed ([[reader-features]]). A nested guard inside a failed guard's form is
  evaluated like SBCL's. Malformed expressions are `simple-error` with SBCL's messages. Pinned by
  `ReadFeatureGuardFixture` (ci-spec `read-skips-a-failed-feature-guard`) in the three backend suites.
- The character a terminator gives back rides the `unread-char` cell — what makes `read` +
  `read-line` on one stream work.
- **After the object, ONE whitespace character is consumed** (CLHS 23.2); a terminating macro
  character is unread instead.
- An INCOMPLETE datum signals `end-of-file` whatever `eof-error-p` says.
- **Trap**: `read` needs BOTH compile-path passes — `LispPreludeLibrary.process` and
  `UnreadCharLibrary.process` — in `CompileFrontend`'s order; neither run = call-time "The function
  READ is undefined".
- Dead: `JvmReadRuntimeBuilder`'s `_read`/`_readStream`. WASM's `FUNC_READ` keeps its slot with an
  unused stub — removing a function shifts every later index and changes the component blobs.

Pinned by `LispEvaluatorTest#read*`, `JvmLispCompilerTest#compileAndRunRead*`,
`WasmLispCompilerIntegrationTest#read*`, ci-spec `read-stream-datum-by-datum`.

## `read-from-string` answers the STOP INDEX, and honors `*read-suppress*`
**Invariant: the second value exists on ALL FOUR backends; the suppressed MODE is the
interpreter's alone.**
- The index is `%read-from-string-end`, emitted ONLY by the multiple-value lowering of a
  `read-from-string` producer (`LispMacroExpander.isMvProducerForm`), so a plain call pays nothing.
  The interpreter's `LispLexer.datumEnd` is a RAW-CHARACTER scan, so it answers for text the parse
  refuses.
- **The index counts CHARACTERS (code points)**, as SBCL does: `"日本 x"` -> 3, `"😀 x"` -> 2. The
  interpreter and JVM convert a UTF-16 index; WASM counts non-continuation bytes. ci-spec
  `read-from-string-stop-index-counts-characters`.
- CLHS 23.2 decides the last character, for ANY datum (SBCL-verified): a whitespace terminator is
  CONSUMED, a terminating macro character given back. `"(1 2) x"` -> 6, `"\"str\" x"` -> 6.
- `*read-suppress*` (CLHS 2.2): the datum is consumed and NOTHING parsed, so an unknown package, a
  bogus character name and an out-of-range digit pass. The interpreter's built-in reads it through
  `Environment.setReadSuppressQuery` (the built-in holds the GLOBAL environment; the binding is
  dynamic). The VARIABLE exists on all four: a program naming it or `*read-eval*` gets a `defvar`
  (`LispMacroExpander.PRINTER_MODE_VARS`), so puri's `#u` compiles; ci-spec
  `source-reads-with-the-readtable-an-eval-when-installs`.
- **`read` must stay SINGLE-valued**: its prelude body ends in `(values (read-from-string
  %rd-text))`; without that `values` the index rides out of the tail.
- **The publication is TAIL-position only** (`spillEscapingMvProducers`): publishing on every call
  leaks a discarded call's index as an enclosing form's second value.
- The producer is recognized at ONE argument only; a call passing more is the next section's defun
  (`settleTail`, `isReadFromStringWithArguments`).

Pinned by `LispEvaluatorTest#readFromStringAnswersTheStopIndexAsItsSecondValue`,
`#aDiscardedReadFromStringLeavesNoSecondValueBehind`,
`#readSuppressConsumesTheDatumAndAnswersNil`,
`JvmLispCompilerTest#compileReadFromStringStopIndex`,
`WasmLispCompilerIntegrationTest#readFromStringStopIndex`, ci-spec
`read-from-string-stop-index`.

## `*read-base*`: every reader reads a rational token in it
**Invariant: on all four backends, under a base other than 10, a token that is `[sign] digits [/
digits]` in the base is that number, ahead of every other reading; one that would be that shape in
decimal but holds a digit past the base is a symbol; every other token reads as in base 10** (a
trailing dot, a float; so `1s0` is a float in base 16 where `1f0` is 496). Against SBCL 2.2.9: 100
tokens over bases 2 to 36 agree on all four but a double's `d0` (one float type). ci-spec
`read-base-reads-rationals-in-its-radix`.
- Interpreter and source reads: `LispLexer.lexRationalInReadBase`. A token holding an escape is no
  number. JVM `_classify` -> `_readBaseNow` (`JvmRawGlobals.reserved` names the variable, since the
  method reads the field directly). WASM `emitReadBaseArm`, ahead of the qualifier drop (`CL:FF` is a
  symbol, `NIL` in base 36 a number); `--reentrant` reads the task slot (`ReadBaseVar`); no new
  function or local, since an index would shift the component blobs.
- **Only a program naming the variable pays** (its `defvar` makes the global). A base only a run-time
  string names is not honored.
- A value no radix is reads as 10 on all four; SBCL's declared type refuses the binding.
- Limits: the compiled readers' token runs through a `,` (`1,000` is their decimal grouping where the
  interpreter and SBCL read `1`); `#S`'s type name stays a symbol; `|ff|` and `\ff` are symbols.
- A compile's own source: compile-file semantics ([readtables.md](readtables.md)), so a top-level
  `(setq *read-base* 16)` changes the interpreter's read of the next form and only the run-time reads
  of a compiled program. `load` does not bind the variable, in SBCL neither.

## The one-argument `read-from-string` refuses malformed text
**Invariant: text holding no datum (empty, whitespace, comments) or ending inside one (a list, a
string, a quote, a dotted tail, `#(`, `#S(`, `#|`, `#\`) is `end-of-file`; a `)` where a datum is
due, a `.` token with nothing before it in its list (`( . a)`, `#( . a)`) and a dotted tail followed
by more than one object (`(a . b c)`) are `reader-error`, reporting `Unexpected ')'`, `Nothing
appears before '.' in list` and `More than one object follows '.' in list`, on all four backends**
(SBCL's classes; the interpreter's texts).
- The emitted readers never throw for these; they RECORD how the parse ended -- JVM `_readFail`
  (`JvmReadRuntimeBuilder.FAIL_FIELD`), WASM `READ_FAIL_ADDR`, which SHARES the block-comment depth
  cell (no free word below `DATA_BASE_OFFSET`): bit 0 the text ran out (an OR, so an earlier error
  survives), 2 a stray `)`, 4 a dot with nothing before it, 6 a second object after a dotted tail
  (`ReadFailure`). An error also moves the cursor to the end, so every enclosing reader unwinds through
  its end-of-input path and no later `#|` overwrites the cell; the error is `failure & 6`.
- The call site signals (`LispMacroExpander.expandReadFromStringFailure`): typed behind a landing pad,
  plain `%error` with the same text otherwise (the `lowerParseError` split).
  `READ_FROM_STRING_FAILURE_SITES` stands in for both tags in `conditionNarrowing` / `usedLayoutTags`.
  The conditions carry no stream on the compiled backends.
- Runtime `load` (`_load`, both backends) reads the same record after each form and refuses the bad
  one before evaluating it, after the forms before it ran (SBCL's order).
- The interpreter's `load` reads a Common Lisp file FORM AT A TIME (`SourceLanguage.readForms`,
  [readtables.md](readtables.md)); `loadReadError` signals `end-of-file` / `reader-error` reporting
  `LispReadException.reason`, while the Java message keeps `file:line:column:`. A form the lexer cuts
  off reports the LEXER's error (`Unterminated block comment`). A Scheme / Clojure file is lowered
  whole through `SourceLanguage.readUntilError`: a read error there runs no form.
- Compiled-only leniencies left: `(a . . b)` is the "more than one object" report there, `Unexpected
  '.'` on the interpreter (both `reader-error`); `#` alone reads as a symbol everywhere.

Pinned by `ReadFromStringMalformedFixture` (ci-spec `read-from-string-refuses-malformed-text`) in the
three backend suites, `JvmLispCompilerTest.compileAndRunLoadRefusesAFormTheTextEndsInsideOrAStrayCloseParen`,
`WasmLispCompilerIntegrationTest.loadRefusesAFormTheTextEndsInsideOrAStrayCloseParen`,
`LispEvaluatorTest.loadRunsTheFormsBeforeAMalformedOneThenSignalsACatchableCondition`,
`LispReaderTest.aPrefixReadAnswersTheFormsBeforeTheFirstError`.

## `read-from-string`'s whole lambda list is ONE prelude defun
**Invariant: a call passing more than the string is `(%read-from-string-full ...)` on all four
backends, in call position (`LispMacroExpander.expandReadFromString`) and through `#'read-from-string`.
The one-argument call keeps the built-in, byte for byte.** CL's lambda list is the defun's own, so
keyword errors behave as any defun's.
- The body is `read`'s over a `with-input-from-string` window. No datum answers `eof-value` or
  signals as `eof-error-p` says; an incomplete datum is `end-of-file` WHATEVER it says.
- The index is `:start` plus the window stream's `file-position`, on every target (`--no-wasi` too:
  `WasmLispCompilerIntegrationTest.noWasiStringStreamsAnswerFilePosition`). A `read-char` drain would
  make the successive-forms idiom `(read-from-string text nil eof :start pos)` quadratic. The scanner's
  terminator is TAKEN and counted back when kept, so no pushed-back character outlives the stream.
- The bound check is the window's `subseq` (`.kb/sequence-bounding-keywords.md`).
- Selection (`LispPreludeLibrary.referencedBySurfaceForm`, `LibraryDefunPruner`): a call with more
  than one argument, or a designator that may be called with more (`mayPassMoreThanOneArgument`);
  the full `#'read-from-string` wrapper `(s &rest r)` goes in with it
  (`BuiltinFunctionWrappers.HELPER_WRAPPERS`).
- Its `#+`/`#-` is resolved on every backend; the one-argument call resolves it on the interpreter only.
- The full call is slower than the one-argument read: the scanner walks in Lisp what that does natively.

Pinned by `ReadFromStringLambdaListFixture` (`.PROGRAM`, sbcl's answers, ci-spec
`read-from-string-takes-its-whole-lambda-list`; `.REPORT_PROGRAM`) in the three backend suites,
`LispMacroExpanderTest.aReadFromStringCallPassingMoreThanTheStringCallsThePreludeDefun`,
`LispPreludeLibraryTest.theFullReadFromStringIsSplicedOnlyWhereACallCanPassMoreThanTheString`.

## `read-line`, `read-char`, `peek-char`
- `read-line`'s second value, missing-newline-p, comes off a separate read that sees the
  terminator, used only where a consumer or a tail can observe it ([[multiple-values]],
  "read-line's missing-newline-p").
- **A line ends at LF alone; one CR just before the LF (or the end of input) is dropped** -- on all four
  backends, so a lone `\r\n` line reads `""`, and a CR no LF follows stays in the line. SBCL keeps the
  CR before the LF/EOF; the drop is this project's CRLF convention (HTTP over a socket). Never
  `BufferedReader.readLine` (it ends a line at `\r`): interpreter `TerminatedLine`, JVM `_lfLine` /
  `_readLinePair`. Pinned by ci-spec `read-line-ends-a-line-at-line-feed-alone` and
  `ReadLineValuesFixture.carriageReturnProgram` / `carriageReturnFileKindsProgram` in the three suites.
- `read-char`: WASM reads ONE BYTE per call (fd or a negative string-stream handle). **Trap**: the
  compilers evaluate multi-argument call forms right-to-left, so sequence consecutive `(read-char s)`
  calls through `let*`, not `(list ...)`.
- `peek-char`: only "the next character, left in place" is a primitive (`%peek-char`,
  `PEEK_CHAR_INTERNAL`); the SKIPPING forms are one shared `LispMacroExpander.expandPeekChar`
  lowering, behind a runtime `(null ...)` test for a non-literal peek-type. **The `characterp`
  guard in the loop's end test is load-bearing**: with a nil `eof-error-p` it ends the loop on the
  eof-value instead of skipping forever. Interpreter/JVM `mark(2)`/`reset()`; WASM has no reset, so a
  WASI fd parks the BYTES the character was decoded from in the fd's byte pushback
  (`PUSHBACK_KEY_ADDR` / `PUSHBACK_BYTES_ADDR`, `.kb/character-sequence-io.md` "Malformed
  input"), which every character read takes first.

## `open` / `with-open-file` / `%probe-file`
- `with-open-file` is a plain macro (`expandWithOpenFile`) over `open`/`close`.
- The positional form's direction is ONE literal token so both compilers resolve the mode at
  compile time (`compiler.OpenModes`); `:append`, `:overwrite`, `:io-append`, `:io-overwrite` are
  normalized direction + `:if-exists` spellings (`LispNames.outputDirectionToken`).
- **A failed `open` signals a `file-error` on every backend**, carrying the designator as given
  and reporting `OPEN: cannot open file <namestring>`. Compiled: ONE call-site lowering,
  `LispMacroExpander.expandOpenFileErrorSignal`, over the internal `%open-or-nil`. `%file-error` is
  the `%program-error` split (`lowerFileError`), so the whole-program scans cannot see the
  construction and take `LispMacroExpander.FILE_ERROR_SITES`' presence for the tag
  (`conditionNarrowing`, `WasmLispCompiler.usedLayoutTags`).
- **A `load` that cannot open its file signals a `file-error` on every backend**, reporting
  `LOAD: cannot open file <namestring>` (`expandLoadFileErrorSignal`; `isRawLoadCall`; `LOAD` is in
  `FILE_ERROR_SITES`). `require` and ASDF components keep `cannot read file <resolved>`. A top-level
  literal `(load "missing.lisp")` still fails the compile in `LoadInliner`.
- **`with-open-file` closes only a stream** when its open may answer nil (`buildWithOpenFileFrom`'s
  `mayBeNil`, CL's `(when var (close var))`); `close` of nil is a `type-error` ("The stream
  operators that take a stream" in `.kb/error-handling.md`).
- `--component`: with no `--dir`, `adapter.wat`'s `$ensure_preopen` caches `-1` and `$path_open` turns
  it into an errno (not a handle-0 trap).
- **`%probe-file` stays a string-in/string-out PRIMITIVE**, not `open` in a `handler-case`: nil is
  cheaper than catching a condition, works outside EH mode, and `--no-gc` rejects catching. Public
  `probe-file` is prelude Lisp over it, probing the `%real-path` spelling. Contract: the namestring as
  given when the file exists (no link resolved, nothing absolutized), in DIRECTORY form (one `/`
  added) for a directory, nil otherwise -- also for a FILE spelled with a trailing `/` (POSIX's
  ENOTDIR, which both WASM hosts give; `File`/`Path.of` drop the separator, so the JVM and the
  interpreter spell the rule). A link counts when its target does. The interpreter goes through the
  installed **`SourceLoader.exists`**, never `Files`, so the playground's in-memory loader answers
  (`fileSystem()` OVERRIDES with `Files.exists` — a read is wrong for a file that is not decodable
  text). JVM `JvmProbeFileCompiler`; WASM `_probe_file` closes the fd (`probeFileLeaksNoDescriptor`).
- **`truename` resolves symbolic links on all four**: prelude `%real-path`, a realpath(3) walk over
  ONE primitive per backend, `%read-link` (WASM: the injected `path_readlink`; the adapter's over
  `readlink-at`, the serve bridge errno 76). The `bufused` cell must be 4-aligned (wasmtime:
  `Pointer not aligned to 4`). A relative path stays relative (no cwd on wasm). wasmtime refuses an
  ABSOLUTE link target (errno 63), so such a link is not there on both wasm backends (the one
  cross-backend difference). **Every WASM `path_open` passes `symlink_follow`**: with lookupflags 0
  the host refuses a link in the LAST component.
- **`:if-exists :append`** normalizes into the `:append` PSEUDO-DIRECTION (not a CL direction;
  `OpenModes.normalizeKeywordForm`, `isAppendIfExists`). WASM opens it with `O_CREAT` alone (O_TRUNC
  would discard what the append keeps) plus `FDFLAGS_APPEND`. With `:direction :input` it is ignored.

Pinned by `LispEvaluatorTest#evalOpenAppendKeepsTheExistingContent`,
`JvmLispCompilerTest#compileAndRunOpenAppend`, `LispEvaluatorTest#probeFile*` + twins,
`#truenameResolvesEverySymbolicLinkInThePath` (+ JVM / WASM twins), ci-spec
`probe-file-existing-and-missing`, `truename-resolves-symbolic-links` (tree staged by
`CorpusFixtures.stageSymlinkTree`), `open-if-exists-append-keeps-the-existing-content`.

## The `:if-exists` / `:if-does-not-exist` table is ONE lowering over `%probe-file`

**No backend learned a mode for it.** The whole table plus `:direction :probe` is a GUARD around the
literal open, built once in `LispMacroExpander.lowerRuntimeOpenOptions` and reached by `open`'s two
compilers, `expandWithOpenFile` and the `#'open` wrapper. The interpreter's `open` built-in is the
fourth reading, natively over `Files.exists`; the two must stay in step, pinned across backends by
ci-spec `open-if-exists-if-does-not-exist-and-probe`.

- The guard reads `(%probe-file path)` ONCE and the base open appears once below it. Both arms folding
  to "just open" emits NO guard (`needsOpenExistenceGuard`). The probe is the primitive
  (`existenceProbe`), not `probe-file`: it asks what the open will see, needs no truename, and --
  built after prelude selection -- needs no splice.
- **`:if-exists` is read only on an OUTPUT open**, so `(open p :if-exists :rename)` is the plain input
  open CL says it is. `:supersede` / `:new-version` / `:rename` / `:rename-and-delete` all collapse onto
  the truncating open (no version numbers; SBCL leaves the same content); `:append` and `:overwrite`
  are pseudo-directions ("`:direction :io` and `:if-exists :overwrite`").
- **`:if-does-not-exist` defaults per CLHS**: `:create` for a superseding output or `:io` open, nil for
  `:probe`, `:error` everywhere else -- INCLUDING an APPENDING or OVERWRITING open, which therefore
  does not create the file (sbcl-checked; smart-buffer's disk spill is unaffected because
  `uiop:with-temporary-file` creates the file first).
- **`:direction :probe` is an open followed by a close**: CL's probe stream is an already-closed file
  stream.
- **`close` on an already-closed stream answers `t`** (CL, SBCL): `with-open-file` closes a stream the
  body may have closed. `--component`: `adapter.wat`'s `$fd_close` returns 0 when the slot's live flag
  (offset 12) is already clear, since the host traps on a second drop of one `descriptor`; regenerate
  `adapter.wasm` with `src/wasm-component/regen.sh` after touching it. On the WASM backends a second
  close of a stream VALUE never reaches the descriptor ("A stream is a VALUE, not a handle"): the
  descriptor may belong to a NEWER stream by then. Pins:
  `LispEvaluatorTest#closingAnAlreadyClosedStreamAnswersTrue`,
  `JvmLispCompilerTest#compileAndRunOpenExistenceOptions`; all four: ci-spec
  `open-if-exists-if-does-not-exist-and-probe` and `stream-operators-clear-input-and-friends`
  (double close after reuse).

## Five stream operators that are prelude Lisp over what exists

`clear-input` (nothing here buffers input; a Gray stream's method runs, [gray-streams.md](gray-streams.md)),
`interactive-stream-p` (over `%terminal-p`), `stream-external-format` (always `:utf-8`),
`file-string-length` (the UTF-8 byte length) and `broadcast-stream-streams`: no primitive, no
per-backend compiler, no wrapper entry. The composite constructors carry `stream-read-byte` /
`stream-write-byte` the same way. Pinned by ci-spec `stream-operators-clear-input-and-friends`.

### `%terminal-p`: is a standard stream a terminal

`(%terminal-p fd)`, one primitive per backend, never signalling: `t` is fd 0 AND fd 1, a `:standard`
value its descriptor (`*error-output*` 2), a synonym its target.

- Interpreter `runtime/RontoTerminal.isTerminal` (FFM `isatty`), fd 0/1 only while the evaluator was
  given `System.in` / `System.out` itself (a test capture is no terminal).
- JVM `RontoTerminal.terminalP`, travelling (`TERMINAL_RUNTIME_CLASS_FILES`).
  `isNativeAccessEnabled()` false answers false WITHOUT the downcall, so the JDK's restricted-method
  warning never reaches standard error. `-Pweb` substitutes `false` (`Target_RontoTerminal`).
- Preview 1: an injected `fd_fdstat_get`, a character device (filetype 2) is a terminal.
  `--component`: the adapter over `wasi:cli/terminal-std{in,out,err}` (the five `terminal-*`
  interfaces appended LAST to `uni.wit`); serve answers errno 58; `--no-wasi` a nil stub.

No test opens a pty; ci-spec pins the piped (nil) answers.

## Output left open at the end is WRITTEN, on all four backends

A file output stream the program never closes keeps what it wrote, however the program ends
(last form, `uiop:quit` / Scheme `exit` / `emergency-exit`, uncaught condition) -- as C's `exit` and
Gauche. **SBCL does not** (an EMPTY file under `--script`, `sb-ext:exit` and an unhandled `error`
alike); ANSI leaves it unspecified, and cross-backend identity decided, since wasm writes through
`fd_write` and cannot lose it.

- Interpreter: `Environment.flushOpenStreams`, called by `RontoLispCli.interpret` in a `finally` round
  the form loop and by `repl` at the session's end. An embedder driving `LispEvaluator` directly calls
  it itself.
- JVM: `_flushStreams()V` (`JvmFlushStreamsBuilder`) before `main`'s `RETURN`, in `%host-exit`
  (`JvmExitCompiler`) and in the uncaught handler (`JvmUncaughtHandler`). **Gated on the unexpanded
  program naming `open` or `with-open-file`**; the latter because a quit in its body skips the close.

Pinned by `UnclosedOutputFileE2eTest` (all four backends; reads the file after the process
ends, since the spec corpora compare stdout only).

## Computed open options (the mode is still picked from a LITERAL)
Options passed down as values (uiop's `call-with-input-file`) lower to
`LispMacroExpander.lowerRuntimeOpenOptions`: path and every computed value bound left to right in one
`let*`, checked, then dispatched onto at most SIX literal `open` leaves. No backend learned a runtime
mode — the dispatch IS the mechanism.

- **A spec whose values are all literal never enters it** (`hasRuntimeOpenOption`)
  (`JvmLispCompilerTest#aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore`).
- **The accepted value SET is the literal path's minus the bidirectional modes and every integer
  type but the octet; only the time of the refusal moves.** A COMPUTED wide element type ("Element
  types wider and narrower than one octet") or `:io` / `:overwrite` ("`:direction :io` and
  `:if-exists :overwrite`") is refused at call time, by a measured trade; the interpreter's own `open`
  cannot tell computed from literal and takes every value.
- **Three entries, one lowering**: `expandWithOpenFile`, `OpenModes.lowerRuntimeOptions` in front of
  `Jvm/WasmOpenCompiler`, and `BuiltinFunctionWrappers.openWrapper`. The interpreter's runtime `open`
  is the fourth reading of the same table; **keep the two in step**.

Pinned by `LispEvaluatorTest#withOpenFileComputedOptions*`,
`JvmLispCompilerTest#compileAndRunComputedOpenOptions`, its WASM twin, ci-spec
`computed-stream-options`.

## WASM: a path resolves against the PREOPEN TABLE, not fd 3
`_path_dirfd` (`WasmIoRuntimeBuilder.buildPathDirFdBody`) answers the descriptor a staged path opens
relative to and leaves the path that descriptor sees in `PATH_PTR_ADDR` (248) / `PATH_LEN_ADDR` (212).
Every `path_*` import goes through it.

- Relative -> fd 3, unchanged. An absolute path no preopen covers -> fd 3 and the whole path, so the
  failure is an ordinary errno, never a trap (`.kb/wasi-component.md`).
- **A path with a `..` component CLIMBS** and resolves against the SHORTEST covering preopen: a preopen
  is a cap-std sandbox that refuses a `..` leaving it. A RELATIVE climbing path is first joined onto
  fd 3's name when that name is ABSOLUTE (not under `--dir .`); the host resolves the `..` physically.
  Hence a pointer + length answer, not a skip: a joined path is not a suffix of the staged one. The
  `--native` runner stub names fd 3 after `current_dir()` (`.kb/native-output.md`). Pinned by
  `WasmLispCompilerIntegrationTest#relativePathClimbsAboveANamedCurrentDirectory` + its
  `component` twin, and `rontolisp-native/precomp/tests/stub.rs`
  `relative_paths_climb_above_the_current_directory`.
- Absolute: matched against preopen NAMES (`fd_prestat_get`, whose EBADF ENDS the walk;
  `fd_prestat_dir_name`) as a path-COMPONENT prefix, LONGEST wins (`/tmpfoo` does not match `/tmp`); a
  relative preopen name covers no absolute path; the preopen ITSELF becomes `"."`.
- **Trap: a LITERAL absolute path tests none of this**: `CompileTimePathnameFolder` bundles the file's
  COMPILE-TIME contents into the artifact (`.kb/asdf.md`). Every pin BUILDS the path at run time.
- Adding a preview1 import raises `IMPORT_FUNC_COUNT` and `FUNC_START`, shifting every emitted
  function index (`.kb/directory-listing.md`). In step: `--no-wasi` EBADF stubs, `adapter.wat`,
  `adapter-http-server-p1.wat`.
- **`--component` adapter**: `$ensure_preopens` caches the table (16 slots at `0x50500`) and `dirfd`
  MEANS `3 + preopen index`. A COPY on purpose: `get-directories` lifts through `cabi_realloc` at the
  CORE's `HEAP_PTR`, which the core pops after every resolution. A name over 256 bytes is recorded
  with length 0, not truncated.

Pinned by `WasmLispCompilerIntegrationTest#absoluteRuntimePathResolvesAgainstThePreopenThatCoversIt`
+ its `component` twin, ci-spec `runtime-absolute-path-open-probe-and-load`.

## WASM intern table and heap base are COMPUTED, not fixed
`RT_INTERN_BASE_ADDR` (152) and `HEAP_PTR_ADDR` (84) are seeded by active data segments at
instantiation (never in `_start` — hosts can call exports without running it) from the final
static-data size: `rtInternBase = max(8192, 16-aligned end of the string segment)`,
`heapBase = rtInternBase + 8192`; the Preview 1 page count grows with `heapBase`. A fixed base let a
large program's runtime interning overwrite static strings. Pinned by
`WasmLispCompilerIntegrationTest#runtimeInternTableSurvivesLargeStaticData`.

## String streams
`with-output-to-string` / `with-input-from-string` expand over `%make-string-output-stream`,
`%make-string-input-stream`, `%string-stream-contents` (`PackageRegistry.CL_INTERNALS`), in the
with-open-file shape; same handle space as file streams.

- WASM: a **negative i31 handle** (a WASI fd is never negative); output goes to a per-stream GC byte
  buffer that DOUBLES, so a write costs linear memory NOTHING. Three traps if you move the layout: **the module-global table is the GC ROOT** a linear-memory record
  cannot be; **`_close` recycles the SLOT, not the record** (an arena free list would alias the
  reset); **a closed stream is closed** (kind 2, slot -1: a double close is a no-op, a write after one
  traps). Pinned by `WasmStringStreamArenaE2eTest`.
- **`%string-stream-contents` CLEARS the stream as it answers** (CL's contract). The interpreter
  registers `make-string-output-stream`/`get-output-stream-string` as real `LispFunction`s so `#'` and
  native-image mode work. A stream of another kind is a type-error (`.kb/error-handling.md`, "A
  wrong-type argument names its operator").
- `make-string-input-stream` exists because a library needs the stream to OUTLIVE the form that made
  it (yason's `parse`, under `lack/request`). **Trigger**: if another CL stream constructor is withheld
  for "the internal one covers every consumer", check whether a library needs it as a VALUE.
- **The two macros take their full CL spec, as ONE expansion every backend shares.**
  `:index` stores `(+ start (file-position s))` on a NORMAL exit only -- characters, a parked
  `unread-char` not counted, as SBCL. The query is synthesized inside the expression compilers, so two
  things key on the surface form (`LispMacroExpander.indexesStringInput` /
  `isIndexedWithInputFromString`): both compiled backends' `file-position` gate, and
  `UnreadCharLibrary`, which expands an indexed form ITSELF so the query becomes
  `%unread-file-position`. Pinned by `StringStreamPrograms.INDEX_PUSHBACK_PROGRAM` in the three
  backend suites. `with-output-to-string`'s string argument gets the output appended at exit, and the
  form answers the BODY's values -- so `isSingleValuedOperator` classifies it by its spec, not by name.
  A malformed spec is a CALL-time stub (`callTimeUnsupportedStub`), so a dead branch compiles.
  Pinned by `LispEvaluatorTest#evalStringStreamMacroOptions`,
  `JvmLispCompilerTest#compileAndRunStringStreamMacroOptions`,
  `WasmLispCompilerIntegrationTest#stringStreamMacroOptionsCompileAndRun` and ci-spec
  `string-stream-macro-index-bounds-and-fill-pointer-string`.
- **The direction predicates answer the REAL direction on all four** (sbcl-checked; an `:overwrite`
  open is output only, a CLOSED file stream neither). **Kept lite**: the `t` designator answers both
  (`(input-stream-p *standard-output*)` is t where sbcl says nil), and a closed STRING stream keeps its
  kind's direction on the compile paths. A `:FILE` answers from `LispLayout.STREAM_ABILITY_CELL` -- a
  cell, not a side table, because the stream check needs it on every write of every file program.
  **The gate, decided on size**: only a program that NAMES a predicate (`Ctx.asksStreamDirection`,
  which `WasmAsyncEmit.freshCtx` must carry or a TOP-LEVEL predicate falls back to `streamp`; ci-spec
  `error-output-identity-and-top-level-stream-direction`). Ungated, the dead `#'input-stream-p` wrapper
  every WASM module compiles and drops interned the kind keywords and shifted every later string;
  gating the WRAPPERS (`REFERENCE_GATED_FUNCTIONS`) shifted them too; a classifier defun was measured
  and declined. The composite constructors check their components' direction (`type-error`).
- **`file-position` of a STRING stream is real on all four**, in characters. Input: from the stream's
  own start (a bounded stream starts at 0, as sbcl); an index past the end answers nil and leaves the
  cursor (sbcl moves past it). Output: characters written since the contents were last taken; a set
  succeeds only where the stream already is. A parked `unread-char` counts as unread (one character
  here, its UTF-8 length on a file stream). **Trap**: only ONE wrapper over the pushback may exist;
  two stacked subtract twice. The JVM uses `runtime.RontoStringInputStream` for EVERY string input
  stream (so `listen` answers uniformly), and it TRAVELS. **`:end` on the compile paths is `(or (file-length s) -1)`**
  (`rewriteFilePositionArg`) and every string arm reads -1 as its end (nil would be a QUERY on the
  JVM), so a literal `(file-position s -1)` seeks a string stream's end there where the interpreter
  signals. **Limits**: `--no-wasi` answers for a string stream and nil for a file stream; a CLOSED
  string input stream still answers on both WASM backends, nil elsewhere.
- **Write-through: measured, not built.** A fill-pointer string that sees the output as it is written
  needs a stream kind of its own on every backend. No ANSI test (`WITH-OUTPUT-TO-STRING.3 .5 .6 .10`
  read the string after the form) and no corpus program (cl-who reads it after) depends on it. Also
  why `file-position` of that stream starts at 0, where sbcl starts at the fill pointer.
  **Trigger**: a caller that reads the string mid-body.
- Pinned by `LispEvaluatorTest#directionPredicatesAnswerTheStreamsRealDirection` /
  `#filePositionOfAStringStreamQueriesAndSeeks` /
  `#compositeStreamConstructorsRefuseAComponentOfTheWrongDirection`, their JVM twins,
  `WasmLispCompilerIntegrationTest#directionPredicatesAnswerTheStreamsRealDirectionOnPreview1` /
  `#componentDirectionPredicatesAnswerTheStreamsRealDirection` /
  `#filePositionOfAStringStreamQueriesAndSeeksOnPreview1` /
  `#componentFilePositionOfAStringStreamQueriesAndSeeks` (one text, `testsupport/StringStreamPrograms`),
  `JvmRuntimeClassFilesTest`, ci-spec `string-stream-direction-and-file-position`.
- Runtime `_eval` interpreters and `--no-gc` do not know string streams.
- **`listen` answers whether a character remains on a string input stream, on all four backends**
  -- not `BufferedReader.ready()`, which is true until close. An unread character counts. An echo
  stream echoes only what is READ: its `stream-peek-char` does not write (the default
  read-plus-unread would move the output position).
- Pinned by `LispEvaluatorTest#listenAtTheEndOfAStringStreamAnswersNil` /
  `#echoStreamPeekDoesNotEcho`,
  `JvmLispCompilerTest#listenAtTheEndOfAStringStreamAnswersNil` /
  `#echoStreamPeekDoesNotEcho`,
  `WasmLispCompilerIntegrationTest#listenAtTheEndOfAStringStreamAnswersNil` /
  `#echoStreamPeekDoesNotEcho`, ci-spec
  `listen-at-the-end-of-a-string-stream-and-echo-peek`.

## Binary streams and binary standard I/O
`open`'s element type `'(unsigned-byte 8)` (and every one-unsigned-octet spelling: `'unsigned-byte`,
`'bit`, `'(unsigned-byte 3)`) is this section; a wider or signed type the next section's. WASM: a WASI
fd is element-type-agnostic, so `WasmOpenCompiler` masks the mode with `& OUTPUT_BIT` (raw 2/3 would
mis-select write oflags/rights); hence `read-byte` on a text-opened stream "works" there while
interpreter/JVM signal — out of contract.

- **Binary stdin/stdout is the standard-stream DESIGNATOR**: `t` is the process standard stream, an
  explicit `nil` resolves through `*standard-input*`/`*standard-output*` (`compiler.StreamDesignators`),
  handle 2 is stderr. **Runtime dispatch is "is it a handle", never "is it nil"**, because `t` is a
  value: WASM tests `ref.test (ref i31)` (a `ref.cast` on the `t` struct would trap), the JVM
  `instanceof Long`.
- **The JVM reads `System.in` directly, not the `_stdinReader` the character reads share**: a shared
  `BufferedReader` would swallow bytes the next `read-byte` owes the caller.
- **The JVM needs an explicit flush; the other three do not.** A single-byte `write(int)` only
  flushes on `'\n'`, so `JvmLispCompiler` emits `System.out.flush()` before `main`'s `RETURN`, gated
  on the source naming `write-byte`/`write-sequence`; **any new path to `_writeByte`'s
  standard-output branch must join that gate** or its output truncates silently.
- **A raw octet moves no column** (SBCL 2.2.9,
  [standard-output-redirect.md](standard-output-redirect.md), "A stream's line start"), so `(write-byte 10 t)` +
  `fresh-line` past column 0 writes a second newline (ci-spec `binary-standard-output`).
- `--component`: in an ASYNC component `%stdin-read-byte-or-raw-f` is a raw passthrough, so octets come
  from the adapter's stdin, not the chunk buffer. Limit: an async program reading stdin BOTH as bytes
  and as lines/characters holds two host stdin streams. **Trigger**: if a pending BYTE read must
  suspend, route that nil designator to `%stdin-read-byte-f`.
- `read-sequence`/`write-sequence` are shared macro expansions into a `while` loop, so no per-backend
  codegen exists for the loop. The keyword tail is read the way a lambda list reads one (CLHS 3.4.1.4,
  `parseSequenceArgs` over `keywordTailProblem`), and a bad tail is a `program-error` the CALL
  signals -- never an expansion-time refusal, which loses the whole enclosing form. A packed buffer is
  first offered to `%read-sequence-packed`/`%write-sequence-packed` (`.kb/binary-sequence-io.md`), a
  character buffer to `%read-sequence-chars` (`.kb/character-sequence-io.md`). A LIST buffer works.
  **The stream picks the element** -- see that section.
- **A provably byte-only buffer skips the dispatch** (`compiler/SequenceIoNarrowing`). **When a
  string stream can reach the site** (`%character-stream-p` spliced) an element-type-`t` buffer may
  receive characters, so only a NUMERIC-typed buffer narrows (ci-spec
  `read-sequence-into-a-character-buffer`). Runs backend-locally after the gate scans, never in
  `CompileFrontend`: the gates that emit the packed runtime key on the unexpanded spelling. **It sees
  only the top-level forms, never a defun**, so `characterStreams` and `wide` come from the backend's
  function table; reading `wide` off the forms made it always false and a narrowed octet buffer read
  raw octets from an `(unsigned-byte 16)` stream
  (`JvmLispCompilerTest#compileAndRunANarrowedSiteKeepsTheWideGuard`, its WASM twin).
- The `_eval` interpreters know none of this, nor `require`/`provide` (a file read by the runtime
  `load` of compiled output must not contain them — `.kb/load-inliner.md`). The `CiSpecE2eTest`
  driver passes `--dir . --dir /tmp` to both wasmtime invocations.

## The stream picks the element

CLHS: `read-sequence` / `write-sequence` move elements of the STREAM's element type. On all four
backends (sbcl-checked):

- a STRING buffer moves characters on any stream;
- any other buffer -- a general vector, a fill-pointer vector, a LIST -- moves characters on a
  STRING stream (`%character-stream-p`: kind `:STRING-INPUT` / `:STRING-OUTPUT`), bytes on every other.

- **One prelude defun, not an inline test** (no per-site copy). `%character-stream-p` is selected
  (`LibraryDefunPruner.SYNTHESIZED_ENTRIES`) when the program names the operators (or their `-raw`
  aliases) AND can build a stream value (`mayCreateStreamValues`); otherwise every stream is a
  bivalent standard stream, the buffer alone decides, and the expansion is unchanged. The interpreter
  (`evalSequenceWithGrayDispatch`) and gray.lisp's `%gray-read-sequence-dispatch` /
  `%gray-write-sequence-dispatch` hand the built-in the stream VALUE, not the unwrapped handle
  (`JvmLispCompilerTest#compileAndRunAStringStreamPicksTheElementThroughTheGrayDispatchers`).
- **A standard stream keeps the buffer rule**: it is bivalent (sbcl answers characters for a general
  vector from `*standard-input*` and bytes for an octet vector).
- **A FILE stream keeps the buffer rule -- measured, not chosen.** Telling a character file from a
  binary one needs the element-type registry (next section) or a distinct kind keyword; both cost
  corpus-wide bytes for no ANSI test (every failing test reads a string stream). The ability cell does
  know, but a sequence operator's stream check does not see the buffer. **Trigger**: an ANSI test, or
  any corpus program, reading a general vector or a list from a character FILE stream.
- The first-class `#'read-sequence` / `#'write-sequence` / `#'write-string` wrappers
  (`BuiltinFunctionWrappers.boundedSequenceIo`) expand ONE call with `:end (getf kw :end)`.
- **The argument check**: every expansion first calls `%check-sequence-bounds`, on all four backends
  -- a dotted-list buffer, a bad bound, or a range outside the buffer is a `type-error`, as in SBCL.
  One defun for the per-site reason above; the Gray dispatchers (and the interpreter's
  `evalSequenceWithGrayDispatch`, which bypasses the expansion) call it too. `constructsInstance`
  counts the operators and wasm's layout scan keeps the `type-error` tag on their presence (the
  `FILE_ERROR_SITES` situation). The cost is FIXED per program (the `type-error` machinery); no
  narrower gate keeps the guarantee. Two traps: the expansion calls it only when it is in the function
  table (a direct backend compile without the front end never splices it;
  `binaryStandardStreamDesignators`); a quantized-matrix buffer is answered a nil length, since its
  transfers count block bytes no Lisp-level reader measures.

Pinned by `LispEvaluatorTest#readAndWriteSequenceMoveTheElementTheStreamCarries`,
`#readAndWriteSequenceReadTheirKeywordTailTheWayALambdaListDoes`, their JVM twins,
`WasmLispCompilerIntegrationTest#readAndWriteSequenceMoveTheElementTheStreamCarriesOnPreview1` /
`#componentReadAndWriteSequenceMoveTheElementTheStreamCarries`, `SequenceIoNarrowingTest`, ci-spec
`read-and-write-sequence-move-the-element-a-string-stream-carries`. The argument check by
`LispEvaluatorTest#readAndWriteSequenceSignalTypeErrorForABadSequenceOrBound` /
`#readSequenceOnAGrayStreamValidatesItsBoundsToo`, their JVM twins, the Preview 1 and component
twins of the first, `SequenceBoundsFixture`, and ci-spec
`read-and-write-sequence-signal-type-error-for-a-bad-sequence-or-bound`.

## `write-line` takes `:start` / `:end`

**Invariant: `(write-line string [stream] :start s :end e)` writes the bounded substring plus the
newline and returns the whole string, in call position and first class, on all four backends.** The
keyword tail follows the sequence-I/O rule (`LispMacroExpander.keywordTailProblem`, shared with the
interpreter's first-class validator so the two cannot disagree).

- `LispMacroExpander.lowerWriteLineBounds` rewrites a keyword call into `write-string` with the
  original tail plus `terpri` -- the Gray protocol's own write-line shape. The keyword-free shape
  keeps its dedicated path (the WASM literal-print fold, the socket writes), byte-identical. First
  class: `BuiltinFunctionWrappers.writeLineWrapper`.
- A Gray instance honors the bounds after ONE check (`.kb/gray-streams.md`, "Bounds on `write-line` /
  `write-string`").
- A bounded `write-line` to a socket stops at `terpri`, which has no socket arm on any backend.
- ANSI `WRITE-LINE.4-.10` print the right bytes but fail on a `#.` expectation form the runner
  compares unevaluated (harness gap).
- Pinned by `LispEvaluatorTest#evalWriteLineTakesStartAndEnd`,
  `JvmLispCompilerTest#compileAndRunGrayWriteLineHonorsItsBounds`,
  `WasmLispCompilerIntegrationTest#grayWriteLineHonorsItsBounds`, ci-spec
  `write-line-start-end-keywords`, `write-line-takes-start-and-end`.

## Element types wider and narrower than one octet

An integer `:element-type` opens a binary stream whose ELEMENT is a fixed number of little-endian
octets, two's complement when the type admits negatives; `stream-element-type` answers the widened
type; `file-length` / `file-position` count elements. One classification, `macro/StreamElementType`
(over `macro/IntegerTypeRange`), read by all four backends.

**sbcl's layout (checked): no packing, no bias.** A type needing b bits takes 1 / 2 / 4 / 8 octets for
b <= 8 / 16 / 32 / 64 and `ceil(b/8)` beyond (`(unsigned-byte 100)` -> 13 octets, answered as
`(unsigned-byte 104)`); `(unsigned-byte 1)` and `(integer 100 200)` write the raw value in one octet;
`bit`, `unsigned-byte` and `(mod n)` are `(unsigned-byte 8)`, `signed-byte` is `(signed-byte 8)`, an
`(or ...)` takes its hull. So every descriptor keeps moving octets and the element is composed above
it. sbcl signals on `stream-element-type` of a CLOSED stream; ours answers `character` on all four,
because close forgets the entry.

- **Compile paths: a REGISTRY in prelude Lisp (`%file-stream-*`, `%wide-*`), not a runtime mode**;
  the interpreter keeps `Environment.streamElementTypes`. The packed arm of every sequence expansion is
  guarded by `%wide-width` (`guardPackedSequenceForWideStreams`), since the packed primitive moves raw
  octets. The helpers reach the primitives through internal names the lowering does not rewrite again
  (`%read-octet`, `%write-octet`, `%file-octet-length`, `%file-octet-position`); **every name-keyed
  runtime gate still sees the public name** (the JVM `FileMeta` / stdout-flush gates, the WASM
  `file-position` import).
- **Keyed by the handle, forgotten at close -- both measured, not chosen.** Checking the stream VALUE
  fails: gray.lisp's dispatchers resolve `%stream-target` FIRST and pass the raw handle. A stale entry
  on a reused descriptor is prevented at close. Closing a SYNONYM forgets nothing; a raw handle, which
  the Gray close dispatcher can pass, forgets.
- **Two SURFACE selection facts**: the wide half on `LispMacroExpander.opensWideElementStream` (a
  literal wide `:element-type`); the registry also on "names `stream-element-type` AND `open` /
  `with-open-file`" (so an octet stream answers `(unsigned-byte 8)`). A wide leaf compiled without the
  registry signals at call time (`wideElementTypeUnavailableStub`) rather than open octets.
- **Literal-only on the compile paths, by the `:io` trade.** A computed wide type would put every uiop
  wrapper and `#'open` behind the helpers plus a runtime `StreamElementType.of`: a large fixed size step
  for four ANSI tests (`FILE-POSITION.7` `.8`, `OPEN.65`, `MAKE-TWO-WAY-STREAM.13`) and no corpus
  caller. **Trigger**: a real caller that computes a wide `:element-type`.
- **An octet spelling emits exactly `'(unsigned-byte 8)`** (`elementTypeLiteral`)
  (`JvmLispCompilerTest#theOctetElementTypeSpellingsCompileToTheSameBytesAsUnsignedByte8`).
- ANSI `STREAM-ELEMENT-TYPE.2-.4` rely on the integer-interval `subtypep` rule in
  [declarations-type-checks.md](declarations-type-checks.md).

Pinned by `LispEvaluatorTest#wideAndNarrowElementTypesRoundTripTheWaySbclStoresThem`,
`#aClosedFileStreamForgetsItsElementTypeButClosingASynonymDoesNot`,
`JvmLispCompilerTest#compileAndRunWideAndNarrowElementTypes` /
`#compileAndRunStreamElementTypeOfAnOctetFileStream` / `#compileAndRunWideElementTypesThroughTheGrayDispatchers`,
`WasmLispCompilerIntegrationTest#wideAndNarrowElementTypesOnPreview1` /
`#componentWideAndNarrowElementTypes` / `#streamElementTypeOfAnOctetFileStreamOnPreview1`,
`StreamElementTypeTest`, ci-spec `wide-and-narrow-stream-element-types`.

## Component stdin (stdin.lisp over wit-imported `wasi:cli/stdin@0.3.0`)
On `--component`, an ASYNC program that reads stdin (not serve) gets `stdin.lisp` +
`stdin-dispatch.lisp` spliced by `eval/StdinLibrary`. The interface is bound FROM the fixed import
block (`WasmComponentBuilder.FIXED_BLOCK_IFACES`; `validateFixedMembers`), so the WIT world is
unchanged and no new `-S` flag exists. It is the preview1 adapter's stdin cache in Lisp: ONE
`read-via-stream` stream, a chunk buffer, and async-defuns so a pending stdin read SUSPENDS the task
(`WasmSocketsRewrite`). EOF: `read-line` -> nil; `read-char` signals the CLASS `end-of-file`
(`LispMacroExpander.endOfFileSignal`), not a look-alike message.

**The eof-argument reads drain the same buffer**: reading fd 0 there would skip what the chunk
buffer already holds.
Pinned on the interpreter, JVM, Preview 1 and the component by `ReadLineValuesFixture.ASYNC_STDIN_EOF_*`
(SBCL 2.2.9's answers). `read-byte` stays raw.

**A NON-async stdin program is deliberately NOT migrated**: it keeps the adapter's `fd_read` branch,
byte-identical (`componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags`). Under serve (no stdin in the
wasi:http world) `stdin-stub.lisp` supplies raw passthroughs. Limits: reads buffer one host chunk; a
migrated program that ALSO consumes stdin through forms the rewrite leaves native holds TWO host stdin
streams.

## The stream table is CONCURRENT on the interpreter and the JVM
`http-handler`/`serve` run one virtual thread per request (`.kb/mutexes.md`) over a process-wide
table, so the allocator is atomic: "reserve `count`, then store" hands two requests the SAME handle
(against PostgreSQL: random connection loss inside the trust-auth handshake).

- Interpreter (`Environment.createGlobal`): a `ConcurrentHashMap` plus an `AtomicLong`. Never put a
  `null` value in it — `close` REMOVES.
- JVM: every producer appends through the ONE allocator `_addStream(Object) -> Long`
  (`ACC_SYNCHRONIZED`), so **a new stream-producing built-in MUST call it**. `_closeStream` is
  synchronized with it, `_streams` is `ACC_VOLATILE`. Producers build the stream BEFORE calling it, so
  a connect or file open stays outside the lock.
- WASM: single-threaded by construction.

Wider rule `.kb/concurrent-served-requests.md`. Pinned by
`HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` and its `HttpHandlerJvmTest` twin
(fixture `StreamHandleConcurrencySupport`: 24 simultaneous requests x 3 rounds).

## `file-length`, `file-write-date`, and the three write-side operators
**`file-length` is REAL on ALL FOUR backends, and `nil` only where a stream genuinely has no length.**
Interpreter and JVM keep a path side table (a `Reader`/`Writer` does not remember its path) filled by
`open` only, and flush an output stream first so the answer counts what was WRITTEN. WASM stats the
descriptor (`fd_filestat_get`, `WasmFileLengthCompiler`), nil for the same set: a string or standard
handle, an errno, a non-regular file.

**Trap: a `filestat` staged at `HEAP_PTR` must be rounded UP to 8 first** — it has u64 fields, so
wasmtime REFUSES an unaligned buffer (`Pointer not aligned to 8`). Advance over it for the call and pop
after (the `_open` discipline, load-bearing under `--component` because the adapter allocates through
`cabi_realloc` at that cell). `adapter.wat` re-encodes the preview1 `filestat` from a SYNC-lowered
`descriptor.stat` (result at `0x51600`; dev/ino zero-filled; `$p1_timestamp`).

**`file-write-date` is REAL on all four backends.** It names a PATH, so WASM stats it through an
INJECTED `path_filestat_get` (`.kb/wasm-import.md`), gated on the program naming it (or `%file-stat`),
and answers `mtim / 1e9 + 2208988800` truncated toward zero (a SIGNED division: the adapter's
`$p1_timestamp` of a time before 1970 is negative ns), nil on an errno or an `mtim` of 0 (the JVM's
`lastModified() == 0`). A file dated 1960-06-01T00:00:00.5Z answers 1906502401 on the interpreter, the
JVM and `--component`; **Preview 1 answers nil** (wasmtime 51.0.0 reports `mtim` 0 for any time
before 1970, measured with `%file-stat` too, so no Lisp-side read can tell it from 1970-01-01).
`Files.setLastModifiedTime` reads a pre-1970 time as the epoch on Linux (JDK 25): a test stages such a
file with `touch -d` (`CorpusFixtures.writePre1970File`). The serve bridge answers errno 44, so a served program's date is nil.

**`%file-stat` / `%file-set-times`** (`stat` / `lstat` with no file opened; the Java subset's files
are their one caller, [java-frontend.md](java-frontend.md)) are real on all four; creation time and
permission bits are nil on both WASI backends. The interpreter's `BasicFileAttributeView` opens the
file, so a file the program may not read takes its modification time through `File.setLastModified`.
WASM reads a timestamp signed and writes a time below zero as 0 (Preview 1's is unsigned). wasmtime
51.0.0: both calls serve a mode-000 file and directory, a link to an absolute path is errno 63, a
pre-1970 time reads 0 on Preview 1.

The JVM side is GATED per operator (`JvmIoRuntimeBuilder.FileMeta`); `#'file-length` /
`#'file-write-date` are in `REFERENCE_GATED_FUNCTIONS` because their wrapper bodies call those
helpers and the gate scans the SOURCE program. The WASM side is not gated — `--optimize` drops the
body and the import when unused.

**Three write-side operators share one shape: prelude Lisp over one primitive that answers nil rather
than signalling**, so "a failure is a `file-error`" lives once in the Lisp above it. CL's extra values
are not returned — a prelude defun's secondary value does not cross the function boundary on the
compile paths.

- `ensure-directories-exist` over `%make-directories` (WASM `_make_directories`,
  `WasmMakeDirectoriesCompiler`). Preview 1 creates ONE level per call, so the body walks the prefixes.
  A nonzero final WASM errno is VERIFIED by opening the path as a directory, which turns "already
  there" into T whatever errno the host used; the JVM re-checks `File.isDirectory()` after `mkdirs()`
  (whose false means both "already there" and "refused").
- `delete-file` over `%delete-file` (`WasmDeleteFileCompiler`). **An EMPTY directory is removed on all
  four**, as `Files.deleteIfExists` / `File.delete` do: when unlink refuses, WASM calls the injected
  `path_remove_directory` with the path minus ONE trailing slash -- wasmtime refuses `rmdir("dir/")`.
  `uiop:delete-empty-directory` / `delete-directory-tree` and Clojure's `.delete` ride it.
- `rename-file` over `%rename-file` (`WasmRenameFileCompiler`, `TYPE_PATH_RENAME`); the new name is
  MERGED with the old one, so a bare file name keeps the directory.
- `adapter-http-server-p1.wat` answers all three errno 76 (the serve world has no filesystem), so the
  Lisp callers signal identically there.

**`uiop:read-file-string` must NOT size its buffer from `file-length`**: prelude Lisp over
`with-open-file` + a CHUNKED `read-sequence` loop, both properties load-bearing. **The loop stops on
the first SHORT read, so EOF is read at most ONCE** — a SECOND read past EOF traps on the
`--component` backend alone, because `adapter.wat`'s `$fd_read` calls `stream.read` after the
writable end dropped (`cannot read after being notified that the writable end dropped`). Any slurp
loop hits it. **Trigger**: the adapter answering 0 bytes/EOF idempotently.

Pinned by `LispEvaluatorTest#evalFileWriteDateAndFileLength`/`#fileLengthOverEveryStreamKind`,
`#renameFileMovesTheFileAndSignalsWhenItIsNotThere`, their JVM twins,
`WasmLispCompilerIntegrationTest#fileWriteDateAndFilesystemWritesRunForReal`/
`#fileLengthAnswersTheSizeOfARealFile`/`#componentFileLength`/
`#uiopFilesystemProbeReadsAndMutations`, ci-spec
`file-length-of-a-file-of-a-known-size`,
`filesystem-write-create-rename-delete-and-probe` and
`file-write-date-and-deleting-an-empty-directory`.

## `file-position` is REAL on ALL FOUR backends for every FILE stream

Binary, character and bidirectional alike, query and set. A binary stream counts in elements, a
character stream in BYTES -- sbcl's answer: a character advances it by its UTF-8 length, a line
`read-line` returned ends after its terminator (both bytes of a CRLF, although the line drops the CR),
a character `peek-char` looked at or `unread-char` pushed back is not consumed yet, and an
`:if-exists :append` stream starts at the end of the file. A socket, a standard stream and a closed
handle answer `nil`; a STRING stream its character position ("String streams"). The served-request
body keeps its own REAL `file-position` (`HttpRequestBodyStream`).

- **Interpreter / JVM**: a binary stream keeps a per-handle position side table (a `read-byte` at the
  end advances nothing; ci-spec `file-position-round-trips-on-a-binary-file-stream`). A character
  file is `runtime/RontoCharFileReader` / `RontoCharFileWriter` -- always on the interpreter; on the
  JVM they TRAVEL, gated on `FileMeta.characterPosition` (names `file-position` AND
  `LispMacroExpander.mayOpenCharacterFileStream`), so a program whose every `open` is literally binary
  keeps its single class file. A parked `unread-char` is subtracted (query) or dropped (set).
- **The two classes carry the whole mechanism**: each extends `BufferedReader` / `BufferedWriter`, so
  every existing dispatch takes them with no arm, but each decodes / encodes UTF-8 over its OWN byte
  buffer, so the position is the channel's less (reader) or plus (writer) what is buffered. A
  `BufferedReader` over an `InputStreamReader` cannot answer this: the decoder reads ahead, neither
  layer says how many bytes the characters took, and `readLine` hides `\n` vs `\r\n`. The reader
  consumes a CRLF WHOLE, decodes a malformed sequence to U+FFFD over its valid prefix, and drops its
  mark past the limit `mark(n)` asked for (else a `peek-char` pins every later byte); the writer
  encodes an unpaired surrogate as `?` -- `FileReader` / `FileWriter`'s replacements.
- **Cost floor (measured, accepted)**: the JVM gate scans post-splice, so a library-internal
  reference ships the two classes -- load-bearing, since the library takes its stream from ITS caller.
  No cheaper sound gate exists (no per-site stream dataflow; one class cannot extend both
  superclasses); splitting reader/writer by open direction would save one class at most.
- **Both WASM backends: the descriptor offset IS the position**, through ONE injected import each,
  gated on the program naming `file-position` ([[wasm-import]], "This, not a new index-pinned preview1
  slot"). Nothing buffers ahead of it: `_read_line` / `_read_char` read ONE BYTE per `fd_read`, writes
  go straight through, and `read-sequence`'s bulk character path asks for at most one byte per
  character still wanted (`WasmCharIoRuntimeBuilder`). Two corrections: the query subtracts the bytes
  the fd's byte pushback holds (the set drops them); an APPENDING open moves the descriptor to the end
  at the `open` call site (`WasmOpenCompiler`), because an append descriptor sits at 0 until its first
  write.
- **Preview 1**: one `fd_seek` serves both directions. **The read-mode `path_open` asks for `FD_READ`
  alone and `fd_seek` still works** -- wasmtime's preview1 does not enforce rights; a host that did
  would answer `ENOTCAPABLE` (read as `nil`), and the fix would be the read rights
  `FD_READ|FD_SEEK|FD_TELL` = 38.
- **`--component`** has NO cursor (WASI 0.3 reads are offset-based), so the adapter tracks a per-fd
  byte offset and exports `file_position_get` / `file_position_set`, EBADF below 100 or for a dead
  slot; serve sees the same. Its `path_open` still resets an unread per-fd slot at `0x51a00`, left
  alone because touching `adapter.wat` means regenerating the adapter blobs.
- **`--no-wasi`**: the operator compiles to `nil`.
- `uiop/os:parse-windows-shortcut` / `parse-file-location-info` navigate the `.lnk` with
  `file-position` on every backend, WASI included. Pinned against a fixture built at run time with
  `write-byte` by `WasmLispCompilerIntegrationTest#uiopOsHostIdentityAndGetenvOverrideCompileAndRun`,
  and against the shipped `src/test/resources/lnk/sample.lnk` by
  `LispEvaluatorTest#evalUiopOsWorkingDirectoryAndTheWindowsShortcutFamily`,
  `JvmLispCompilerTest#compileAndRunUiopOsHostIdentityAndGetenvOverride` and ci-spec
  `uiop-os-host-identity`.

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

The two modes the existence guard cannot express, because each opens the file DIFFERENTLY: `:io` reads
and writes one file through one cursor, `:overwrite` writes from 0 without truncating. Both are the
same stream (an `:overwrite` open is an `:io` one whose read half goes unused), and `file-position` on
it is real whatever its element type. sbcl: a fresh `:io` open answers position 0, `CHARACTER`, both
direction predicates `t`; `write-sequence "wxyz"` over `abcdefghij` under `:overwrite` leaves
`wxyzefghij`.

- **Interpreter and JVM run ONE class**, `runtime/RontoIoFileStream` (a `RandomAccessFile`, UTF-8 by
  hand so the byte cursor is never ahead of what was consumed; it TRAVELS). It extends `Writer`, so
  every OUTPUT dispatch takes it with no arm; reads, bytes and position are explicit arms. End of file
  is `ready()`, never a null line: the class cannot spell `@Nullable`.
- **Preview 1** opens ONE descriptor (`WasmOpenCompiler.wasmMode` 3 overwrite: neither `O_CREAT` nor
  `O_TRUNC`; 4-6 the `:io` three, adding `FD_READ` to the write rights).
- **`--component`** has no cursor, so the adapter writes through `descriptor.write-via-stream` AT its
  tracked per-fd offset (`adapter.wasm` and `import-block.bin` regenerated by `regen.sh`); only an
  APPEND fd keeps `append-via-stream`. After every write the cached readable stream is dropped and the
  EOF latch cleared, or the read after a seek replays stale bytes. `$path_open` derives
  descriptor-flags from the requested RIGHTS, not oflags, since `:overwrite` writes with oflags 0.
- **`file-position`'s `:start` / `:end` are ONE shared call-site rewrite on the compile paths**
  (`LispMacroExpander.rewriteFilePositionArg`). A COMPUTED position is resolved at run time, because
  the Gray dispatcher and `#'file-position` pass the designator down as a value (a literal-only rewrite
  threw `ClassCastException` in `JvmDeadMethodEliminationCorpusTest`). `:end` introduces a
  `file-length` call the source never names, so `FileMeta.fileLength` also keys on
  `filePositionMayNeedLength`.
- **`:io :append` only STARTS the cursor at the end** on the interpreter/JVM (one cursor serves the
  reads too), where CL's `:append` sends every write to the end; the WASM backends append for real.
  No test writes after seeking an appending `:io` stream.

**Gate, and the measured trade that made computed values literal-only.** The JVM arm, the travelling
class and the WASM `_open` body key on `LispMacroExpander.opensBidirectionally` (a LITERAL `:io` /
`:overwrite`). A COMPUTED one grows the dispatch from six literal leaves to fourteen and puts the class
in every uiop file wrapper; nothing observed passes one computed, so it is refused at call time.
**Trigger**: a real caller that computes `:io` / `:overwrite` -- widen the two `unlessValueIn` sets and
the gate together.

Pinned by `LispEvaluatorTest#openDirectionIoReadsBackWhatItJustWroteThroughOneCursor` /
`#openDirectionIoTruncatesByDefaultAndCarriesTheByteHalf`,
`JvmLispCompilerTest#compileAndRunOpenDirectionIoAndOverwrite`,
`WasmLispCompilerIntegrationTest#openDirectionIoAndOverwriteOnPreview1` /
`#componentOpenDirectionIoAndOverwrite`, `JvmRuntimeClassFilesTest`, ci-spec
`open-direction-io-and-if-exists-overwrite`; byte identity of the rest by
`JvmLispCompilerTest#aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore`.

## A stream is a VALUE, not a handle
**Every OPEN stream is an instance of the fixed `LispLayout.STREAM` layout** — tag `%STREAM`,
declared slots `HANDLE` and `KIND` — so `streamp` answers off the value, and `file-stream` /
`string-stream` are exact everywhere. A LAYOUT ONLY in `ClosRegistry` (the `%PATHNAME` precedent):
`%obj-new`/`%obj-is` resolve the tag on all four backends while the type joins no `typep` tag table,
no `structure-object`/`standard-object` enumeration and no `%class-slot-defs` answer.

- **The HANDLE is a declared slot**: `equal` is structural over DECLARED slots and CL's `equal` on
  streams is `eq`, so with the kind alone any two file streams would compare `equal`. The number is
  backend-local, so the layout is `OPAQUE` and a stream PRINTS as `#<STREAM>` everywhere
  (`.kb/emitted-output-determinism.md`, `.kb/instance-syntax.md`). A test that must tell two streams
  apart uses `equal` in-program (`StreamHandleConcurrencySupport`).
- Reserved cells past the declared slots (pushback `.kb/gray-streams.md`, path `.kb/pathnames.md`,
  ability `.kb/error-handling.md`) are invisible to printing and `equal`. The KIND keyword is compared
  with `equal`, not `eq`.
- **`*error-output*` holds one** (`:STANDARD` over handle 2). `*standard-output*` / `*standard-input*`
  keep the `t` DESIGNATOR. A Clojure `*out*`/`*in*` is a value whose handle IS `t`
  (`.kb/clojure-frontend.md` "Streams as values").
- **One gate, both halves**: `LispMacroExpander.mayCreateStreamValues(program)` answers
  `Ctx.usesStreamValues`, gating the WRAP a producer emits AND the UNWRAP a consumer emits, so the two
  can never disagree. It also forces `mayCreateInstances` on.
- **An integer is no stream** (`.kb/error-handling.md`, "A stream operator's argument is a stream"),
  except in the next bullet's case.
- **A `with-output-to-string` the gate never saw**: a seeded condition's `:report`
  (`no-applicable-method-error`, `unbound-slot`) renders through one `expandTypedSignal` writes while
  the body compiles, in a program the gate answered no for. Its stream is a raw handle, consistent
  with every consumer there except `close`, so the backends pass `usesStreamValues` and that close is
  `%close`. Turning the gate on instead would move every program with a generic function to stream
  values for a line only a failure prints. Pinned by `UncaughtReportParityTest` /
  `WasmReportLocationsTest#aConditionOnlyTheRuntimeSignalsRendersItsReport`.
- **Producers wrap in the BACKEND, not in an expansion** (`JvmObjCompiler.emitWrapStream`,
  `WasmInstanceCompiler.emitWrapStream`), so the I/O runtime helpers keep their exact bodies — on wasm
  their `FUNC_*` indices are fixed and the `--component` adapter blobs depend on them.
- **Consumers unwrap at ONE seam per backend**: `StreamDesignators.throughStream` (`streamDesignator`
  without the `*standard-output*` designator rule, for consumers taking their argument as written);
  interpreter `Environment.streamTarget`.
- **The prelude splice of `%STREAM-TARGET` is best effort**: a stream value can arrive from a form
  injected AFTER selection (the condition renderer, the print-object seam), so both seams fall back to
  `StreamDesignators.throughStreamInline`. **`sockets.lisp` carries its own `%sock-handle`** and the
  WHOLE resolution, because the component socket splice is used by pipelines without prelude selection.
- **Stream TYPE names are all EXACT.** `file-stream`/`string-stream` are
  `LispMacroExpander.makeStreamKindTest`. **The test must be a `let` plus a nested `if`, tag test
  first**: `%obj-ref` on a non-instance is undefined on the compile paths. `readtable` is
  `(%obj-is x '%READTABLE)` ([readtables.md](readtables.md)). All are in `PackageRegistry.CL_TYPES`
  and `LispMacroExpander.makeTypeTest`; **a name in the first without a case in the second is a hard
  expansion error in `typecase`, not a silent nil.** `subtypep` places them below `stream`
  (`.kb/gray-streams.md`).
- **Open or closed is a fact about the VALUE on wasm.** A WASI descriptor has no table behind it and
  the host reuses the lowest free one, so a second `close` would close -- and forget the registry
  entries of -- whatever stream reused it. `WasmExprCompiler.compileClose` wraps every `close` /
  `%close-raw` in `LispMacroExpander.markingClose` (the first close sets `STREAM_CLOSED_CELL`, a later
  one answers t and touches nothing; the `*error-output*` value is never marked -- a standard stream
  outlives a close, ci-spec `error-output-designator`), and `open-stream-p` reads it
  (`expandOpenStreamPOnValue`, a synonym resolved to its target VALUE first). The interpreter and the
  JVM answer from their table. Every read, write and flush refuses a closed stream off the same answer
  (`.kb/error-handling.md`, "A closed stream and a stream of the wrong direction"). Pinned by
  `WasmLispCompilerIntegrationTest#openStreamPAnswersNilAfterCloseOnPreview1` /
  `#componentOpenStreamPAnswersNilAfterClose` (both splices included) and the two ci-spec
  cases above.

Pinned by `LispEvaluatorTest#evalStreamp`/`#theStreamAndReadtableTypeNamesResolve`,
`JvmLispCompilerTest#compileAndRunTheMissingStandardNames`, its WASM twin, ci-spec
`a-stream-is-a-self-describing-value`.

## Synonym streams
**A synonym stream is a distinct VALUE forwarding EVERY operation to the current value of the symbol
it names — for any symbol.** `LispLayout.SYNONYM_STREAM`: ONE declared slot holding the symbol, ONE
RESERVED cell holding a zero-argument closure that reads the variable (`expandMakeSynonymStream`; a
COMPUTED symbol falls back to `symbol-value`). The closure is why `symbol-value` — a force of the whole
eval runtime (`.kb/symbol-runtime-api.md`) — is not in the lowering. It sits outside `slotNames`, so
it reaches neither the printers (`#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>` on all four) nor `equal`.

**`%STREAM-TARGET` resolves BOTH kinds**, recursively; a cycle is the only thing that cannot resolve.
Callers beyond the two seams: interpreter `Environment.synonymTarget`, applied BEFORE the
`instanceof LispInstance` test by every Gray-dispatching built-in wrap (`resolveSynonymArg`);
**`gray.lisp`'s `%gray-*-dispatch` helpers**, which resolve FIRST because a synonym is an instance too
and would otherwise take the CLOS arm and die on "no applicable method" (rove); **the `close` guard
exists TWICE on wasm** — at `close` and at the `%CLOSE-RAW` alias the `--component` socket rewrite
falls through to, or a component hands the synonym to the handle-typed close and traps; and **the
`--component` spliced dispatchers**, which REPLACE the read built-ins so the compiler's seam never sees
those call sites.

**Two exceptions in gray.lisp**: `%gray-close-dispatch` does not resolve a synonym and so tests
`%STREAM` by tag ahead of `%obj-p`; the three PREDICATE dispatchers hand the ORIGINAL designator to the
built-in.

**Gating**: the SYNONYM arm on `make-synonym-stream` in the source (`Ctx.usesSynonymStreams`; no read
syntax), the `%STREAM-TARGET` call on `usesSynonymStreams || usesStreamValues`. **Two LIBRARY splices
pay unconditionally**: gray.lisp's dispatch helpers and the `--component` I/O dispatchers. Hence a
program using the GRAY protocol splices `%STREAM-TARGET`, and **a pipeline that splices gray.lisp must
run `LispPreludeLibrary.process` too** (the harnesses' `compileAndRunGray`). The component splices run
BEFORE prelude selection.

Pinned by `makeSynonymStreamResolvesTheNamedVariable`/`makeSynonymStreamIsAStreamValue`/
`synonymStreamOverStandardOutputFollowsALaterBinding`/
`makeSynonymStreamOverStandardInputFollowsALaterBinding`/
`synonymStreamOverAUserSpecialFollowsALaterBinding` (JVM + WASM), their `eval*` twins, ci-spec
`synonym-stream-value`.

## Load-context variables and `load` options
`*load-pathname*` / `*load-truename*` hold the file being loaded on EVERY backend: the interpreter's
`loadFile` binds them per file (a COMPONENT by its resolved path, a plain `load` by its spelling;
nothing absolutizes); the compile paths ASSIGN them per SPLICED file from `LoadInliner`'s
`%begin-file` brackets, so the value agrees byte for byte and equals `asdf:component-pathname`.
**Established at READ time too** (`loadFile` before its read, `UserMacroExpander` around the spliced
forms): otherwise a `#.` reading `*load-truename*` answers nil, since the bracket's `setq` runs long
after the datum resolves. Mechanics/gate/tests `.kb/load-inliner.md`; the family `.kb/asdf.md`.

`*load-verbose*` / `*load-print*` are nil and proclaimed special on every backend. `load`'s
`:if-does-not-exist` is REAL; `:verbose`/`:print`/`:external-format` are accepted and dropped, every
value still BOUND in written order (`LispMacroExpander.lowerLoadOptions`, an `(or <value>
(%probe-file path))` guard; an UNREADABLE file answers nil too). The guard is built after prelude
selection, so it calls the primitive and splices nothing.

The SUCCESS value is `t` on every backend. Pinned by `LispEvaluatorTest#loadAcceptsTheKeywordOptions`,
ci-spec `computed-stream-options`.
