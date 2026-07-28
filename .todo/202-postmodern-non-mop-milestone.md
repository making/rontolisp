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
- `.todo/196-restart-system.md` -- handler-bind + restart stack (largest gate)
- `.todo/197-catch-throw.md` -- json-encoder
- `.todo/198-runtime-package-and-symbol-ops.md`
- `.todo/199-clos-gaps-for-postmodern-non-mop.md` -- slot shadowing,
  change-class, real slot-boundp, print-object, with-accessors
- `.todo/200-postmodern-language-incidentals.md` -- format nesting, features
  visibility, float subtypep, stream captures

The `.asd` override and dependency plumbing (old `.todo/201`) has LANDED:
`(ql:quickload "postmodern")` resolves and orders the whole graph and stops in
`postmodern/config.lisp` on `make-synonym-stream`, which is `.todo/200`'s
territory. See the postmodern section of `.kb/asdf.md` for what the replacement
`.asd` decided. Two pieces were split out of it and are NOT prerequisites of
this milestone:

- `.todo/204-mutex-primitive-and-postmodern-thread-safe.md` -- the build is
  `:postmodern-thread-safe` OFF, so the connection pool and the
  prepared-statement id counter are racy under concurrent handlers. Fine for
  the single-threaded milestone program; not fine for a `serve` handler.
- ~~`.todo/205-probe-file-and-uiop-file-exists-p.md`~~ -- DONE: `probe-file` is
  a real primitive on all four backends and `uiop:file-exists-p` lowers onto it
  (`.kb/read-load-streams.md`). `pomo:execute-file` still needs `.todo/196`'s
  `restart-case` and `alexandria:read-file-into-string`. The milestone program
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
