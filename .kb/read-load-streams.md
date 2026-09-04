# `read`/`load`, `read-line`, file streams in all three backends

A runtime reader is emitted into compiled output (like `eval`). Interpreter `LispReader`/`Files`;
JVM recursive-descent `JvmReadRuntimeBuilder`; WASM `WasmReadRuntimeBuilder` over linear memory,
interning symbols to shared string offsets.

## Emitted reader: frontend parity, else SIGNAL

**Invariant: the emitted reader has FRONTEND PARITY; anything outside it SIGNALS, never misreads.**
`buildReadExpr` (JVM) / its WASM twin cover the frontend lexer's whole set: `(` (dotted pairs), `'`,
`"`, `)`, atoms (symbol / integer / bignum (JVM) / double / ratio / nil / t, leading `+` consumed
before a digit), and the `#` mirror -- `#'`, `#\` (the frontend's 9-name table, case-insensitive),
`#(...)`/`#nA(...)`, `#*` bit vectors (a general vector), `#f(`/`#d(` packed float arrays (`--simd`
builds the VBLOCK layout), `#S(...)`, `#P"..."` (instance over the fixed PATHNAME layout; the arm
signals with the instance gate off), `#x`/`#o`/`#b`, nesting `#|...|#` in the whitespace skipper.

- Unclaimed tokens fall to the atom path like the frontend's `readSymbol`: `#foo` -> `#FOO`,
  `#:g` -> `#:G`, `#16r1f` -> a symbol.
- PERMANENT limits: `#.`, `#+`/`#-`, `#n=`/`#n#` labels signal a catchable error. The interpreter's
  runtime read still resolves the first two and reads labels -- the ONE documented
  interpreter/compiled divergence; `#.` EVALUATES via the marker resolver `LispEvaluator` installs
  into `Environment.setReadTimeEvalResolver`, gated on `*read-eval*` (`.kb/reader-features.md`).
- Errors: JVM `RuntimeException`, caught as `simple-error` with the frontend's EXACT messages
  (`LispEvaluator.foldStructLiteralsOf` converts `LispReadException` likewise); WASM `unreachable`,
  or in EH mode a catchable `$lisp-cond` throw whose message is STATIC, no name interpolation
  (`WasmReadRuntimeBuilder.emitErr`).
- `#S`: JVM bakes an `Object[][]` `_rdStructs` directory in `<clinit>` via
  `JvmReadRuntimeBuilder.structTableClinit`, gated on `usesRead && mayUseInstances`; WASM appends a
  directory blob after the `WasmInstanceLayouts` records (`buildReadCtx`). An omitted slot takes a
  `nil` initform, re-reads a baked `EmittedReaderInitforms` constant text in place (cursor
  save/restore), or signals -- never a silently wrong value.
- The reader forces the JVM array machinery (`usesFloatArray |= usesRead`).
- WASM integers are `i31` (ratio/radix included); decimal floats -> `TYPE_FLOAT` via `emitTryFloat`:
  one `e`/`s`/`f`/`d`/`l` marker, optional sign, >=1 digit, scaled by one `*`/`/` against an
  iteratively built `10^n` -- exact under one rounding for |exp| <= 22, saturating past it.
- Dotted pairs: `LispReader.readList` consumes a `Token.Dot`; `JvmReadRuntimeBuilder.buildReadList`
  / `WasmReadRuntimeBuilder.buildReadListBody` treat `.` as a dot token only when the next byte is a
  delimiter (whitespace `( ) ' " ;`) or EOF.

Pinned by `Jvm/WasmLispCompilerTest#compileReadFromString{CharLiterals,RatiosAndRadix,VectorsAndArrays,StructLiterals,SymbolParityAndBlockComments,ReaderErrors*}`,
ci-spec `runtime-read-*`.

## `read` is PRELUDE RONTOLISP, not a primitive on any backend

One `LispPreludeLibrary` entry (`read` + the `%rd-*` family), so all four backends run one code
path. It consumes exactly ONE datum's characters and leaves the stream after them; a datum may span
lines and `read`/`read-line` may be mixed.

- **The scanner only DELIMITS**; `read-from-string` parses the text, so datum SYNTAX keeps one
  definition per backend. A syntax the emitted reader lacks (backquote, `|...|`) is a
  `read-from-string` gap, identical for `read`.
- Walk mirrors `LispLexer`'s raw `skipDatum`: balanced `(...)` honouring strings, `;`, nesting
  `#|...|#`, `#\(`; `"..."` with escapes; `|...|`; quote/backquote/comma prefixes; `#` dispatch
  where `#+`/`#-` take feature expression AND guarded form as one unit, and
  `#S(`/`#P"`/`#2A(`/`#b1010`/`#5=` take the symbol-shaped prefix plus a DIRECTLY following list or
  string.
- The character a terminator gives back rides the `unread-char` cell (`unread-char.lisp` on compile
  paths, the `Environment` cell interpreted) -- what makes `read` + `read-line` on one stream work.
- **After the object, ONE whitespace character is consumed** (CLHS 23.2); a terminating macro
  character is unread instead.
- EOF: `(read s)` -> nil, `(read s nil v)` -> `v`, non-nil `eof-error-p` signals `end-of-file`; a
  nil DATUM and EOF are no longer confused. An INCOMPLETE datum signals everywhere (`Unexpected end
  of input, expected ')'`, `Unterminated string literal`).
- **Trap**: `read` needs BOTH compile-path passes -- `LispPreludeLibrary.process` (splice) and
  `UnreadCharLibrary.process` (cell) -- in `CompileFrontend`'s order; neither run = call-time "The
  function READ is undefined". The `Jvm`/`Wasm` stdin test helpers run both.
- Not in `ShadowedBuiltins.LOWERED_WITHOUT_WRAPPER`; `#'read` compiles; `read` works on a socket or
  served request body interpreted. Reading is character-at-a-time; for bulk interpreter reads the
  fix is a faster interpreter `read-char`, not a per-backend `read`.
- Dead: `JvmReadRuntimeBuilder`'s `_read`/`_readStream`. WASM's `FUNC_READ` keeps its slot with an
  unused stub -- removing a function shifts every later index and changes the component blobs.

Pinned by `LispEvaluatorTest#read*`, `JvmLispCompilerTest#compileAndRunRead*`,
`WasmLispCompilerIntegrationTest#read*`, ci-spec `read-stream-datum-by-datum`.

## `read-line` CRLF parity

Strips one trailing CR everywhere (interpreter/JVM via `BufferedReader.readLine`; WASM `_read_line`
does an explicit `pos--` when the byte before the newline is `0x0D`). A lone `\r\n` line reads `""`,
not `"\r"`; a blank CRLF line must be `string=` to `""` (`.kb/tcp-sockets.md`).

## `open` / `with-open-file`

- `with-open-file` is a plain macro (`LispMacroExpander.expandWithOpenFile`) over `open`/`close`.
- `:direction` must be a literal `:input`/`:output` so both compilers resolve the mode at compile
  time (`OpenModes.staticMode` in `am.ik.rontolisp.compiler`, used by `Jvm/WasmOpenCompiler`);
  consequently `open` has no `BuiltinFunctionWrappers` entry.
- **A failed `open` SIGNALS on every backend.** WASM `_open` answers `ref.null eq` on a non-zero
  errno; null check and signal live at the call site (`WasmOpenCompiler`), where `%ERROR` is a
  catchable `$lisp-cond` throw in EH mode, `unreachable` outside it. It used to emit `unreachable`,
  uncatchable in any mode.
