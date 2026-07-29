# Milestone: (ql:quickload "postmodern") non-MOP build runs on all backends

## ⟶ STATUS 2026-07-29: THE GOAL PROGRAM RUNS ON ALL THREE TCP-CAPABLE BACKENDS

The verbatim upstream postmodern sources (minus `table.lisp`, the
`:if-feature :postmodern-use-mop` component -- the DAO layer is
`.todo/203`) load and run a live round trip on the interpreter, the JVM and
the WASM `--component` backend. Preview 1 is a compile error by design
(no sockets, same as `.todo/115`). The goal program, verbatim:

```lisp
(ql:quickload "postmodern")
(pomo:with-connection '("mydb" "myuser" "mypass" "127.0.0.1")
  (pomo:execute (:create-table 'person ((id :type integer :primary-key t)
                                        (name :type text))))
  (pomo:execute (:insert-into 'person :set 'id 1 'name "alice"))
  (print (pomo:query (:select '* :from 'person)))
  (pomo:with-transaction ()
    (pomo:execute (:update 'person :set 'name "bob" :where (:= 'id 1))))
  (print (pomo:query (:select 'name :from 'person) :single)))
```

prints `((1 "alice"))` then `"bob"`, byte-identically on all three
TCP-capable backends. `doquery`, `defprepared`, `sql-compile`, every
`*result-styles*` format (`:alists` / `:plists` / `:column` / `:single` / ...),
the connection pool and the prepared-statement `:reconnect` restart were probed
alongside it and work on all three.

**One leg is short of the goal**: `pomo:retry-transaction` runs correctly on the
interpreter and throws out of `invoke-restart` on both compile backends. That is
`.todo/207`, filed with its reproduction; it is not on the milestone program's
path, and `PostmodernE2eTest` runs that leg on the interpreter only until it
lands. Decide when closing this file whether 207 closes with it or outlives it.

Everything below is the record of what landed and what deliberately did not.

## What the prerequisites left, and what this pass had to add

`.todo/195`-`.todo/200`, `.todo/204` and `.todo/205` had already landed, and
after them `(ql:quickload "postmodern")` LOADED. Four gaps stood between that
and a program that runs; none of them is postmodern-specific, and all four are
pinned on all four backends:

1. **`concatenate 'string` takes any character sequence** -- s-sql's
   `expand-table-name` builds `"CREATE TABLE person"` as
   `(concatenate 'string (unless tableset "TABLE ") (to-sql-name name))`, and
   `nil` is the empty sequence in CL. The `.kb` file's own re-evaluation
   trigger prescribed the shape: ONE cheap helper called once per argument,
   never an inlined `coerce` loop per site. `%seq-string` is a
   `BuiltinFunctionWrappers` entry (so no backend needed a new primitive) gated
   on `ConcatenateForms.needsSeqString`; that gate must stay exact, because the
   macro expander emits `concatenate 'string` of its own during codegen.
   `.kb/concatenate-result-families.md`.
2. **Quoted DATA resolves against the reading package** -- `*result-styles*` is
   a `defparameter` table of `(:rows list-row-reader all-rows)` triples that the
   `query` macro splices into its expansion, so `ALL-ROWS` has to be
   `POSTMODERN::ALL-ROWS`. `PackageResolver` resolved a quoted LONE symbol but
   left quoted LISTS alone; now every quoted datum resolves, with data position
   made more permissive than code position (`inQuotedData`).
   `.kb/packages.md`.
3. **An `:export` of an INHERITED name re-exports the source symbol** --
   postmodern `(:use :s-sql)` re-exports `#:sql` / `#:sql-compile`, which s-sql
   defines. Recorded at `defpackage` time as a `LispPackage.imports` entry, the
   redirect both resolution paths already honour. `.kb/packages.md`.
