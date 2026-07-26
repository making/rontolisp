# db

Talking to a database from rontolisp, using the real upstream driver rather than
a rontolisp-specific binding.

| Program | Library | Upstream |
| --- | --- | --- |
| [`postgres-hello.lisp`](postgres-hello.lisp) | cl-postgres 1.33.11 (zlib), Postmodern's low-level PostgreSQL driver | <https://github.com/marijnh/Postmodern> |

Start a server first:

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
rontolisp examples/db/postgres-hello.lisp
```

Runs on the **interpreter and the JVM backend**:

```bash
rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
```

`ql:quickload` pulls md5, split-sequence, ironclad, cl-base64, cl-ppcre, uax-15
and alexandria; the first run also downloads them into `~/.rontolisp/quicklisp`.
Compiling takes a couple of seconds, and every backend -- the interpreter
included -- runs it in well under a second. `trust`, `password` and
`md5` authentication all complete on both. SCRAM-SHA-256 completes too, but on the INTERPRETER only when
the server allows enough time: its 4096-round PBKDF2 outruns the default
60-second `authentication_timeout` in interpreted Lisp, so start the server with
`-c authentication_timeout=600` for it. The compiled backend has no such
problem.

## WASM

The driver needs TCP, and TCP on WASM is `--component` only (WASI 0.3 sockets;
Preview 1 has no host socket API, so `rontolisp ... -o prog.wasm` rejects the
program at compile time). The component build and its run flags are:

```bash
rontolisp examples/db/postgres-hello.lisp -o postgres-hello.wasm --component --optimize
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y postgres-hello.wasm
```

The three `-S`/`-W` groups are all required: `gc` for the value
representation, `exceptions` because the driver uses `handler-case`, and
`tcp` + `inherit-network` because wasmtime gates sockets by permission
(without them the socket calls return errors, which surface as `nil`).
`--optimize` is the habit worth keeping for WASM, though on THIS program it
changes nothing (the two builds are byte-identical): the library pruner runs
by default and the driver uses nearly everything it loads.

The component completes the full query round-trip and prints the same results
as the interpreter and the JVM. One limitation remains: a connection which
negotiates SSL can never work here -- TLS is interpreter/JVM only. Use plain
TCP (the default `:no`) on WASM.