- `--component`: `adapter.wat`'s `$ensure_preopen` read the first `get-directories` element
  unconditionally, handing `open-at` handle 0 with no `--dir` (`unknown handle index 0` trap); it
  caches `-1` for "no preopen" and `$path_open` turns that into an errno. Hit `probe-file` too.

## `%probe-file` stays a string-in/string-out PRIMITIVE

Not `open` in a `handler-case`: nil is cheaper than catching a condition, works outside EH mode, and
`--no-gc` rejects catching. Public `probe-file` is prelude Lisp over it (coerces a pathname
argument, wraps the answer in the pathname VALUE -- `.kb/pathnames.md`; `uiop:file-exists-p` lowers
onto it, `.kb/asdf.md`); `#'probe-file` resolves to that defun (no `BuiltinFunctionWrappers` entry).
Contract: the namestring when the file exists (nothing resolves symlinks or absolutizes -- the
"truename" carries the argument namestring), nil otherwise; a directory counts as existing; the path
is interpreted exactly as `open` interprets it.

- Interpreter: a global function in `LispEvaluator` through the installed **`SourceLoader.exists`**,
  never `Files` directly, so the browser playground's in-memory loader answers. `SourceLoader` has
  it as a `default` deriving the answer from `load`; `fileSystem()` OVERRIDES with `Files.exists` (a
  read is wrong for a file that exists but is not decodable text).
- JVM: `_probeFile` in `JvmIoRuntimeBuilder` (`new java.io.File(p).exists()`, quotes stripped like
  `_open`, returns the original quoted path) + `JvmProbeFileCompiler`.
- WASM: `_probe_file` (`WasmIoRuntimeBuilder.buildProbeFileBody`, `FUNC_PROBE_FILE` appended after
  `FUNC_T_SYM` as the new `FX_FUNC_LAST` -- mod/rem pattern, no import index shifts, component blobs
  unaffected): `buildOpenBody`'s staging + read-mode `path_open`, errno != 0 -> `ref.null eq`,
  success closes the fd via `fd_close` (pinned by `probeFileLeaksNoDescriptor`, 300 probes then an
  `open`).

Pinned by `LispEvaluatorTest#probeFile*`, `JvmLispCompilerTest#probeFile*`,
`WasmLispCompilerIntegrationTest#probeFile*`/`componentProbeFile`, ci-spec
`probe-file-existing-and-missing`.

## WASM: a path resolves against the PREOPEN TABLE, not fd 3

`_path_dirfd` (`WasmIoRuntimeBuilder.buildPathDirFdBody`, via `emitDirFdAndPath`) answers the
descriptor a staged path opens relative to and leaves the bytes it accounts for in the
`PATH_SKIP_ADDR` (248) cell. Every `path_open` goes through it -- `_open`, `_probe_file`,
`_list_directory`, `_load`.

- Relative -> fd 3, skip 0 (first preopen). No preopen covering an absolute path -> fd 3, skip 0
  too, so the failure is the ordinary "cannot open" ERRNO each caller turns into nil. An errno,
  never a trap (`.kb/wasi-component.md`).
- Absolute is matched against preopen NAMES via preview1 imports `fd_prestat_get` (name length; its
  EBADF at the first non-preopened fd ENDS the walk) and `fd_prestat_dir_name`. Match is a
  path-COMPONENT prefix, LONGEST wins: with `--dir /` and `--dir /tmp`, `/tmp/x` -> `/tmp`;
  `/tmpfoo` matches neither. A trailing slash on a preopen name is stripped first; a relative
  preopen name (`--dir .`) covers no absolute path. A path naming the preopen ITSELF leaves an EMPTY
  remainder, so it becomes `"."` over the staged path's last byte.
- **Trap: a LITERAL absolute path tests none of this.** `(with-open-file (s "/tmp/x.txt") ...)`
  appears to work with no `--dir` because `CompileTimePathnameFolder` bundles the file's
  COMPILE-TIME contents into the artifact as a `with-input-from-string` (`.kb/asdf.md`). Every pin
  BUILDS the path at run time (a special variable plus `concatenate`).
- Cost: `IMPORT_FUNC_COUNT` 9 -> 11 and `FUNC_START` with it, so every emitted function index
  shifted (`fd_readdir` precedent, `.kb/directory-listing.md`). No new type entries --
  `fd_prestat_get` reuses `TYPE_INTERN` `(i32,i32)->i32`, `fd_prestat_dir_name` `TYPE_RD_MEMEQ`
  `(i32,i32,i32)->i32`. In step: `--no-wasi` gets two more EBADF trap stubs; `adapter.wat` exports
  both over `wasi:filesystem@0.3.0`; `adapter-http-server-p1.wat` exports them as EBADF.
- **`--component` adapter**: `$ensure_preopens` caches the whole table -- 16 slots of
  `{descriptor, name-len, name}` at `0x50500` -- and `dirfd` MEANS `3 + preopen index`. The table is
  a COPY on purpose: `get-directories` and its name strings lift through `cabi_realloc` at the
  CORE's `HEAP_PTR`, which the core pops back after every resolution. A preopen name over 256 bytes
  is recorded with length 0, not truncated.
- **Not reached**: `file-write-date` still nil on both WASM backends; `%delete-file` /
  `%rename-file` / `%make-directories` still signal -- `path_unlink_file`, `path_rename`,
  `path_create_directory` do not exist here.

Pinned by
`WasmLispCompilerIntegrationTest#absoluteRuntimePathResolvesAgainstThePreopenThatCoversIt` and its
`component` twin (both stage the file one level BELOW the preopen and keep a `-sibling` tree a match
without the component-boundary rule would find), ci-spec
`runtime-absolute-path-open-probe-and-load`.

## Load-context variables

`*load-pathname*` / `*load-truename*` hold the file being loaded on EVERY backend: the interpreter's
`loadFile` binds them dynamically per file (a COMPONENT by its resolved path, a plain `load` by the
spelling it was called with; nothing absolutizes); the compile paths ASSIGN them per SPLICED file
from `LoadInliner`'s `%begin-file` brackets, so the value agrees byte for byte and equals
`asdf:component-pathname` for a component. **Established at READ time too**: `loadFile` binds the
pair BEFORE its marker read and `UserMacroExpander` pushes the same two strings around the spliced
file's forms -- otherwise a `#.` datum reading `*load-truename*` answers nil, since the bracket
lowers to `setq` running long after the datum resolves. Mechanics/gate/tests: `.kb/load-inliner.md`;
the variable family (incl. the permanently-nil compile-file pair -- nil at read time too,
deliberately -- and `*readtable*`) is `.kb/asdf.md`.

## WASM intern table and heap base are COMPUTED, not fixed

`_intern` appends 8-byte `(offset,len)` records for runtime-first-seen symbols to a table whose base
it loads from `RT_INTERN_BASE_ADDR` (152); the heap bump pointer is at `HEAP_PTR_ADDR` (84). Both
cells are seeded by active data segments at instantiation (never in `_start` -- hosts can call
exports without running it) from the final static-data size in `WasmLispCompiler.compile`:
`rtInternBase = max(RT_INTERN_MIN_BASE=8192, 16-aligned end of the string segment)`,
`heapBase = rtInternBase + RT_INTERN_REGION_SIZE (8192)`; the Preview 1 page count grows with
`heapBase` (minimum 4 pages). With the old fixed 8192/16384, a large program's interned-string
segment (which also holds the eval function registry, appended last) overflowed and runtime
interning silently overwrote static strings and registry records (`eval` -> nil, garbled prints).
Pinned by `WasmLispCompilerIntegrationTest#runtimeInternTableSurvivesLargeStaticData`.

