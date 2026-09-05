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
- PERMANENT limits: `#.`, `#+`/`#-`, `#n=`/`#n#` signal a catchable error. The interpreter's runtime
  read still resolves the first two and reads labels — the ONE documented interpreter/compiled
  divergence; `#.` EVALUATES via `Environment.setReadTimeEvalResolver`, gated on `*read-eval*`
  (`.kb/reader-features.md`).
- Errors: JVM `RuntimeException` caught as `simple-error` with the frontend's EXACT messages
  (`LispEvaluator.foldStructLiteralsOf` converts `LispReadException` likewise); WASM `unreachable`,
  or in EH mode a catchable `$lisp-cond` throw whose message is STATIC, no name interpolation
  (`WasmReadRuntimeBuilder.emitErr`).
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
- EOF: `(read s)` -> nil, `(read s nil v)` -> `v`, non-nil `eof-error-p` signals `end-of-file`; an
  INCOMPLETE datum signals everywhere.
- **Trap**: `read` needs BOTH compile-path passes — `LispPreludeLibrary.process` and
  `UnreadCharLibrary.process` — in `CompileFrontend`'s order; neither run = call-time "The function
  READ is undefined".
- Dead: `JvmReadRuntimeBuilder`'s `_read`/`_readStream`. WASM's `FUNC_READ` keeps its slot with an
  unused stub — removing a function shifts every later index and changes the component blobs.

Pinned by `LispEvaluatorTest#read*`, `JvmLispCompilerTest#compileAndRunRead*`,
`WasmLispCompilerIntegrationTest#read*`, ci-spec `read-stream-datum-by-datum`.

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
- `:direction` must be a literal `:input`/`:output` so both compilers resolve the mode at compile time
  (`compiler.OpenModes.staticMode`, used by `Jvm/WasmOpenCompiler`); `open` therefore has no
  `BuiltinFunctionWrappers` entry. Modes 0 text-in / 1 text-out / 2 bin-in / 3 bin-out
  (`OUTPUT_BIT`/`BINARY_BIT`), plus `APPEND_BIT` (4) -> 5 / 7.
- **A failed `open` SIGNALS on every backend.** WASM `_open` answers `ref.null eq` on a non-zero
  errno; the null check and signal live at the call site (`WasmOpenCompiler`), catchable in EH mode.
  It used to emit `unreachable`, uncatchable in any mode.
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
- **The accepted value SET is unchanged; only the time of the refusal moves.** `:direction`
  `:input`/`:output`; `:element-type` `character` / `(unsigned-byte 8)` (plus unsized
  `unsigned-byte`, `(unsigned-byte *)`); `:if-exists` `:supersede`/`:append`; `:if-does-not-exist`
  `:create`/`:error`; `:external-format` `:utf-8`/`:default`.
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
- **Not reached**: `file-write-date` nil on both WASM backends; `%delete-file`/`%rename-file`/
  `%make-directories` signal.

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
`open` takes an optional third literal argument — `'character` (default) or `'(unsigned-byte 8)`;
unparameterized `'unsigned-byte` is the same binary type. Interpreter: `BufferedInputStream`/
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
  `.kb/binary-sequence-io.md`). **The BUFFER, not the stream, picks the element**: both dispatch on
  `(stringp seq)`, so a character vector moves CHARACTERS and anything else moves bytes — a RUNTIME
  test because the buffer arrives in a variable, which is also why `make-array`'s `:element-type`
  accepts a computed designator (`lowerRuntimeElementTypeMakeArray`). Unfinished: the character half
  is `.todo/219`.
- The `_eval` interpreters know none of this, nor `require`/`provide` (a file read by the runtime
  `load` of compiled output must not contain them — `.kb/load-inliner.md`). The `CiSpecE2eTest`
  driver passes `--dir . --dir /tmp` to both wasmtime invocations.

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
  `Files.createDirectories`/`File.mkdirs`, both WASM backends a call-time error. It SIGNALS on WASM
  where `file-length` answers nil, because its contract has no "cannot be determined" answer.
- `delete-file` over `%delete-file`, which answers nil rather than signalling when the file is absent
  or the host refused, so "a missing file is a `file-error`" lives once in the Lisp above it. Both
  WASM backends stub; the preview1 `path_unlink_file` import is `.todo/257`. mito's
  `generate-migrations` is the caller, so that branch is interpreter/JVM-only.
- `rename-file` over `%rename-file` (same nil-not-signal rule); the new name is MERGED with the old
  one, so a bare file name keeps the directory. Same `.todo/257`, one import wider (`path_rename`).

**`uiop:read-file-string` must NOT size its buffer from `file-length`**: prelude Lisp over
`with-open-file` + a CHUNKED `read-sequence` loop, both properties load-bearing. **The loop stops on
the first SHORT read, so EOF is read at most ONCE** — a SECOND read past EOF traps on the
`--component` backend alone, because `adapter.wat`'s `$fd_read` calls `stream.read` after the
writable end dropped (`cannot read after being notified that the writable end dropped`). Any slurp
loop hits it. **Trigger**: the adapter answering 0 bytes/EOF idempotently.

Pinned by `LispEvaluatorTest#evalFileWriteDateAndFileLength`/`#fileLengthOverEveryStreamKind`,
`#renameFileMovesTheFileAndSignalsWhenItIsNotThere`, their JVM twins,
`WasmLispCompilerIntegrationTest#fileMetadataAnswersNilAndDirectoryCreationSignals`/
`#fileLengthAnswersTheSizeOfARealFile`/`#componentFileLength`, ci-spec
`file-length-of-a-file-of-a-known-size`.

## A stream is a VALUE, not a handle
**Every OPEN stream is an instance of the fixed `LispLayout.STREAM` layout** — tag `%STREAM`,
declared slots `HANDLE` and `KIND` — so `streamp` answers off the value, and `file-stream` /
`string-stream` are exact everywhere. The `%PATHNAME`/`%SYNONYM-STREAM` precedent: a LAYOUT ONLY in
`ClosRegistry`, so `%obj-new`/`%obj-is` resolve the tag on all four backends while the type joins no
`typep` tag table, no `structure-object`/`standard-object` enumeration and no `%class-slot-defs`
answer.

- **The HANDLE is a declared slot, not machinery**: `equal` on two instances is structural over
  DECLARED slots and CL's `equal` on streams is `eq`, so with the kind alone declared any two file
  streams would compare `equal`. The price is a printed form carrying a backend-local number.
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