4. **Two `FreeVarAnalyzer` holes** -- `(bt:with-lock-held (lock) ...)` puts a
   VALUE in its one-element spec (the opposite of the `with-*` stream macros),
   so the default walk read `(LOCK)` as a call and a defun closing over a
   top-level let-bound lock -- `prepare.lisp`'s statement-id counter -- failed
   to compile; and `*error-output*` / `*standard-output*` are globals, not
   lexicals a lambda captures, which `generate-prepared`'s
   `(format *error-output* ...)` handler tripped over.

## Verification that landed

- **`PostmodernE2eTest`** (new, opt-in `RONTOLISP_POSTGRES_E2E=1`, the
  `ClPostgresE2eTest` harness): the milestone program on all three backends,
  plus the restart system's only honest E2E: a **reconnect** exercise on all
  three backends (the server drops the connection under a `defprepared` call and
  the `:reconnect` restart has to make the same call answer) and a **retry**
  exercise (a `with-transaction` body inserts and calls `retry-transaction`,
  which only yields `(2 20)` if the insert was really rolled back and replayed)
  that runs on the INTERPRETER only -- both compile backends throw out of
  `invoke-restart` there, which is `.todo/207`. Preview 1's compile error is
  pinned too.
- **ci-spec `postmodern-non-mop-milestone`** for the socket-free half (the four
  language mechanics above in the shape the library uses them), and the
  `concatenate-result-families` case grew the mixed-sequence string family.
  A ci-spec case cannot `ql:quickload` (no network in that job), so this follows
  the `s-sql-enablement-language-group` / `postmodern-language-incidentals`
  convention of pinning the MECHANICS rather than the library.
- Per-backend pins: `LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`, `PackageResolverTest`.
- Docs: a postmodern row in `doc/{en,ja}/guides/asdf-systems.md` (the
  established one-row-per-library shape, next to s-sql), naming the non-MOP
  scope, the Preview 1 limitation and the EH-mode wasmtime flags. `.kb/asdf.md`
  and `.kb/packages.md` / `.kb/concatenate-result-families.md` updated.
- `.todo/115`'s "Out of scope" pointer is closed.

## Load time and artifact size (measured 2026-07-29, the program above)

| backend | figure |
| --- | --- |
| interpreter | ~63 s end to end -- dominated by SCRAM-SHA-256's 4096-round PBKDF2 (`.todo/188`), not by loading postmodern |
| JVM | ~13 s to quickload + compile; **7.8 MB** `.class` |
| WASM component | **8.4 MB** `.wasm` |

Neither ceiling was hit with pruning on. With `--no-prune` the JVM backend
**fails loudly** -- `while compiling defun POSTMODERN::MAP-SLOTS: branch offset
34371 ... overflows the signed 16-bit branch encoding` -- so
`LibraryDefunPruner` is what keeps this tree compilable, and the guard in
`.kb/jvm-method-size-limits.md` does its job. That is the designed behaviour of
the escape hatch, not a defect, but it is worth knowing before anyone reaches
for `--no-prune` on a quickload-heavy program.

## Deliberately NOT in scope (owned elsewhere)

- **`.todo/203`** -- the DAO/MOP layer (`table.lisp`). The build takes
  `:postmodern-use-mop` OFF; flipping it is a feature flag in the bundled
  replacement `.asd`, not a re-edit.
- **`.todo/206`** (raised by this pass) -- a condition's `:report` is applied
  when it is SIGNALLED but never when it is PRINTED, so
  `(format t "~a" condition)` dumps slots and cl-postgres surfaces every server
  NOTICE as its raw format-control string.
- **`.todo/149`** -- `*error-output*` is not routed to the error stream, so
  postmodern's own reconnect diagnostic lands on stdout.
  `PostmodernE2eTest.programOutput` drops that line before comparing, for both
  reasons at once; delete the filter when they land.
- **`.todo/207`** (raised by this pass) -- `pomo:retry-transaction` throws
  "THROW: no enclosing catch for the tag" out of `invoke-restart` on both
  compile backends while the interpreter is correct. The milestone program does
  not retry, so it is not on its path; the file carries the reproduction and
  everything already ruled out.
- **`.todo/181`** -- a `*features*` push is invisible to the reader, which is
  why the replacement `.asd` declares both postmodern features statically.