## String streams

`with-output-to-string` / `with-input-from-string` are `LispMacroExpander` expansions over
`%make-string-output-stream`, `%make-string-input-stream`, `%string-stream-contents`
(`PackageRegistry.CL_INTERNALS`), in the with-open-file let/close shape
(`expandWithOutputToString` fetches contents then closes; `expandWithInputFromString` mirrors
`__wof_result` with `__wifs_result`). Same handle space as file streams:

- Interpreter: `StringWriter` / `BufferedReader(StringReader)` in the `streams` table;
  `write-line`'s writer dispatch was widened from `BufferedWriter` to `Writer`. JVM: the same in
  `_streams` (`_makeStringOutputStream` / `_makeStringInputStream` / `_stringStreamContents` in
  `JvmIoRuntimeBuilder`).
- WASM: a **negative i31 handle** whose absolute value is a 12-byte linear-memory record (a WASI fd
  is never negative). Output `[kind=1][slot][len]` over a per-stream `$str_bytes` GC byte buffer
  reached through a module-global table; input records hold `[cursor][end]` over a persistent linear
  copy of the source, consumed by a branch at the top of `_read_line` (making `_read` work for
  free). `_write_line` grew an append branch; `_close` skips `fd_close` for negative handles and
  returns the slot (`WasmStringStreamRuntimeBuilder` + branches in `WasmIoRuntimeBuilder` /
  `WasmRuntimeBuilder.buildReadLineBody`).

`print`/`prin1`/`princ`/`terpri` take an optional stream on all three backends (interpreter via a
shared `emitTo`; JVM renders then `_writeStr(String, Object)` -- non-`Long` handles, i.e. nil/t, go
to `System.out` and update `_col`; WASM renders via `FUNC_PRINC_TO_STR`/`FUNC_PRIN1_TO_STR` then
`_write_stream_str`, whose stdout path delegates to `_write_str` keeping `LINE_START_ADDR`).
`write-string` and `write-to-string` (a prin1-to-string alias) are `BuiltinFunctionWrappers`
entries. `expandFormat` accepts a non-literal destination by building the string like `format nil`
and emitting one `(write-string <string> __format_stream)` (destination bound first). Compiled
print-family return values stay nil on the compile backends. Runtime `_eval` interpreters and
`--no-gc` do not know string streams.

**A WASM string OUTPUT stream costs linear memory NOTHING per write.** Every append
(`_write_stream_str`, `_write_line`, `fresh-line`'s newline) asks `_ostream_room(rec, n)`
(`FUNC_OSTREAM_ROOM`, reusing the `(i32,i32)->(ref null eq)` signature `TYPE_RAT_NEW`, appended
after the last fixed helper) for room and `array.copy`s in; the buffer DOUBLES, so k bytes one at a
time copies O(k) total, and `_str_stream_contents` is one `array.copy` into a fresh `TYPE_STRING`.
The buffer holds the frame quote `"` at index 0, content at `[1, 1+len)`. The old `[off][len][next]`
chunk list cost **15 bytes of linear memory per CHARACTER** on a `write-char` loop, reclaimed only
at the enclosing `__ronto_alloc_reset`. Three traps if you move the layout:

- **The table is the GC ROOT** a linear-memory record cannot be: a `TYPE_HASH_BUCKETS` in the
  module's LAST global (after the cached `t` and the raw-local sentinel), created by the first
  `_make_str_ostream` and doubled from there.
- **`_close` recycles the SLOT, not the record.** Freed slots form a free list threaded through the
  table's own entries (a free slot holds the next as an i31; head is `OSTREAM_FREE_ADDR`, a slot
  index + 1 so zero = empty), keeping the list on the GC heap where a host's arena reset cannot
  reach it. The 12 bytes are NOT recycled: an arena free list would hand out records the reset had
  already given back and the two allocators would alias.
- **A closed stream is closed**: `_close` sets kind 2, slot -1, so a double close is a no-op and a
  write after one traps at the table read rather than landing in a stream that inherited the slot.

Pinned by `WasmStringStreamArenaE2eTest` (node, a JS host reading `__ronto_alloc_mark` across calls:
65536 chars one `write-char` at a time vs 64 `write-string`s of 1 KiB cost the arena the same
nothing) plus `withOutputToString*` / `stringOutputStream*` / `freshLine*` in
`WasmLispCompilerIntegrationTest`.

**Public names, clear-on-read.** `make-string-output-stream` / `get-output-stream-string` are the CL
spellings of `%make-string-output-stream` / `%string-stream-contents`
(`LispMacroExpander.expandMakeStringOutputStream` / `expandGetOutputStreamString`, dispatched in
`Jvm/WasmExprCompiler`; the interpreter registers both as real `LispFunction`s so `#'` and
native-image mode work). **`%string-stream-contents` CLEARS the stream as it answers** (CL's
contract): `StringWriter.getBuffer().setLength(0)` interpreted and in `_stringStreamContents`;
`len = 0` on the output record (`WasmStringStreamRuntimeBuilder.buildContentsBody`; the BUFFER
stays). Pinned by `stringOutputStreamNamesClearOnRead` (JVM/WASM),
`evalStringOutputStreamNamesClearOnRead`, ci-spec `postmodern-language-incidentals`.

`make-string-input-stream` is the CL spelling of `%make-string-input-stream`
(`LispMacroExpander.expandMakeStringInputStream`, dispatched in `Jvm/WasmExprCompiler`; a real
`LispFunction` interpreted); `&optional start end` routes through `(subseq string start end)`, so
the bounds rule is subseq's everywhere. Needed once a library needed the stream to OUTLIVE the form
that made it: yason's `parse` makes one, so `lack/request` answered `400 Bad Request` to every JSON
body (every `ningle` application). Pinned by `stringInputStreamReadsWithoutWithInputFromString` +
`stringInputStreamHonoursStartAndEnd` (JVM/WASM), `evalStringInputStream*`,
`examples/cloudflare-workers/httpbin-ningle`. **Re-evaluation trigger**: if another CL stream
constructor is withheld for "the internal one covers every consumer", check whether a library now
needs it as a VALUE rather than in a scoped macro.

## `peek-char` (all four backends)

`(peek-char [peek-type [stream [eof-error-p [eof-value]]]])`. Only "the next character, left in
place" is a primitive (`%peek-char`, `PEEK_CHAR_INTERNAL`); the SKIPPING forms are one shared
`LispMacroExpander.expandPeekChar` lowering -- `t` skips whitespace, a character skips up to it,
both leaving what they stopped on -- so no backend has its own loop, and a non-literal peek-type
keeps the shape behind a runtime `(null ...)` test (what makes `#'peek-char` work). **The
`characterp` guard in the loop's end test is load-bearing**: with a nil `eof-error-p` it ends the
loop on the eof-value instead of skipping forever.

- Interpreter/JVM: `mark(2)` + `read` + `reset()` on the `BufferedReader` (2 covers a surrogate
  pair). WASM has no reset: a string input stream decodes at its record cursor WITHOUT advancing; a
  WASI fd goes through `_read_char` and parks the code point in a ONE-SLOT pushback keyed on the fd
  (`PEEK_FD_ADDR` = fd+1, 0 = drained; `PEEK_CP_ADDR` = the code point) that `_read_char` drains
  first. `FUNC_PEEK_CHAR` appended after `FUNC_FRESH_LINE_STREAM` as the new `FX_FUNC_LAST`.
