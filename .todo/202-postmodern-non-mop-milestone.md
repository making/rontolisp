# Milestone: (ql:quickload "postmodern") non-MOP build runs on all backends

Goal: the verbatim upstream postmodern sources (minus `table.lisp`, which is
`:if-feature :postmodern-use-mop` -- the DAO layer is
`.todo/203-dao-mop-layer.md`) load and run a live round-trip on interpreter,
JVM and WASM `--component` (Preview 1 stays a TCP compile error by design,
same as `.todo/115`):

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

This exercises the compile-time `query`/`execute` machinery (S-SQL expanded
at macroexpansion via `*result-styles*`), prepared statements
(`generate-prepared`'s nested handler-bind + `:reconnect` restarts -- the hot
path), transactions (`call-with-transaction`'s tagbody/restart-case/
unwind-protect knot + `retry-transaction`), the connection pool
(`change-class`, macrolet place), `deftable`, and `doquery`.

## Prerequisites (the actual work lives there)

- `.todo/195-s-sql-support.md` -- s-sql layer
- ~~`.todo/196-restart-system.md`~~ -- DONE (2026-07-29): handler-bind runs
  handlers at the signal point, restart-case/restart-bind/with-simple-restart
  establish restarts, find-restart returns a first-class object,
  invoke-restart/compute-restarts/muffle-warning/abort/continue drive them and
  `cerror` is continuable -- one shared lowering on all four backends
  (`.kb/error-handling.md` "Phase 4"). Every postmodern shape the survey named
  is pinned per backend and cross-backend (ci-spec `restart-system`).
- `.todo/197-catch-throw.md` -- json-encoder
- `.todo/198-runtime-package-and-symbol-ops.md`
- ~~`.todo/199-clos-gaps-for-postmodern-non-mop.md`~~ -- DONE: inherited-slot
  shadowing, real slot unboundness (`unbound-slot` on every backend),
  in-place `change-class`, `print-object`, `with-accessors`, `with-slots` over
  a struct, `:default-initargs` on a typed signal (`.kb/clos.md`)
- ~~`.todo/200-postmodern-language-incidentals.md`~~ -- DONE (2026-07-29). The
  format/`getf`/`rassoc-if`/`string-trim` half landed first; the remaining
  string/character stream group landed with it: `make-string-output-stream` /
  `get-output-stream-string` (public names over the existing `%` internals, with
  CL's clear-on-read), `peek-char` (all three peek types), a TYPED `end-of-file`
  from `read-char`/`read-byte`/explicit-eof-error-p `read-line` -- which is what
  makes `execute-file.lisp`'s lexer `handler-case` fire -- and
  `make-synonym-stream` (lite: resolved once, at construction; the reason and the
  re-evaluation trigger are in `.kb/read-load-streams.md`). All on four backends,
  pinned by the `postmodern-language-incidentals` ci-spec case. Still open in that
  area, owned elsewhere: `.todo/181` (`*features*` pushes invisible to the reader,
  a FIDELITY gap for json-encoder, not a correctness one) and `.todo/149`'s
  "an explicit nil stream argument must mean `*standard-output*`".

The `.asd` override and dependency plumbing (old `.todo/201`) has LANDED:
`(ql:quickload "postmodern")` resolves and orders the whole graph; the
`postmodern/config.lisp` stop on `make-synonym-stream` is gone with `.todo/200`,
so the next probe of where the load stops has to be re-run. See the postmodern section of `.kb/asdf.md` for what the replacement
`.asd` decided. Two pieces were split out of it and are NOT prerequisites of
this milestone:

- `.todo/204-mutex-primitive-and-postmodern-thread-safe.md` -- the build is
  `:postmodern-thread-safe` OFF, so the connection pool and the
  prepared-statement id counter are racy under concurrent handlers. Fine for
  the single-threaded milestone program; not fine for a `serve` handler.
- ~~`.todo/205-probe-file-and-uiop-file-exists-p.md`~~ -- DONE: `probe-file` is
  a real primitive on all four backends and `uiop:file-exists-p` lowers onto it
  (`.kb/read-load-streams.md`). `pomo:execute-file` has its
  `restart-case` now (todo-196) and still needs
  `alexandria:read-file-into-string`. The milestone program
  does not use it.

## Milestone-level work (not covered by the prerequisites)

- Extend `ClPostgresE2eTest` (or a sibling `PostmodernE2eTest`, same
  Testcontainers harness, opt-in `RONTOLISP_POSTGRES_E2E=1`) driving the
  program above on the three TCP-capable backends with byte-identical output.
  Include one leg that FORCES the reconnect/retry path (kill the connection
  under a `defprepared` call; drop a table mid-`with-transaction` and
  `retry-transaction`) -- that is the restart system's only honest E2E.
- `ci-spec.yaml` cases for the socket-free parts (s-sql string generation,
  json-encoder) so the native-image job covers them.
- Load-time and artifact-size measurement: postmodern adds ~5000 lines + 141
  s-sql methods on top of the cl-postgres stack; watch the JVM constant-pool/
  method-size ceilings and wasm module size (`.kb/jvm-method-size-limits.md`,
  `.kb/wasm-function-body-size.md`, `LibraryDefunPruner` coverage for the
  new tree).
- Docs: a postmodern library page under `doc/{en,ja}/` (mirrored, same
  fences), noting the non-MOP scope, the Preview-1 limitation, and the EH-mode
  wasmtime flags (every postmodern program has handler-case/unwind-protect,
  so `-W exceptions=y` is always required).
- Close the loop on `.todo/115`'s "Out of scope" pointer once this lands.
