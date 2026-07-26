# Make cl-postgres (Postmodern's PostgreSQL driver) actually run

Goal: `(ql:quickload "cl-postgres")` loads, and a real query round-trip works
against a live PostgreSQL:

```lisp
(ql:quickload "cl-postgres")
(let ((conn (cl-postgres:open-database "mydb" "myuser" "mypass" "127.0.0.1" 5432)))
  (print (cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
```

Iterate against the cached copy in
`~/.rontolisp/quicklisp/software/postmodern-*/cl-postgres/`. Run the verbatim
upstream sources -- vendoring a patched subset (dropping scram/saslprep behind a
rontolisp feature) is acceptable only as a temporary stepping stone.

## Where this stands (2026-07-25)

Everything below the driver itself is done; what remains is the driver.

- **The `.asd` parses** (defparameter env, `#.*string-file*`, the `:feature`
  clauses in `:depends-on`), pinned by
  `AsdfSystemsTest.parsesTheClPostgresAsdHeaderShape`.
- **Every dependency runs REAL on all four backends** -- `md5`,
  `split-sequence`, `cl-base64`, `cl-ppcre`, `uax-15`, `ironclad`. No shims.
  The `ironclad` slice covers all nine `ironclad:` names `scram.lisp` calls,
  with RFC 7677 section 3 pinned end to end (`.kb/asdf.md`).
- **The condition system carries `errors.lisp`**: `define-condition` with
  slots/`:reader`s, `handler-case`, `unwind-protect` and a lite `cerror` all
  work on the interpreter, the JVM and wasm-GC (only `--no-gc` rejects the
  catching forms). `.kb/error-handling.md`.
- **The socket layer is covered** by the usocket shim (`.kb/tcp-sockets.md`):
  `socket-connect` + `:element-type '(unsigned-byte 8)` + `socket-stream`, and
  the `#-(or allegro sbcl ccl)` reader conditionals in `public.lisp` select
  that path under rontolisp's feature set.
- **The `cl-postgres-wip` branch is dead.** Its content was the JDK-backed
  crypto shims (obsolete: the real libraries load) plus pre-work that has since
  landed on develop independently. Start from develop; do not revive it.

Two conclusions worth not re-deriving:

- **`restart-case` needs nothing.** All 4 sites are `(restart-case (error X)
  (clauses...))` and cl-postgres never invokes a restart itself (zero
  library-side `invoke-restart`/`find-restart`), so the existing lite
  primary-form-only expansion is behavior-identical here. The real restart gate
  is Postmodern proper, which is out of scope.
- **The `ironclad` slice will not be widened** (ciphers / the rest of
  public-key / prng / the other digests) -- the next real consumer decides.
  `dotimes-unrolled` users stay out with it: its definition loads, but no
  expansion of it does (`symbol-macrolet` is still unsupported).

## What is left

### Session record 2026-07-26 (the interpreter path is DONE, the compile path is not)

**The interpreter runs a real query round-trip on the FULL auth ladder.**
`trust`, `password`, `md5` and **SCRAM-SHA-256** all complete against a live
PostgreSQL 17 (`docker run postgres:17-alpine`)
and `(cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader)`
returns `((42 "hello"))`. The load order that works is package -> features ->
config -> oid -> errors -> data-types -> sql-string -> trivial-utf-8 ->
strings-ascii -> communicate -> messages -> ieee-floats -> interpret ->
saslprep -> scram -> protocol -> public -> bulk-copy, with `md5`,
`split-sequence`, `ironclad`, `cl-base64`, `cl-ppcre`, `uax-15` and
`alexandria` quickloaded first.

**Everything below was ADDED to make that happen** (all four backends unless
noted; each has a doc page + catalog entry + table row):

- CL primitives: `encode-universal-time` / `decode-universal-time` (prelude,
  era-based Gregorian; nil zone = GMT), `force-output` / `finish-output`,
  `listen` (real: `available()`/`ready()`; the component reports its chunk
  buffer; Preview 1 rejects it), `open-stream-p` (real against the stream
  table), `mismatch`, `digit-char`, `decode-float`, `arrayp`, `bit`,
  `hash-table-test`/`-size`/`-rehash-size`/`-rehash-threshold`,
  `with-open-stream`, `multiple-value-prog1`, `do-external-symbols`
  (interpreter-only), `internal-time-units-per-second` and
  `lambda-list-keywords` reader constants.