- **WASM-only limit, documented not fixed**: only `read-char` drains that pushback, so mixing
  `peek-char` with `read-line`/`read` on the same FILE OR STDIN stream loses the peeked character
  (string input streams, the interpreter and the JVM are unaffected).

Pinned by `peekCharLeavesTheCharacterInTheStream` / `peekCharSkipsWhitespaceAndUpToACharacter` in
all three suites plus a ci-spec case.

## Synonym streams

**A synonym stream is a distinct VALUE forwarding EVERY operation to the current value of the symbol
it names -- for any symbol.** An instance of the fixed `LispLayout.SYNONYM_STREAM` layout (tag
`%SYNONYM-STREAM`, seeded into `ClosRegistry.layoutsByTag` as a LAYOUT ONLY, the pathname precedent
of `.kb/pathnames.md`): ONE declared slot holding the symbol, ONE RESERVED cell (`capacity` 2)
holding the per-operation READER -- a zero-argument closure over a read of that variable.
`(make-synonym-stream '*out*)` -> `(%obj-new '%SYNONYM-STREAM '*out* (lambda () *out*))`
(`LispMacroExpander.expandMakeSynonymStream`; a COMPUTED symbol falls back to `(lambda ()
(symbol-value sym))` over a let-bound temporary). Nothing needs the symbol's NAME at run time, which
is why `symbol-value` (global-only on the compile paths, and a force of the whole eval runtime,
`.kb/symbol-runtime-api.md`) is not in the lowering. The reader is deliberately OUTSIDE `slotNames`,
so it reaches neither the printers (`#<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>` on all four, while
a closure prints `#<lambda>` interpreted and `#<function>` elsewhere) nor `equal`.

**Resolution is one shared prelude defun, `%STREAM-TARGET`** (`LispPreludeLibrary`): a synonym
answers `(funcall (%obj-ref s 1))`, recursively; anything else answers itself. A cycle is the only
thing that cannot resolve. Five callers:

- **Both compile-path seams**: `StreamDesignators.throughSynonym` wraps the designator expression
  `JvmStringStreamCompiler.streamArg`/`inputStreamArg` and `WasmEmitHelper`'s twins produce, so
  every print/read/byte/sequence operator inherits it from ONE gate per backend. A literal that
  cannot BE a synonym (omitted argument, `t`, a handle) is untouched.
- **Interpreter**: `Environment.synonymTarget`, applied by `resolveOutputDest`/`resolveInputSrc` and
  -- BEFORE the `instanceof LispInstance` test -- by every Gray-dispatching built-in wrap in
  `LispEvaluator` (`resolveSynonymArg`).
- **`gray.lisp`'s `%gray-*-dispatch` helpers**, which resolve their stream FIRST: a synonym is an
  instance too, so otherwise they take the CLOS arm and die on "no applicable method". That is
  rove's composition (an indent stream wrapping `(make-synonym-stream '*standard-output*)`).
- **`streamp` / `input-stream-p` / `output-stream-p` / `close`**: the value answers `t` for the
  predicates; `close` closes the SYNONYM and answers `t`. The `close` guard exists TWICE on wasm --
  at the `close` case and at the `%CLOSE-RAW` alias the `--component` socket rewrite falls through
  to -- or a component hands the synonym to the handle-typed close and traps.
- **The `--component` spliced dispatchers** (`sockets.lisp` / `stdin-dispatch.lisp` /
  `stdin.lisp`): their `(or s *standard-input*)` binding is wrapped in the resolution
  (`%stream-target` for the two stdin files, sockets.lisp's own `%sock-handle` copy). That rewrite
  REPLACES the read built-ins, so the compiler's seam never sees those call sites and a synonym
  would read as "not a handle" and go to the host stdin cache. Same reason the explicit-nil
  designator is resolved there (`.kb/standard-output-redirect.md`).

**Gating**: the SYNONYM arm of a program-level operator is gated on `make-synonym-stream` appearing
in the source (`Ctx.usesSynonymStreams`, `constructsInstance` for the instance gate) -- it is the
only constructor and has no read syntax. The `%STREAM-TARGET` call itself is gated on
`usesSynonymStreams || usesStreamValues`, because an OPEN stream needs the same resolution. **Two
LIBRARY splices pay unconditionally**: gray.lisp's dispatch helpers and the `--component` I/O
dispatchers (sockets.lisp carries its own copy). Hence
`LispPreludeLibrary.referencedBySurfaceForm` splices `%STREAM-TARGET` for a program that merely uses
the GRAY protocol (the same predicate roots it in `LibraryDefunPruner`): both seams insert the call
inside the expression compilers, and `GrayStreamsLibrary.process` runs AFTER prelude selection.
**A pipeline that splices gray.lisp must run `LispPreludeLibrary.process` too** (CLI, playground,
E2E supports do; the backend test harnesses grew a `compileAndRunGray`). The component splices run
BEFORE prelude selection.

Pinned by `makeSynonymStreamResolvesTheNamedVariable` / `makeSynonymStreamIsAStreamValue` /
`synonymStreamOverStandardOutputFollowsALaterBinding` /
`makeSynonymStreamOverStandardInputFollowsALaterBinding` /
`synonymStreamOverAUserSpecialFollowsALaterBinding` (JVM + WASM), their `eval*` twins in
`LispEvaluatorTest`, ci-spec `synonym-stream-value`.

## Binary stdin/stdout is the standard-stream DESIGNATOR

`read-byte`/`write-byte` take the same designators as every other stream operation: `t` is the
process standard stream, an explicit `nil` resolves through `*standard-input*` /
`*standard-output*` (which hold `t` unless bound) via the shared `compiler.StreamDesignators`
rewrite at the call site, handle 2 is stderr. `(read-byte *standard-input*)` therefore works with NO
handle value in those variables. `read-sequence`/`write-sequence` inherit it (they lower onto the
byte ops) -- `size-report/programs/zlib/zlib.lisp` is such a filter.

- **Runtime dispatch is "is it a handle", never "is it nil"**, because `t` is a value: WASM tests
  `ref.test (ref i31)` (a `ref.cast` on the `t` struct would trap) and falls back to fd 0 / fd 1;
  the JVM tests `instanceof Long` and falls back to `System.in`/`System.out`, plus the reserved
  handles (0 stdin, 1 stdout, 2 stderr through the existing `emitStderrBranch` gate); the
  interpreter routes through `resolveInputSrc` / `resolveOutputDest`.
- **The JVM reads `System.in` directly, not the `_stdinReader` the character reads share**
  (interpreter likewise reads its `in`): a shared `BufferedReader` would swallow bytes the next
  `read-byte` owes the caller.
- **The JVM needs an explicit flush; the other three do not.** A single-byte `write(int)` only
  flushes on `'\n'`. `JvmLispCompiler` emits `System.out.flush()` before `main`'s `RETURN`, gated on
  the source naming `write-byte` or `write-sequence`; **any new path to `_writeByte`'s
  standard-output branch must join that gate** or its output truncates silently. The interpreter's
  twin is `out.flush()` at the end of `RontoLispCli.interpret`.
- **A raw octet moves the standard-output COLUMN like a character does** on all four backends:
  `_write_byte` sets `LINE_START_ADDR` when the descriptor is fd 1, `_writeByte` sets `_col` to
  `b ^ 10` in its stdout branch (only ever tested against zero), the interpreter sets `atLineStart`.
  Without it `(write-byte 10 t)` + `fresh-line` emitted a second newline on three backends and none
  on the interpreter. Pinned by ci-spec `binary-standard-output`.
- **`--component`**: a NON-async component reads fd 0 / writes fd 1 through the preview1 adapter
  (`componentBinaryStandardStreamsAreByteTransparent`). In an ASYNC one `stdin.lisp`'s
  `%stdin-read-byte-or-raw-f` is a raw passthrough, so octets come from the adapter's stdin, not
  stdin.lisp's chunk buffer; the `stream<u8>` chunk lifts as an octet vector and stdin.lisp's cursor
  walks BYTES (`%stdin-read-byte-f`, `%stdin-read-char-f` assembling a character from them as
  sockets.lisp does). Documented limit: an async program reading stdin BOTH as bytes and as
  lines/characters holds two host stdin streams with implementation-specific interleaving.
  **Re-evaluation trigger**: if a pending BYTE read must suspend the task, route
  `%stdin-read-byte-or-raw-f`'s nil designator to `%stdin-read-byte-f`.

Pinned by `LispEvaluatorTest#evalBinaryStandardStream{sAreByteTransparent,Designators}`, their
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` twins (the wasm ones render stdout through
`od` inside the runner, since `ExecResult` decodes it as text), and a ci-spec case.

## Binary streams (`:element-type '(unsigned-byte 8)`)

