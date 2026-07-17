# Make cl-postgres (Postmodern's PostgreSQL driver) actually run

Goal: `(ql:quickload "cl-postgres")` loads, and a real query round-trip works
against a live PostgreSQL:

```lisp
(ql:quickload "cl-postgres")
(let ((conn (cl-postgres:open-database "mydb" "myuser" "mypass" "127.0.0.1" 5432)))
  (print (cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
```

The usocket shim (todo-114, `.kb/tcp-sockets.md`) already covers the socket
layer cl-postgres uses (`socket-connect` + `:element-type '(unsigned-byte 8)`
+ `socket-stream`); the `#-(or allegro sbcl ccl)` reader conditionals in
`public.lisp` select the usocket path under rontolisp's feature set. This todo
is everything ABOVE the socket.

## Empirical state (2026-07-12)

**M1 (the `.asd` front-end) is merged on develop**: the verbatim
cl-postgres.asd parses (defparameter env, `#.*string-file*` resolves to
"strings-ascii", the usocket/sb-bsd-sockets `:feature` clauses drop). Pinned
by `AsdfSystemsTest.parsesTheClPostgresAsdHeaderShape`.

**Everything after M1 is parked on the `cl-postgres-wip` branch, which is
FROZEN -- do not touch or rebase it; adoption is undecided**: the M2 crypto
shim systems (md5/ironclad/cl-base64/uax-15 over the java: bridge, +
cl-ppcre/alexandria reference shims), and the M4 pre-work batch (source-file
`#.` read-time eval via ReadTimeEvaluator, the find-package fold, defgeneric
inline `:method`, `with-standard-io-syntax`, `encode-universal-time`). With
that branch, cl-postgres loads through interpret.lisp on the interpreter.

Its last known failure (`defmethod: unknown specializer bad-char-error` --
define-condition classes) is **stale**: that was the todo-116 Phase 2 gate, and
it closed on develop (Phases 1-3 shipped, commit a8b957b). The branch has not
been re-run since, so its real next gate is unknown -- re-run it against
post-116 develop before planning anything on top of it.

Iterate with the cached copy in
`~/.rontolisp/quicklisp/software/postmodern-*/cl-postgres/`.

## Blocker inventory (grep counts over cl-postgres/*.lisp)

1. **.asd front-end** (first gate, small): `defparameter` forms in the .asd
   feeding `#.` component names (`(:file #.*string-file*)` — currently the
   skip leaves the component nameless), and `(:feature (:or :allegro ...)
   "usocket")` clauses inside `:depends-on` (must evaluate against
   `reader.Features`; under rontolisp the usocket clause is feature-false —
   fine, usocket is built in). Suggested lite fix: allow top-level
   `defparameter` of a literal/`#+`-conditional value in a `.asd` into the
   parse-time data env, and resolve `#.<var>` in the .asd against it.
2. **`#.` read-time eval in SOURCE files** (~46 sites across 7 files, e.g.
   constant tables in `interpret.lisp`/`oid.lisp`): currently a hard read
   error outside `.asd`. Needs a restricted read-time evaluator (literals +
   arithmetic + quoted data would cover most sites) or a documented
   preprocessing step. Survey the actual 46 forms before designing.
3. **Condition system — mostly SHIPPED** (todo-116 Phases 1-3, commit
   a8b957b, 2026-07-12; the durable record is `.kb/error-handling.md`, and
   `.todo/039` remains the API catalog). `define-condition` with
   slots/`:reader`s (11), `handler-case` (6) and `unwind-protect` (5) all
   work on the interpreter + JVM, and wasm-GC catches too since todo-129
   (only `--no-gc` rejects the catching forms). So cl-postgres's
   `errors.lisp` `database-error` hierarchy is expressible today. What is
   left for this todo is small and specific:
   - **`handler-bind`** — exactly ONE real site: `public.lisp:386`,
     `wait-for-notification`, catching `postgresql-notification`
     (LISTEN/NOTIFY only, not on the `exec-query` path). The
     `errors.lisp:142` grep hit is inside a docstring, not code. Until it
     exists, the file still LOADS (defun bodies are lazy on the
     interpreter); only a call to `wait-for-notification` fails, so
     LISTEN/NOTIFY is simply unsupported for now.
   - **`cerror`** — 4 sites (protocol.lisp:269/289 auth edge + SCRAM
     signature checks); needs a lite `cerror` → `error` lowering, an
     M4/M5-sized item.
   - **`restart-case`** (4 sites) needs **nothing**. The todo-116 Phase 4
     survey (see `.kb/error-handling.md`, "The Phase 4 survey") proved the
     verbatim cl-postgres needs NO restart system at all: all 4 sites are
     `(restart-case (error X) (clauses...))`, and cl-postgres never invokes
     a restart itself (zero library-side `invoke-restart`/`find-restart`),
     so the existing lite primary-form-only `expandRestartCase` is
     behavior-identical to real CL here. The real restart gate is Postmodern
     proper, which is out of scope (see the milestones).