- `&whole` in defmacro AND destructuring-bind; `&rest`/`&body` followed by a
  destructuring PATTERN (alexandria's `if-let`).
- `intern` with a package designator (interpreter; the compilers lower it to a
  call-time signal), `(close stream :abort t)`, `(setf (ldb ...) v)`,
  a computed `gensym` prefix (compilers lower it to string construction --
  the printed name matches the interpreter on all four backends).
- **rontolisp:random-bytes** -- the cryptographic entropy primitive
  (`SecureRandom` / WASI `random_get`) that finally retired the signalling
  `ironclad-prng.lisp` stub: the shim now implements `*prng*`, `make-prng`,
  `random-data`, `random-bits` and `strong-random` FOR REAL (rejection
  sampling, so the SCRAM client nonce is uniform).
- Compile-path fixes: `#.` inside a backquote template no longer splits into
  construction code (`%read-eval-template`), `FreeVarAnalyzer` knows the
  `with-*` stream binders, `ClosRegistry.findClass` resolves a same-member
  name from a sibling package, an UNDEFINED condition type degrades to a plain
  simple-condition instead of failing the build, `:displaced-to` tolerates an
  and `:displaced-to` tolerates an explicit `nil` `:adjustable`.

### Reverted in this session, and why (do not just re-apply it)

**Widening `CrossLambdaExitLowering` to a plain `return` and to flet/labels
boundaries broke WASM and was backed out.** cl-postgres' `message-case` expands
into a `labels` local function that does `(return)` out of the enclosing
`loop` -- a cross-lambda exit the pass does not cover today (its documented
scope is a NAMED `return-from` crossing an explicit `(lambda ...)`). Adding a
nil-block scope for `loop`/`do`/`dotimes`/`dolist`/`%block`, a bare-`return`
case, and lambdaDepth+1 for flet/labels definition bodies made the JVM compile
that shape correctly (checked against the interpreter) -- and made
`ClPpcreE2eTest` trap at RUNTIME on BOTH wasm backends (`wasm trap:
unreachable`, exit 134), with `Uax15E2eTest` failing likewise and
`IroncladE2eTest` emitting an INVALID module ("expected eqref but nothing on
stack"). The `%nlx-catch`/`%nlx-throw` machinery therefore does not survive
this widening on wasm-GC as written. Re-doing it means designing the WASM side
FIRST, with `ClPpcreE2eTest`/`Uax15E2eTest`/`IroncladE2eTest` as the gate --
not extending the JVM side and assuming WASM follows.

### Session record 2026-07-26 (WASM leg): the widening is CORRECT; the trap is an ENGINE-LEVEL failure of the %nlx id compare

**The widening was re-applied** (uncommitted, in the working tree):
`CrossLambdaExitLowering` gained nil-block scopes for
`loop`/`do`/`do*`/`dotimes`/`dolist`/`prog`/`prog*`/`%block` and `(block nil ...)`,
a `transformReturn` for bare `(return [v])`, `(return-from nil ...)` routing through
the nil scope, and `transformFletLabels` (definition bodies at lambdaDepth+1). It also
currently carries two TEMPORARY debug hooks to strip before landing:
`-Drontolisp.nlx.debug` (prints every scope push / rewrite) and
`-Drontolisp.nlx.range=lo:hi` (applies only rewrites number lo..hi -- the bisection
gate that isolated the failing site).

**What works with it**: the todo's minimal repro (`loop`+`labels`+bare `(return)`)
returns 4 on ALL FOUR backends; `IroncladE2eTest`'s program passes on wasm P1 AND
`--component` (the "invalid module" of the previous session did NOT reproduce);
block-in-lambda + labels `return-from`, specials-in-let*, extended `for/then` loop
heads, and a faithful standalone port of cl-ppcre's whole scanner template all pass
on wasm. Interpreter and JVM agree everywhere.

**What still fails**: `ClPpcreE2eTest`'s program traps (`unreachable`, exit 134).
Minimal failing program (`--system-path src/test/resources/cl-ppcre`):

```lisp
(asdf:load-system :cl-ppcre)
(let ((sc (cl-ppcre:create-scanner "a")))
  (print (funcall sc "banana" 6 6)))
```

**The complete forensic chain** (all verified by patching the disassembled wat with
`wasm-tools print` / `parse` and re-running; every claim below has a probe behind it):

1. The trap is the top-level catch_all landing in `_start` = an UNCAUGHT rethrow of
   the `$block-exit` (tag 1) exception.
2. The exit IS caught first by the correct `%nlx-catch` landing (the scanner
   lambda's), but the id compare `car(payload) ref.eq unbox(local4)` fails, so the
   landing rethrows. Forcing the compare to constant-true makes the whole program
   produce the CORRECT output -- the only broken layer is tag identity.
3. Structure is sound end-to-end (verified in the wat): one mint per activation
   (`cell{null}` tag, boxed into local 4), the advance-fn closure env carries THE
   SAME box, the funcId->function dispatch table arms are correct (env-size
   fingerprints match creation sites pairwise), only one `local.set 4` per function,
   no `struct.set` ever writes the box, activation counting confirms no re-entry.
4. The decisive single-build trace (prologue stores the box to a fresh global g53;
   checks at creation / at the throw / at the landing): at the THROW the closure's
   env box `ref.eq`-equals g53 AND unboxes equal; at the LANDING (same activation by
   global counter) `local4 != g53`. That pair is wasm-impossible if execution is
   spec-conformant -- g53 was stored FROM local 4 in that same frame's prologue.
5. **Engines diverge on the identical binary**: with the trace probes patched in,
   V8 (node 22, `--experimental-wasm-exnref`) runs the SAME marker sequence but the
   landing compares succeed and the program completes correctly; wasmtime 46.0.1 AND
   47.0.2 fail the landing compares and trap. wasmtime is internally inconsistent
   within one run (fact 4). The UNPATCHED binary traps on both engines, so V8 is not
   simply "correct" -- both engines flip behavior when semantically-inert print calls
   are inserted, and the flip boundary differs per engine.
6. The trap is shape-dependent at the LISP-source level too: adding one top-level
   `(print :loaded)` between the load and the scanner use makes the full program pass
   (`sc8.lisp` vs `sc9.lisp` pair); carving unrelated DEAD code out of api.lisp
   (the top-level `(let* (...(create-scanner "[^a-zA-Z_0-9]"))...)` block) also
   flips it, while running the same char-class creation from user code does not.
   `-O opt-level=0` and `-O gc-zeal-alloc-counter=1` do NOT flip anything --
   deterministic per (binary, engine).

**Further single-build probes** (each of these is ONE binary, ONE run):

7. Payload integrity across the throw hop: the cons delivered to the landing
   `ref.eq`-equals the cons the thrower built (and their cars are identical). The
   exception transport is NOT corrupting anything.
8. The "ultimate" build (thrower stashes its env box AND the payload; all 105
   `struct.set 5 0` sites carry a watchpoint; landing compares everything):
   **the thrower's env box != the catching frame's local4** (two DISTINCT boxes,
   each internally consistent, ZERO writes to either). Yet a different build with a
   call-site probe showed the closure called at the loop head has env[1] == local4
   right before the call. The causal story itself ("stale closure ran" vs "same box,
   different unbox") FLIPS between builds that differ only by probe code -- on BOTH
   engines.
9. The `%nlx-catch` snapshot hardening (id evaluated once into a dedicated local at
   region entry, landing compares the snapshot -- now implemented in
   `WasmNlxCompiler`, kept: it is semantically sound and regressed nothing) does
   NOT fix the trap. So the failing channel is not the landing's boxed re-read.

**Interpretation**: the module's OBSERVED behavior changes under semantically inert
code insertions, deterministically per (binary, engine), differently per engine, and
the interpreter/JVM outputs prove the Lisp-level semantics have no stale exit. Every
compiler-emitted layer that could pair the wrong tag with the wrong catch has been
individually exonerated IN THE WAT (mint, box, closure env, call site, dispatch
tables, payload transport, writer-freedom). What has NOT been possible is verifying
all links in ONE binary -- each probe combination flips which link looks broken.
That pattern fits a runtime miscompilation (register allocation / stack maps around
`try_table` + GC refs) present in some form in BOTH wasmtime 46/47 and V8 (node 22),
each with different trigger shapes -- or a module-side violation the wat-level checks
cannot see (`wasm-tools` round-trips preserve behavior, so any such bug survives
reprinting).

**RESOLUTION: `%nlx-tag` on wasm-GC now mints an i31 VALUE id** (the next integer
from the new `NLX_ID_CTR` linear-memory cell at address 196; `ref.eq` on i31 is
value equality), and `%nlx-catch` snapshots the id into a dedicated local at region
entry. GC-struct identity is out of the matching path entirely. This fixed EVERY
repro immediately: the count-matches/all-matches/scanner programs, the full
ClPpcreE2eTest exercise on P1 AND `--component`, and the ironclad exercise -- with
zero regressions in the small-case battery. Whose bug the identity scheme tripped
(wasmtime's, V8's, or a layout-sensitive emitter bug the probes could not see)
remains UNRESOLVED but is no longer load-bearing; if it is ever worth pursuing, the
sc8/sc9 flip pair (one `(print :loaded)` apart) and the probe recipes above are the
seed. The JVM keeps `new Object()` identity tags, which are sound there.

**Verified after the fix**: full `./mvnw test` GREEN (4270 tests, 0 failures --
includes `ClPpcreE2eTest` 4/4, `IroncladE2eTest` 4/4, `Uax15E2eTest` 4/4, each on
all four backends); native-image `CiSpecE2eTest` 980/980 against a fresh `-Pnative`
binary; `-Pweb compile` clean; javadoc clean except the known `Version` error; the
message-case-shaped minimal repro returns 4 on all four backends (P1 and
`--component`); `.kb/do-return-block.md` updated (widened lowering scope + the
wasm i31 id design + why identity was retired). All of this is UNCOMMITTED in the
working tree: `compiler/CrossLambdaExitLowering.java` (the widening),
`codegen/wasm/WasmNlxCompiler.java` (i31 ids + entry snapshot),
`codegen/wasm/WasmLispCompiler.java` (the `NLX_ID_CTR_ADDR` cell), this file and
`.kb/do-return-block.md`.

Repro/tooling notes: probe workflow is `wasm-tools print X.wasm > X.wat`, patch with
python by exact line, `wasm-tools parse X.wat -o X2.wasm`, run with
`wasmtime run -W gc -W exceptions=y` (46.0.1 on PATH; 47.0.2 binary was fetched to
the session scratchpad) or `node --experimental-wasm-exnref runwasi.js X.wasm`
(node:wasi preview1 harness, trivial to recreate). `-Drontolisp.nlx.range=8:8`
narrows the cl-ppcre program to ONE lowered site (the start-string-test variant)
and still traps.

### Where the next session starts

The interpreter work is committed (`dc8bc14c`). The compile path is two
INDEPENDENT pieces of design work, either of which can go first:

- **JVM**: the oversized-method / 16-bit branch-offset overflow below. Measure
  which expansion inflates `%SHARED-INITIALIZE--m2` BEFORE touching
  `am.ik.jvm` -- if it is the runtime-`typep` dispatch chain (whose length
  grows with the number of registered classes), the fix belongs in the
  expansion, and wide-branch relaxation in the emitter is the heavier
  alternative.
- **WASM**: the cross-lambda plain-`return` lowering. The widening is
  RE-APPLIED and correct (see the 2026-07-26 WASM-leg session record above);
  what remains is the `%nlx` tag-identity failure at cl-ppcre scale --
  engine-divergent, fully characterized, with a hardening design sketched
  (snapshot/i31 ids in `WasmNlxCompiler`). Follow that record's "Next steps".

The native-image `CiSpecE2eTest` was run 2026-07-26 (WASM-leg session) against a
fresh `-Pnative` binary: 980/980 green -- this clears the debt from the
interpreter session too.

### Session record 2026-07-26 (JVM leg): DONE -- the full stack compiles AND queries live on the JVM

The measurement the previous session asked for settled the design: with
`-Drontolisp.jvm.debug-method-sizes=true` (a new permanent debug flag) the
inflated bodies were (1) computed-`typep` inline dispatch, 3 sites x ~37 KB
(ironclad pbkdf1 `shared-initialize`, alexandria `copy-sequence` + `of-type`),
(2) `%SUBTYPEP-RUNTIME` at 59 KB (same disease, silently near the cliff), and
(3) `CL-POSTGRES::GET-ERROR` at **90 KB** -- the computed-`error`-datum
dispatch inlining a typed expansion per registered class, past even the JVM's
64 KB HARD method limit, which no wide-branch relaxation can fix. The fix
therefore went into the EXPANSIONS, all three onto one mechanism: quoted DATA
tables in chunked top-level defvars + small injected dispatch defuns
(`%typep-runtime` + `%typep-tag-table%`, `%subtypep-ancestor-table%`,
`%error-runtime` + per-condition-class `%ERROR-RT-n` helpers). Two backend
scale guards joined them: `_invoke_N` (66 KB at arity 9) and `_lookup` (60 KB)
are now emitted as chained ~24 KB segments, and `patchBranch` /
`ByteCodeWriter.writeCode` now throw loudly instead of silently truncating.
Full mechanics: `.kb/jvm-method-size-limits.md`, `.kb/clos.md`,
`.kb/error-handling.md`.

Gates cleared along the way (each a general fix, not a cl-postgres hack):

- `(progn)` in a value position pushed nothing on the JVM (message-case's
  empty CloseComplete arm) -- operand-stack mismatch, fixed + pinned.
- `handler-bind` (dead code here: `wait-for-notification`) lowers to a
  call-time signal on both compilers, the 2-arg-intern stub contract.
- A call to an UNDEFINED function (`stream-error-stream`, error path only)
  compiles to the interpreter's call-time "The function X is undefined"
  signal plus a compile-time warning, instead of rejecting the program.
- `(funcall f args...)` with a SYMBOL value resolves late through `_lookup`
  (the row-reader designator `'cl-postgres:list-row-reader`); `_lookup` is
  emitted whenever indirect calls exist, not only under eval.
- `(ql:quickload "cl-postgres")` as ONE form now resolves on the compile
  paths: an `AsdOverrides` entry (`cl-postgres-deps.asd`) declares the
  dependencies upstream under-declares (alexandria, cl-ppcre, usocket).

**Verified**: the full program (quickloads + all driver files, exact
interpreter load order) compiles to `Prog.class` and runs
`(cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader)`
plus a `generate_series` query against live `postgres:17-alpine` (trust,
plain TCP) -- output IDENTICAL to the interpreter run of the same program.
`./mvnw test` 4274/4274 green before the wasm-side follow-ups below.

### Session record 2026-07-26 (WASM component leg, same session): 3 general fixes landed, 2 attempts REVERTED, one gate left

The component leg surfaced its own gates. Three fixes are general and landed;
two more were attempted, broke the ci-spec corpus, and were reverted with
their analysis kept (`.todo/177`, `.todo/178`).

Landed:

- A funcall whose ARITY exceeds `MAX_CALLABLE_ARITY` (7) silently called the
  NEIGHBORING runtime helper (`initiate-ssl`'s 9-arg `make-ssl-stream`
  funcall -> invalid module); it now compiles to a call-time signal.
- The symbol-designator funcall on wasm resolves through the eval registry's
  `_lookup` and synthesizes a `{funcId, null-env}` closure (every dispatch
  case casts the funcval). Emission is GATED on the program actually having a
  runtime designator: the registry embeds every defun NAME, so emitting it
  unconditionally made two programs with identical CODE differ in bytes (the
  wit-import byte-identity pins caught it).
- **An ambiguous slot NAME read dispatched into an unrelated package's reader
  generic**: `(slot-value <cl-postgres protocol-error> 'message)` routed
  through `IRONCLAD::MESSAGE` (the only same-named reader) and died with "No
  applicable method" while rendering the protocol error's own report. Literal
  ambiguous names now take an instance-TAG dispatch, the read-side twin of the
  existing ambiguous WRITE. The dispatcher's no-match error also names the
  argument's class now, which is what made this diagnosable.
- Byte transparency of the component socket path was verified 0..255 (an
  echo probe), so the binary protocol framing is sound.

Reverted, with the analysis kept:

- **The shared-memory map collision** (`.todo/178`): real -- the core's static
  data, the canonical-ABI bump and the adapter's page-5 scratch all overlap for
  a large program, which is what produced "unknown handle index" inside
  `fd_write`. The fix attempt (core data at page 6 + a RINGING `cabi_realloc`)
  broke the ci-spec corpus: the adapter keeps pointers into ABI allocations
  across calls, so the ring recycles live memory.
- **`read-sequence`/`write-sequence` + 3-arg `read-byte` reaching the socket
  dispatch** (`.todo/177`): needed by cl-postgres, but pre-expanding the
  sequence ops in `WasmSocketsRewrite` and widening `%io-read-byte` made the
  corpus trap on a plain `(read-byte in nil -1)` at EOF over a FILE handle in
  a top-level (async-context) `with-open-file` -- a 3-arg read is not promoted
  to an await, so it takes the sync `%io-*` dispatch inside an async task.

**What is left**: the component connects, sends `startup-message`, and walks
the whole post-auth message stream correctly -- verified message by message
against the JVM (`AuthenticationOk`, 14 `ParameterStatus`, `BackendKeyData`,
all with identical tags and lengths) -- and then HANGS on the read that should
return `ReadyForQuery` ('Z'), which the JVM receives immediately. A hand-rolled
tag walk over the same socket reaches the same point, so the driver's own
`message-case` is not implicated. Next step: instrument `%sock-fill` /
`sock-stream-read` at the point after `BackendKeyData` -- does the host return
a chunk that the entry then discards, or does the async read never settle?
(`.todo/176` records two separate top-level-only async findings from the same
tracing session: argument-order reversal of multiple promoted reads in ONE
call, and an `fd_write` handle crash after many interleaved reads/prints.)

### The remaining gate: the JVM backend cannot build the whole stack yet (RESOLVED above; historical analysis follows)

The interpreter is green end to end; the JVM compile of the full program
(quickloads + all cl-postgres files) stops at

```
StackMapAugmenter: method IRONCLAD::%SHARED-INITIALIZE--m2: Index -31123 out of bounds
```

which is a **16-bit branch-offset overflow**: one emitted method body has grown
past 32 KB, so a `goto`/`if` offset no longer fits a signed short. `am.ik.jvm`
declares `GOTO_W` but never emits it, and the emitter patches branches in place
(no relaxation pass), so this needs either wide-branch relaxation in
`am.ik.jvm` or a smaller method. Worth measuring FIRST which expansion inflates
that body -- a `(typep x runtime-type)` inside ironclad's `shared-initialize
:after` expands to a per-class dispatch chain, and the class count grows with
every loaded library, so the fix may belong there rather than in the emitter.
This is the same family as `.todo/137` (the 255-local-slot ceiling): the JVM
backend has no scale guards.

WASM was not reached. Both wasm-GC backends are still to be tried, on the
component model over WASI 0.3 sockets (user, 2026-07-26).

### Then: end-to-end

Testcontainers PostgreSQL (user instruction). All four auth methods (`trust`,
`password`, `md5`, SCRAM-SHA-256) are verified BY HAND on the interpreter
already; what is missing is the automated, opt-in env-gated test (the
`RONTOLISP_HTTP_E2E` pattern). `examples/db/postgres-hello.lisp` + its README
landed this session.

**Two measured performance facts to plan the E2E around** (neither is a
correctness problem):

1. `uax-15` alone takes ~10 minutes to load on the interpreter (34k lines of
   UnicodeData.txt parsed in interpreted Lisp), and cl-postgres pulls it
   through `saslprep`. ASCII credentials never call `uax-15:normalize`
   (`saslprep-normalize` short-circuits on printable ASCII), so only the LOAD
   costs, not the run -- but an E2E that quickloads per backend is not viable
   as written.
2. **SCRAM-SHA-256 needs a raised `authentication_timeout` on the
   interpreter.** With the 60-second default it fails as `READ-BYTE: end of
   file` while the server log says `FATAL: canceling authentication due to
   timeout` -- the 4096-round PBKDF2 (two HMAC-SHA256 per round, in interpreted
   Lisp) does not finish in time. With
   `postgres -c authentication_timeout=600` the same program authenticates and
   queries successfully (verified 2026-07-26). The E2E must either raise that
   setting or run SCRAM on a compiled backend.

## Backend target: ALL FOUR (user, 2026-07-25)

Not a scope decision to make later -- the driver runs on the interpreter, the
JVM and both wasm-GC backends, the same bar every loadable library here meets.
The earlier "interpreter + JVM first" note is retracted: half its reasoning
(int8/OID arithmetic beyond i31) expired when arbitrary-precision exact
integers landed on both wasm backends (`.kb/wasm-bignum.md`).

One real obstacle survives, and it is the one to plan around rather than
discover late: **TLS is interpreter/JVM only** (`.kb/tcp-sockets.md`), so a
connection that negotiates SSL cannot complete on WASM. A plain-TCP connection
can. Two consequences:

- The M5 auth ladder (trust -> password -> md5 -> SCRAM) is all plain TCP, so
  it is reachable on four backends as written -- run it on four, not two.
- Whether `sslmode` support means teaching WASM TLS or documenting an explicit
  per-backend limitation is a decision for whoever hits it; do not let it stall
  the plain-TCP path.

## Out of scope

Postmodern proper (s-sql, the `postmodern` system) is a SEPARATE follow-up on
top -- it adds heavy CLOS/MOP usage and is where the real restart-system gate
lives.