`open` takes an optional third literal argument -- `'character` (default) or `'(unsigned-byte 8)`;
`with-open-file` accepts a literal `:element-type` that `expandWithOpenFile` rewrites into that
positional form. Unparameterized `'unsigned-byte` (= `(unsigned-byte *)`) is the same binary type
(local-time's TZif reader spells it that way). Modes: `0` text-in, `1` text-out, `2` bin-in, `3`
bin-out (`OpenModes.OUTPUT_BIT`/`BINARY_BIT`). `read-byte`'s CL EOF semantics (`eof-error-p` default
t = trap/throw, nil = `eof-value`) are runtime arguments to the helpers.

- Interpreter: binary entries in the same `Map<Long, Closeable>` are
  `BufferedInputStream`/`BufferedOutputStream`; `read-byte`/`write-byte` are real `LispFunction`s
  (so `#'read-byte` works interpreted) with no `BuiltinFunctionWrappers` entries.
- JVM: `_open` grows a 4-way mode branch, `_closeStream` closes `InputStream`/`OutputStream` too,
  `_readByte(handle, eofErrorP, eofValue)` / `_writeByte(byte, handle)` in `JvmIoRuntimeBuilder`; a
  byte is a boxed `Long`.
- WASM: a WASI fd is element-type-agnostic, so `WasmOpenCompiler` masks the mode with
  `& OUTPUT_BIT` (raw 2/3 would mis-select write oflags/rights) and `_open`'s body is untouched;
  `_read_byte`/`_write_byte` (`WasmIoRuntimeBuilder.buildReadByteBody`/`buildWriteByteBody`) move
  one raw byte through the `BYTE_SCRATCH_ADDR` (148) cell via `fd_read`/`fd_write` -- no quote
  framing, no newline scan -- and a byte is an i31 fixnum. `FUNC_READ_BYTE`/`FUNC_WRITE_BYTE`
  appended between `FUNC_P1_FUTURE_AWAIT` and `FUNC_USER_BASE` (mod/rem/gensym pattern), so no
  import/`FUNC_START` shifts and the adapter blobs are unaffected.
- Untyped fds on WASM: `read-byte` on a text-opened stream "works" there while interpreter/JVM
  signal a type error -- documented as out of contract.

`read-sequence`/`write-sequence` are shared macro expansions
(`LispMacroExpander.expandReadSequence`/`expandWriteSequence`) into a `while` loop over
`aref`/`%aset`/`length` with fixed `__rseq_`/`__wseq_` temp names and literal-only `:start`/`:end`,
so no per-backend codegen exists for the loop; the sequence must be a rank-1 array EXCEPT a packed
buffer, which the expansion first offers to the per-backend
`%read-sequence-packed`/`%write-sequence-packed` primitives (raw little-endian elements in one bulk
transfer, any rank; `.kb/binary-sequence-io.md`) and only loops when they decline. **The BUFFER, not
the stream, picks the element**: both dispatch on `(stringp seq)`, so a character vector -- what
`(make-array n :element-type 'character)` and `make-string` build, the one rank-1 array answering
`stringp` on every backend (`.kb/adjustable-arrays.md`) -- moves CHARACTERS (`read-char` / one
`write-string` of the slice) and anything else moves bytes. The test is a RUNTIME one because the
buffer arrives in a variable (`alexandria:read-stream-content-into-string` allocates `(make-array
size :element-type (stream-element-type stream))`), which is also why `make-array`'s `:element-type`
accepts a computed designator (`LispMacroExpander.lowerRuntimeElementTypeMakeArray`, wired into
`Jvm/WasmArrayCompiler`). Unfinished: the character half is `.todo/219`.

None of this is known to the runtime `_eval` interpreters, and `--no-gc` has no stream support. The
`_eval` interpreters also do not know `require`/`provide` (compile-time directives consumed by
`LoadInliner`; a file read by the runtime `load` of compiled output must not contain them --
`.kb/load-inliner.md`). The `CiSpecE2eTest` driver passes `--dir . --dir /tmp` to both wasmtime
invocations so file-stream cases can open files in the shared work dir and the absolute-path case
has a preopen that can cover one.

## Component stdin (stdin.lisp over wit-imported `wasi:cli/stdin@0.3.0`)

On `--component`, an ASYNC program that reads stdin (`read-line`/`read-char`/`read-byte` referenced
+ an async form referenced + not serve mode) gets `stdin.lisp` + `stdin-dispatch.lisp` spliced by
`eval/StdinLibrary` (right after `SocketsLibrary` in the CLI; test helpers mirror it). The interface
is bound FROM the fixed import block (`WasmComponentBuilder.FIXED_BLOCK_IFACES`, whose instance
index the builder reads off the block -- the wait.lisp model; `validateFixedMembers` admits async
type-alias built-ins, whose component-level stream/future types alias nothing out of the block
instance, while drops/task-returns stay rejected), so the emitted WIT world is unchanged and no new
`-S` flag exists.

Mechanics = the preview1 adapter's stdin cache in Lisp: ONE `read-via-stream` stream cached in a
defvar (its result future dropped immediately; EOF is the stream status), a chunk buffer + cursor +
eof defvars, `%stdin-read-line-f`/`-read-char-f` async-defuns so the compiler rewrite's promotion
(`WasmSocketsRewrite`, gated on the spliced `%io-read-line`) makes a pending stdin read SUSPEND the
task -- a concurrent `wait-for` timer fires while the read waits
(`componentAsyncStdinReadDoesNotStallTheInstance`). The `%stdin-*-or-raw-f` helpers dispatch nil
designator -> stdin, else the `%...-raw` native built-ins. EOF parity: `read-line` -> nil; the
0/1-arg `read-char` signals `(error 'end-of-file)` -- the CLASS the native lowering signals
(`LispMacroExpander.endOfFileSignal`), not a look-alike message, so `(handler-case (read-char)
(end-of-file () ...))` catches it here as elsewhere
(`componentAsyncStdinBareReadCharSignalsTheEndOfFileClass`); `read-byte` on nil errors.