4. **Dependency systems**: `:depends-on ("md5" "split-sequence" "ironclad"
   "cl-base64" "uax-15")`.
   - `split-sequence` already runs (todo-054 verification chain).
   - `md5`: only used for `AuthenticationMD5Password` (`md5:md5sum-sequence`,
     one call site in messages.lisp). Port or shim.
   - `ironclad`/`cl-base64`/`uax-15`: reached ONLY from `scram.lisp` /
     `saslprep.lisp` (SCRAM-SHA-256 auth: sha256/hmac/pbkdf2 digests, base64,
     unicode normalization). Porting ironclad/uax-15 wholesale is
     unrealistic. **Strategy: extend the todo-114 `BuiltinSystems` precedent —
     ship mini shim packages backed by JDK primitives** (`MessageDigest`
     SHA-256, `javax.crypto.Mac` HmacSHA256, `SecretKeyFactory`
     PBKDF2WithHmacSHA256, `java.util.Base64`, `java.text.Normalizer` for
     NFKC) registered as built-in systems "ironclad"/"cl-base64"/"uax-15"/
     "md5" exporting exactly the names cl-postgres calls (~12 functions
     total, enumerated in scram.lisp lines 205-320). Interpreter/JVM real;
     WASM component = error or nil per op. cl-base64's real source stays
     blocked on compiled string setf anyway (see asdf-library-candidates
     memory).
5. **Stream/socket gaps**: `force-output` (16 — no-op shim is correct, socket
   writes are unbuffered), `(listen socket)` (1, the MITM check — needs an
   `InputStream.available()`-style primitive or a documented stub),
   `read-sequence`/`write-sequence` on SOCKET handles (the built-ins exist
   for file streams; verify the socket branch), plus cl-postgres's own
   buffered reader over `read-byte` (21) — byte-at-a-time socket reads will
   be slow; consider a buffered socket read primitive later (perf, not
   correctness).
6. **CLOS/macro surface**: 4 `defclass` / 7 `defgeneric` / 3 `defmethod`
   (single dispatch — should fit the static subset; verify `:initform`/
   `:reader` coverage), 18 `defmacro` (loadable-library defmacro works since
   the cl-who work), `eval-when` (4, expands to progn — check none need real
   compile-time eval), `declaim inline` (no-op, fine), `the` (no-op, fine).
7. **`loop` coverage**: 72 uses across 13 files — rontolisp's `loop` is a
   subset; expect `:for x :across vector`, `:collect ... :into`, multi-clause
   forms. Inventory the failing shapes by iterating file loads and extend
   `expandLoop` case by case.
8. **Misc**: `with-standard-io-syntax` (3, in sql-string printing — lite
   no-op progn is probably acceptable), `scale-float` (1, ieee-floats.lisp;
   cl-postgres bundles its own float codec — check `ieee-floats.lisp`
   actually compiles), int8/OID values need arithmetic beyond i31 on WASM
   (bignum path exists on interpreter/JVM; WASM floats — acceptable, document).

## Suggested milestones

- **M1 (front-end)**: DONE 2026-07-11, merged on develop -- .asd
  defparameter/`#.`/`:feature` support; the cl-postgres system graph parses
  and file loading starts. Pinned by AsdfSystemsTest over the verbatim
  cl-postgres.asd header shape.
- **M2 (deps)**: built-in shim systems "md5"/"ironclad"/"cl-base64"/"uax-15"
  (BuiltinSystems entries + JDK-backed built-ins, usocket pattern) →
  `:depends-on` chain resolves without network beyond the postmodern tarball.
- **M3 (conditions + unwind-protect)**: todo-116. Gate: `errors.lisp` +
  `protocol.lisp` load; `handler-case` over `database-error` works on the
  interpreter.
- **M4 (grind)**: iterate `load`-next-file/fix-first-error through
  `communicate.lisp` → `messages.lisp` → `interpret.lisp` → `protocol.lisp`
  → `public.lisp` (loop shapes, `#.` sites, stream gaps land here).
- **M5 (e2e)**: Docker/testcontainers PostgreSQL; `trust` auth first (no
  digests needed!), then `password`, `md5`, finally SCRAM-SHA-256. A
  `LispEvaluatorTest`-style opt-in test (env-gated like RONTOLISP_HTTP_E2E)
  plus an `examples/db/` program with per-backend headers.
- Postmodern proper (s-sql, the `postmodern` system) is a SEPARATE follow-up
  on top — it adds heavy CLOS/MOP usage; do not scope it here.

## Scope decisions to make early

- Interpreter/JVM only first? (WASM lacks TLS and bignums — a
  realistic driver target is interpreter + JVM; WASM = document as
  unsupported for now.)
- Vendor a patched cl-postgres (skip scram/saslprep files via a rontolisp
  feature) vs. running the verbatim upstream source. Verbatim is the todo-054
  tradition (split-sequence/cl-who run unmodified) and should stay the goal;
  a temporary vendored subset is acceptable as an M4 stepping stone.
