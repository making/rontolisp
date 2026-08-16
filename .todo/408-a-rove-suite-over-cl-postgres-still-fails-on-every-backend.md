# 408. A rove suite over cl-postgres still fails, differently on each backend

Difficulty: Low (this file is a status record and a reduction vehicle; the fixes
live in `.todo/393`, `.todo/394` and `.todo/407`)

`cl-postgres-client` (`~/git/cl-postgres-client`, MIT, cl-postgres its only
runtime dependency) is a JdbcClient-style layer over cl-postgres: named
parameters, row mapping, transactions with savepoints, streaming, COPY and
LISTEN/NOTIFY, with a 186-assertion rove suite against a dockerised
PostgreSQL 17. It is a good reduction vehicle -- small, no MOP, no threads, and
its suite exercises exactly the corners a real library's error handling lives in.

**The library itself works on every backend.** Connect, the fluent builder, all
row formats, transactions, savepoints, COPY and LISTEN/NOTIFY answer correctly
when driven from a plain program. What fails is the suite, and every failure is
in the interaction between rove's recorder and rontolisp's condition / non-local
exit machinery.

## Running it

```bash
cd ~/git/cl-postgres-client
make rontolisp-test        # interpreter
make rontolisp-test-jvm    # -o Suite.class, then java Suite
make rontolisp-test-wasm   # --component, then wasmtime
```

Each target starts the container itself (`docker compose up -d --wait`) and
fills rontolisp's Quicklisp cache. SBCL is the reference: `make test`, all 186
assertions green.

## Where it stands (2026-08-16, rontolisp 0.1.565)

| backend | passed | failed |
| --- | --- | --- |
| SBCL (reference) | 186 | 0 |
| rontolisp interpreter | 172 | 14 |
| rontolisp JVM | 178 | 7 |
| rontolisp WASI 0.3 component | 183 | 2 |

The component is the best backend here, and by a distance -- but only up to
`7f496c5b`. From `55af7714` on it traps with `cast failure` 166 assertions in and
never reaches the end: `.todo/409`, bisected to that one commit. The numbers
above are the component's at `7f496c5b`; the interpreter's and the JVM's are
unchanged at HEAD.

### Failing on all three -- `.todo/393`

`stale-prepared-statements`, two assertions. The library recovers from SQLSTATE
26000 (the server has deallocated a statement the cache still remembers) with

```lisp
(handler-case (run (prepared-statement-name client sql))
  (cl-postgres:database-error (condition) ... (run (prepared-statement-name client sql))))
```

which is a library catching its own error and continuing -- the exact shape 393
describes. rove's outer `handler-bind` runs anyway and transfers control, so the
retry never completes and the assertion after it fails. Nothing on the library
side can avoid this; it is not a test that catches an error, it is production
code that does.

### Failing on the interpreter -- `.todo/407`

Every remaining interpreter failure is the `return`-out-of-a-`handler-bind`-
handler bug: `(ok (signals ... 'some-error))` where the code under test raises
from inside a `loop`/`dolist`, plus `do-rows`' documented early exit
(`(do-rows (row ...) (when ... (return row)))`), which is a USER-VISIBLE break
of the library's API and not only of its tests.

### Failing on the JVM -- unexplained, related to `.todo/207`

Four assertions that pass on both the interpreter and the component:

- `(signals (query-value client "select id, name ...") 'too-many-columns-error)`
  -- raised inside the cl-postgres `row-reader` body;
- `(signals (with-transaction (client :read-only t) (insert ...)) 'database-error)`;
- `(signals (with-transaction (client) (with-transaction (client :isolation :serializable) nil))
  'transaction-error)` -- raised by the library itself, no socket involved;
- `(block done (with-transaction (client) ... (return-from done)))`, reported as
  "Raise an error while testing."

All four cross the library's `call-with-transaction`, whose shape is
`unwind-protect` + `multiple-value-prog1` + `restart-case` -- the same knot
`.todo/207` fails in, and the same "works standalone, fails inside the library"
signature. **Ruled out here too**: a hand-written copy of `call-with-transaction`
in one file, and the same copy loaded through its own `.asd` as a spliced
system, both compile and answer correctly on the JVM. So neither the shape nor
the splice alone is the trigger, exactly as 207 records.

The fifth JVM failure is `do-rows` + `return`, which the component passes -- so
whatever 407 is on the interpreter has a JVM-side twin in this path.

## Two things this cost that are worth writing down

- **`(ql:quickload "cl-postgres")` inside a program works; a `:depends-on
  ("cl-postgres")` in a `.asd` does not.** `asdf:load-system` never falls back
  to the Quicklisp index, so the dependency has to be pre-fetched and every
  release directory named on `--system-path`. `rontolisp test SYSTEM` has no
  `--eval` to quickload from, so the Makefile ends up passing all ~140 cached
  release directories. An `asdf:load-system` that quickloads an unresolvable
  dependency (or a `--quicklisp` opt-in flag) would remove the whole dance.
- **A `.asd`'s `:perform` runs in a thinner environment than a program.**
  `(uiop:symbol-call "ROVE" "RUN" ...)` answers "The function UIOP:SYMBOL-CALL is
  undefined" there while the identical call in a `.lisp` file works on every
  backend, and `(funcall (symbol-function (find-symbol "RUN-TESTS" "..."))))`
  resolves on the interpreter but not in a compiled artifact. `(funcall
  (find-symbol "RUN" "ROVE") ...)` is what works everywhere, and that is a thin
  needle for a `.asd` to have to thread.