**A NON-async stdin program is deliberately NOT migrated**: it keeps the preview1 adapter's
`fd_read` stdin branch, so its component is byte-identical
(`componentNonAsyncStdinKeepsTheAdapterPathAndItsFlags`). When sockets.lisp is spliced,
`StdinLibrary` supplies only the or-raw helpers' backing (real `stdin.lisp`, or `stdin-stub.lisp`
raw passthroughs under serve -- the wasi:http service world has no stdin and the bridge's `fd_read`
is EOF by construction) and sockets.lisp's dispatchers keep the `%io-*` names. Known limits,
documented not fixed: reads buffer one host chunk (the sockets.lisp divergence from
byte-at-a-time); a migrated program that ALSO consumes stdin through forms the rewrite leaves native
(`read`, the 2/3-arg eof-parameter `read-char`/`read-byte`) holds TWO host stdin streams with
implementation-specific interleaving.

## `read-char`

`(read-char [stream [eof-error-p [eof-value]]])` over the same handle space as `read-line` (default
stream nil = standard input; file streams and string input streams). EOF semantics mirror
`read-byte`. 0-arg `#'read-char` wrapper like `#'read-line`. Needed by cl-utilities'
`read-delimited`.

- Interpreter: an `Environment` registration reading one UTF-16 code unit from `stdinReader` or a
  `BufferedReader` table entry. JVM: `_readChar(handle, eofErrorP, eofValue)` in
  `JvmIoRuntimeBuilder` (lazily initializes the shared `_stdinReader` field on a null handle;
  `BufferedReader.read()`, boxed `Character`), called by `JvmReadCharCompiler`.
- WASM: `_read_char` (`WasmIoRuntimeBuilder.buildReadCharBody`, `FUNC_READ_CHAR` appended after
  `FUNC_FBOUNDP` before `FUNC_USER_BASE` -- mod/rem, no import index shifts, component blobs
  unaffected) reads ONE BYTE (WASM strings are byte-indexed like `char`/`schar`) from fd 0 / a WASI
  fd via `BYTE_SCRATCH_ADDR` / a negative string-stream handle's `[cursor,end)` range (advancing the
  cursor), boxed as a `TYPE_CHAR` struct.
- **Trap**: the compilers evaluate multi-argument call forms right-to-left (`.todo/014`), so
  sequence consecutive `(read-char s)` calls through `let*`, not `(list ...)`.

## The stream table is CONCURRENT on the interpreter and the JVM

One allocator, and it is atomic. `http-handler`/`serve` put one virtual thread per request on both
backends (`.kb/mutexes.md`) and the table is process-wide. The old "reserve `count`, then store"
shape handed two concurrent requests the SAME handle: one stream leaked and both Lisp handles
denoted the survivor, so two conversations interleaved on one connection (against PostgreSQL this
read as random connection loss inside the trust-auth handshake).

- **Interpreter** (`Environment.createGlobal`): a `ConcurrentHashMap` plus an `AtomicLong`. Never
  put a `null` value in it -- the map forbids it; `close` REMOVES instead of nulling.
- **JVM**: every producer -- `_open`, `_makeStringOutputStream`, `_makeStringInputStream`, every
  socket constructor in `JvmSocketRuntimeBuilder` -- appends through the ONE allocator
  `_addStream(Object) -> Long` (`JvmIoRuntimeBuilder`, emitted `ACC_SYNCHRONIZED`), so **a new
  stream-producing built-in MUST call it** rather than grow its own reserve/store pair.
  `_closeStream` is synchronized with it (its null-out must not land in an array a concurrent growth
  is replacing) and `_streams` is `ACC_VOLATILE`: `_addStream` writes the field back on EVERY call,
  publishing the new element to reader threads. Producers build the stream BEFORE calling the
  allocator, so a connect or file open stays outside the lock.
- **WASM**: both backends are single-threaded by construction (the component's
  `rontolisp::*sock-table*` included), so nothing to do.

Wider rule: `.kb/concurrent-served-requests.md`. Pinned by
`HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` and
`HttpHandlerJvmTest#concurrentRequestsGetTheirOwnSocketHandle` (shared fixture
`StreamHandleConcurrencySupport`: 24 simultaneous requests x 3 rounds, each opening its own socket
to an echo server; asserts "no handle handed out twice" and "the echo that came back is mine").

## `file-length`, `file-write-date`, and the three write-side operators

**`file-length` is REAL on ALL FOUR backends, and `nil` only where a stream genuinely has no
length.** A `Reader`/`Writer` does not remember its path, so interpreter and JVM keep a side table:
interpreter `Map<Long, String> streamPaths` in `Environment`, filled by `open`, cleared by `close`;
JVM an `Object[] _streamPaths` field indexed by handle like `_streams`, written by `_setStreamPath`
(which `_open` wraps its `_addStream` call in) and nulled by `_closeStream`. `_fileLength` runs the
handle through `_forceOutput` first so an output stream's answer counts what was WRITTEN (the
interpreter flushes a `Flushable` entry likewise). Only `open` fills the table, so every other
stream kind -- string streams, sockets, standard streams, a closed handle -- is nil.

**The two WASM backends need no side table**: a stream value there IS its WASI descriptor, so
`_file_length` (`WasmIoRuntimeBuilder.buildFileLengthBody`, fixed index `FUNC_FILE_LENGTH` appended
after `FUNC_EQUALP_KEY`, called by `WasmFileLengthCompiler` through
`WasmEmitHelper.streamDesignator`) stats the fd through the TWELFTH preview1 import,
`fd_filestat_get`. Nil for exactly the set the other two give: a non-i31 designator (`t`, nil, an
unresolved synonym), a NEGATIVE handle (string stream), a handle below
`StreamDesignators.FIRST_USER_HANDLE` (standard streams), a non-zero errno (closed descriptor, a
socket handle past the adapter's fd table), and any `filetype` that is not `regular_file`. No flush
first, unlike the other two.

**Trap: the 64-byte `filestat` staged at `HEAP_PTR` must be rounded UP to 8 first.** preview1's
`filestat` has u64 fields, so wasmtime REFUSES an unaligned buffer (`Pointer not aligned to 8`)
instead of writing it, while `HEAP_PTR` is a bump pointer over values of every width. The pointer is
advanced over for the call and popped after -- the `_open` discipline, load-bearing under
`--component` because the adapter allocates through `cabi_realloc` at that cell. Under
`--component`, `adapter.wat`'s `$fd_filestat_get` re-encodes the preview1 `filestat` from a
SYNC-lowered `wasi:filesystem` `descriptor.stat` (`result<descriptor-stat, error-code>` at
`0x51600`: result disc @0, `type` @8, `link-count` @24, `size` @32); dev/ino and the three
timestamps are zero-filled. `adapter-http-server-p1.wat` answers EBADF; `--no-wasi` gets the EBADF
trap stub.

**`file-write-date` still answers nil on both WASM backends.** It names a PATH, not an open stream,
so it needs an open/stat/close of its own; and the component adapter zero-fills the timestamps.
**Re-evaluation trigger**: lifting `data-modification-timestamp` out of `descriptor-stat` in
`adapter.wat` plus a path-stat runtime beside `_probe_file` is the whole remaining change.

The JVM side is GATED per operator (`JvmIoRuntimeBuilder.FileMeta`); `#'file-length` /
`#'file-write-date` are in `REFERENCE_GATED_FUNCTIONS` because their wrapper bodies call those
helpers and the gate scans the SOURCE program, not the injected wrappers. The WASM side is not gated
-- one always-emitted body like `_list_directory`, and `--optimize` drops it (and the import) when
unused.

Pinned by `LispEvaluatorTest#evalFileWriteDateAndFileLength` / `#fileLengthOverEveryStreamKind`,
`JvmLispCompilerTest#compileAndRunFileWriteDateAndFileLength` /
`#compileAndRunFileLengthOverEveryStreamKind`,
`WasmLispCompilerIntegrationTest#fileMetadataAnswersNilAndDirectoryCreationSignals` /
`#fileLengthAnswersTheSizeOfARealFile` / `#componentFileLength`, ci-spec
`file-length-of-a-file-of-a-known-size`.

**Three write-side operators share one shape: prelude Lisp over one primitive.** CL's extra values
(`ensure-directories-exist`'s `created`, `rename-file`'s two truenames) are not returned -- a
prelude defun's secondary value does not cross the function boundary on the compile paths
(`.todo/212`).

- `ensure-directories-exist` over `%make-directories`: the "directory component is everything up to
  and including the last slash" rule has one definition in `LispPreludeLibrary`; the primitive is
  interpreter/JVM `Files.createDirectories` / `File.mkdirs`, both WASM backends a call-time
  `LispMacroExpander.makeDirectoriesStub()` error. It signals on WASM where `file-length` answers
  nil, because its contract has no "cannot be determined" answer.
- `delete-file` over `%delete-file`, which answers nil rather than signalling when the file is
  absent or the host refused, so "a missing file is a `file-error`" lives once in the Lisp above it.
  Interpreter `Files.deleteIfExists`; JVM `_deleteFile` (`JvmIoRuntimeBuilder`, gated through
  `FileMeta`); both WASM backends `LispMacroExpander.deleteFileStub()`. Unfinished: the preview1
  `path_unlink_file` import is `.todo/257`. mito's `generate-migrations` is the caller, so that
  branch is interpreter/JVM-only.
- `rename-file` over `%rename-file` (same nil-not-signal rule). Interpreter `Files.move`
  (`REPLACE_EXISTING`); JVM `_renameFile` (`File.renameTo`, same `FileMeta` record, a fifth flag);
  both WASM backends `LispMacroExpander.renameFileStub()` -- same `.todo/257`, one import wider
  (`path_rename`). The new name is MERGED with the old one, so a bare file name keeps the directory.
  Pinned by `LispEvaluatorTest#renameFileMovesTheFileAndSignalsWhenItIsNotThere`,
  `JvmLispCompilerTest#renameFileMovesTheFileOnDisk`, and the rename arm of
  `WasmLispCompilerIntegrationTest#pathnameAlgebraOverTheFlatNamestring`.

## `uiop:read-file-string` must NOT size its buffer from `file-length`

Prelude Lisp over `with-open-file` + a CHUNKED `read-sequence` loop; both properties load-bearing.
`file-length` answered nil on both WASM backends when this was written (real since 2026-08-31), so
`(make-string (file-length s))` trapped there. **The loop stops on the first SHORT read, so EOF is
read at most ONCE**: a SECOND read past EOF traps on the `--component` backend alone, because
`adapter.wat`'s `$fd_read` calls `stream.read` after the writable end dropped and wasmtime rejects
it (`cannot read after being notified that the writable end dropped`). Any slurp loop hits it.
**Re-evaluation trigger**: the adapter answering 0 bytes/EOF idempotently.

## Computed open options (the mode is still picked from a LITERAL)

`(with-open-file (s path :element-type et) ...)` -- options passed down as function arguments, as
uiop's `call-with-input-file` / `call-with-output-file` do -- lowers to
`LispMacroExpander.lowerRuntimeOpenOptions`: path and every computed option value bound left to
right in one `let*`, checked, then dispatched by nested `if` onto at most SIX literal `(open path
:direction ['(unsigned-byte 8)])` leaves (2 element types x input/output/append). No backend learned
a runtime mode -- the dispatch IS the mechanism.

- **A spec whose values are all literal never enters it.** `hasRuntimeOpenOption` decides per FORM;
  a literal `:element-type` is a `(quote ...)` form and a literal option value a keyword, while a
  variable, `(list 'unsigned-byte 8)` or `(stream-element-type s)` is computed. That keeps existing
  output byte-identical, pinned by
  `JvmLispCompilerTest#aLiteralWithOpenFileSpecCompilesToTheSameBytesAsBefore`.
- **The path is bound once.** Six leaves name it; a path expression with a side effect or a cost
  must not run six times.
- **The accepted value SET is unchanged -- only the time of the refusal moves.** `:direction`
  `:input`/`:output`; `:element-type` `character` / `(unsigned-byte 8)` (plus unsized
  `unsigned-byte`, `(unsigned-byte *)`); `:if-exists` `:supersede`/`:append`; `:if-does-not-exist`
  `:create`/`:error`; `:external-format` `:utf-8`/`:default`. A literal outside the set refuses at
  expansion time (or, for the three ignorable options, through the existing call-time stub); a
  computed one is checked by an `unless` emitted BEFORE the dispatch, whatever the direction.
- **Three entries, one lowering**: `LispMacroExpander.expandWithOpenFile` (all four backends),
  `OpenModes.lowerRuntimeOptions` in front of `Jvm/WasmOpenCompiler` (a direct `open` call), and
  `BuiltinFunctionWrappers.openWrapper`, which hands its `getf` plist to the same builder instead of
  its own two-way dispatch -- that is what gave `(apply #'open p '(:direction :output :if-exists
  :append))` the append it used to drop silently. The interpreter's own runtime `open`
  (`Environment`) is the fourth reading of the same table (widened to unsized `unsigned-byte` in the
  same pass); **the two must be kept in step**.

Pinned by `LispEvaluatorTest#withOpenFileComputedOptionsDispatchAtRunTime` /
`#withOpenFileComputedOptionValueOutsideTheSupportedSetSignals`,
`JvmLispCompilerTest#compileAndRunComputedOpenOptions`,
`WasmLispCompilerIntegrationTest#withOpenFileComputedOptionsDispatchAtRunTime`, ci-spec
`computed-stream-options-439`.

## A stream is a VALUE, not a handle

**Every OPEN stream is an instance of the fixed `LispLayout.STREAM` layout** -- tag `%STREAM`,
declared slots `HANDLE` and `KIND` -- so `streamp` answers off the value, and `file-stream` /
`string-stream` are exact everywhere. The `%PATHNAME` / `%SYNONYM-STREAM` precedent: a LAYOUT ONLY
in `ClosRegistry` (never a class, never a struct), so `%obj-new`/`%obj-is` resolve the tag on all
four backends while the type joins no `typep` tag table, no `structure-object`/`standard-object`
enumeration and no `%class-slot-defs` answer.

- **The HANDLE is a declared slot, not machinery.** `equal` on two instances is structural over
  DECLARED slots and CL's `equal` on streams is `eq`: with the kind alone declared, any two file
  streams would compare `equal`. The price is a printed form carrying a backend-local number --
  `#<STREAM :HANDLE 3 :KIND :FILE>` on interpreter/JVM, a WASI fd or a negative string-stream record
  on wasm.
- **The KIND is a keyword** (`LispLayout.Kinds`): `:FILE`, `:STRING-INPUT`, `:STRING-OUTPUT`,
  `:SOCKET`, `:SOCKET-SERVER`, `:BODY`, `:STANDARD`. Compared with `equal`, not `eq`.
- **`*error-output*` holds one** (`:STANDARD` over reserved handle 2). `*standard-output*` /
  `*standard-input*` keep the `t` DESIGNATOR: it is not a value and does not become one.

**One gate, both halves.** `LispMacroExpander.mayCreateStreamValues(program)` scans for the
constructor names (plus `*error-output*`, whose seeded default IS a stream) and answers
`Ctx.usesStreamValues` on both compile backends. That single boolean gates the WRAP a producer emits
AND the UNWRAP a consumer emits, so the two can never disagree: a program the scan says no about
keeps raw handles end to end and compiles byte-identically. It also forces
`LispMacroExpander.mayCreateInstances` on.

**Producers wrap in the BACKEND, not in an expansion.** `JvmObjCompiler.emitWrapStream` and
`WasmInstanceCompiler.emitWrapStream` take the raw handle off the stack and build the instance, so
the I/O runtime helpers (`_open`, `_makeStringInputStream`, `_tcpConnect`, `FUNC_OPEN`,
`FUNC_MAKE_STR_ISTREAM`, ...) keep their exact bodies -- which matters on wasm, where their `FUNC_*`
indices are fixed and the `--component` adapter blobs depend on them. The interpreter's producers
answer `StreamDesignators.streamValue` directly.

**Consumers unwrap at ONE seam per backend, plus stragglers.** `StreamDesignators.throughStream`
wraps a designator in `(%stream-target D)`, and `JvmStringStreamCompiler.streamArg`/`inputStreamArg`
+ `WasmEmitHelper`'s twins apply it to every print/read/byte/sequence operator.
`streamDesignator(ctx, expr)` is the same resolution WITHOUT the `*standard-output*` designator
rule, for consumers taking their stream argument as written: `close`, `open-stream-p`,
`force-output`, `%string-stream-contents`, `file-length`, `warn`'s `*error-output*` read, the whole
`tcp-*` family. The interpreter's twin is `Environment.streamTarget`, reached from
`resolveOutputDest`/`resolveInputSrc` and applied by hand at the raw sites.

**`%STREAM-TARGET` resolves BOTH kinds** (the old `%SYNONYM-TARGET`, renamed): a synonym forwards to
what its variable holds now, recursively; an open stream answers its handle slot. **Two exceptions
in gray.lisp**: `%gray-close-dispatch` does not resolve a synonym and so tests `%STREAM` by tag
ahead of `%obj-p`; and the three PREDICATE dispatchers (`open-stream-p` / `input-stream-p` /
`output-stream-p`) hand the ORIGINAL designator to the built-in rather than the resolved handle -- a
bare handle is not a stream to those predicates any more, the value is.

**The prelude splice of `%STREAM-TARGET` is best effort, so the seams have a fallback.**
`LispPreludeLibrary.referencedBySurfaceForm` selects it from surface facts, but a stream value can
arrive from a form injected AFTER selection (the generated condition renderer and the print-object
seam each open a string output stream). When the defun is absent both seams emit
`StreamDesignators.throughStreamInline` -- the `%obj-is`/`%obj-ref` unwrap written out of the
primitives, needing no splice. The synonym half never needs it. **`sockets.lisp` carries its own
`%sock-handle`** and the WHOLE resolution, because the component socket splice is used by pipelines
that do not run the prelude selection; `%sock-register` answers the wrapped value (`:SOCKET` /
`:SOCKET-SERVER` by its kind field) while the TABLE stays keyed by the raw fd.

**Stream TYPE names** are all EXACT. `synonym-stream` is `(%obj-is x '%SYNONYM-STREAM)`;
`file-stream` and `string-stream` are `LispMacroExpander.makeStreamKindTest` -- `(%obj-is x
'%STREAM)` then an `equal` against the `KIND` slot, `:FILE` for the first,
`:STRING-INPUT`/`:STRING-OUTPUT` for the second. **The test must be a `let` plus a nested `if` in
that order**: `%obj-ref` on a non-instance is undefined on the compile paths, so the tag test comes
first (the shape `LispMacroExpander.coercePathArg` uses for a pathname). `readtable` lowers to
`null`, because the nil token `*readtable*` holds is the only value that can be one
(`.kb/reader-features.md`). All four are in `PackageRegistry.CL_TYPES` and
`LispMacroExpander.makeTypeTest`; **a name in the first without a case in the second is a hard
expansion error in `typecase`, not a silent nil.**

Pinned by `LispEvaluatorTest#evalStreamp` / `#theStreamAndReadtableTypeNamesResolve`,
`JvmLispCompilerTest#compileAndRunTheMissingStandardNames`,
`WasmLispCompilerIntegrationTest#theMissingStandardNames`, ci-spec
`a-stream-is-a-self-describing-value`.

## `load` options, `*load-verbose*` / `*load-print*`

`*load-verbose*` / `*load-print*` are bound and nil on every backend -- `load` prints no banner and
echoes no form value. Proclaimed special (`LispEvaluator`'s `specialVars` seed interpreted, an
injected `defvar` on the compile paths) so `(let ((*load-verbose* nil)) (load f))` binds dynamically.

`load` takes CL's keyword options: `:if-does-not-exist` is REAL -- a false value answers nil instead
of signalling -- while `:verbose` / `:print` / `:external-format` are accepted and dropped. Every
option value is still BOUND, in written order. The compile paths lower the form in
`LispMacroExpander.lowerLoadOptions` (a `let*` plus, for `:if-does-not-exist`, an `(or <value>
(probe-file path))` guard around the one-argument `load`); the interpreter reads the same four in
its `load` builtin against a `sourceLoader` probe -- an UNREADABLE file, not merely a missing one,
answers nil on both. Because the guard is built inside the expression compilers long after prelude
selection, `LispPreludeLibrary.referencedBySurfaceForm` splices `probe-file` on the SURFACE fact
instead: the program writing `:if-does-not-exist` on a `load` at all
(`LispMacroExpander.callsLoadWithIfDoesNotExist`). What a top-level literal `load` with options does
is `.kb/load-inliner.md`. Pinned by `LispEvaluatorTest#loadAcceptsTheKeywordOptions`, ci-spec
`computed-stream-options`.

The SUCCESS value is `t` on every backend: JVM `_load` returns the symbol String `"T"`, the WASM
`_load` calls `_t_sym`. Both runtime helpers once answered the integer 1 -- truthy, so only reading
the value showed it; the ci-spec case prints a successful runtime load (a file the program wrote
itself) alongside the `:if-does-not-exist` nil for that reason.

## `:if-exists :append`

The ONE non-default value of the three otherwise-ignorable `open`/`with-open-file` options that is
implemented rather than rejected. `:direction :output :if-exists :append` normalizes into the
`:append` PSEUDO-DIRECTION -- not a CL direction, produced only by
`compiler.OpenModes.normalizeKeywordForm` and `LispMacroExpander.expandWithOpenFile`
(`LispMacroExpander.isAppendIfExists` is the shared predicate) -- so every backend reads ONE literal
token where the source wrote an option pair. `OpenModes.APPEND_BIT` (4) extends the mode encoding to
`5` = text output appending, `7` = binary output appending. Interpreter
`Files.newBufferedWriter`/`newOutputStream` with `CREATE + WRITE + APPEND`; JVM two extra `_open`
arms over `FileWriter(String,boolean)` / `FileOutputStream(String,boolean)` (the mode constant no
longer fits `ICONST_0..3`, hence `emitIntConst`); WASM `WasmOpenCompiler.wasmMode` collapses the
mode to 0 read / 1 write / 2 append and `_open` answers `oflags = O_CREAT` alone (O_TRUNC would
discard exactly what the append keeps) plus `fdflags = FDFLAGS_APPEND` -- the `i32.eq` result IS the
flag value. An `:if-exists :append` alongside `:direction :input` is ignored, as in CL. Pinned by
`LispEvaluatorTest#evalOpenAppendKeepsTheExistingContent`,
`JvmLispCompilerTest#compileAndRunOpenAppend`, ci-spec
`open-if-exists-append-keeps-the-existing-content`.
