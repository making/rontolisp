# db

Talking to a database from rontolisp, using the real upstream driver rather than
a rontolisp-specific binding.

| Program | Library | Upstream |
| --- | --- | --- |
| [`postgres-hello.lisp`](postgres-hello.lisp) | cl-postgres 1.33.11 (zlib), Postmodern's low-level PostgreSQL driver | <https://github.com/marijnh/Postmodern> |
| [`postgres-crud.lisp`](postgres-crud.lisp) | the same driver: the full CRUD cycle | <https://github.com/marijnh/Postmodern> |
| [`postgres-web.lisp`](postgres-web.lisp) | the same driver behind a web app, with cl-who 1.1.5 (BSD) for the HTML | <https://github.com/edicl/cl-who> |

Start a server first:

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
rontolisp examples/db/postgres-hello.lisp
rontolisp examples/db/postgres-crud.lisp
rontolisp examples/db/postgres-web.lisp   # then open http://127.0.0.1:8080
```

`postgres-crud.lisp` walks create / insert / select / update / delete and adds
the two things the hello program leaves out: a parameterised statement through
the extended query protocol (`prepare-query` + `exec-prepared`) and a second row
reader shape (`alist-row-reader`, which labels each column with its name). It
runs inside one transaction that it rolls back at the end, so it leaves the
server exactly as it found it and can be re-run as often as you like.

`postgres-web.lisp` is the third one: the notes it lists live in PostgreSQL,
[cl-who](https://github.com/edicl/cl-who) renders the page and
`rontolisp:http-handler` serves it. `GET /` lists the notes and shows a form;
`POST /add` inserts one through the prepared statement and redirects back. Its
handler is an `rontolisp:async-defun` because the
request `:body` is a stream — `rontolisp:read-all` drains the url-encoded form
and `rontolisp:query-param` picks the field out of it, the same function a
query string goes through. It takes **a connection per request** and hands it
back in an `unwind-protect`: a connection is one conversation with the server,
and the interpreter/JVM servers put every request on its own thread.

That is the right shape, and today only the interpreter delivers on it under
load. Twelve concurrent POSTs: the interpreter and a `wasmtime serve` component
insert all twelve, wasmCloud inserts ten (two `wasm trap: cast failure`), and
the **JVM class inserts one** -- concurrent requests there corrupt each other's
dynamic bindings, which is what cl-postgres' `initiate-connection` needs to
survive a connect. Sequentially all four are 12/12. Driving the demo from a
browser tab never touches this; a load test does.

Non-ASCII notes work: cl-who escapes them as numeric
character references (`&#x304a;`) the way it escapes everything above ASCII by
default, so the page source looks escaped and the browser shows 日本語 -- bind
`cl-who:*escape-char-p*` to a predicate that only matches `<>&'"` if you would
rather see raw UTF-8.

All three run on the **interpreter and the JVM backend**:

```bash
rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
```

`ql:quickload` pulls md5, split-sequence, ironclad, cl-base64, cl-ppcre, uax-15
and alexandria (and, for the web app, cl-who); the first run also downloads them
into `~/.rontolisp/quicklisp`.
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

`postgres-crud.lisp` builds and runs the same way -- swap the two filenames.

Both components complete their round-trips and print the same results as the
interpreter and the JVM. One limitation remains: a connection which
negotiates SSL can never work here -- TLS is interpreter/JVM only. Use plain
TCP (the default `:no`) on WASM.

`postgres-web.lisp` serves and queries from inside one component, under
`wasmtime serve` rather than `wasmtime run`, and it needs **one more flag**:

```bash
rontolisp examples/db/postgres-web.lisp -o app.wasm --component --optimize
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y app.wasm
```

`-S cli=y` is the one to remember. Without it the linker refuses the module
with `instance export 'tcp-socket' has the wrong type: resource implementation
is missing`, which reads like the host has no sockets at all -- it is a flag,
not a missing host feature (`wasmtime run` needs no equivalent).

**wasmCloud** runs the same component: drop it in a `wash dev` project with
`wasm_proposals: [gc, exception-handling, component-model-async]` and it serves
and queries just the same. (Its "Host provides interfaces" startup log names
only 0.2 interfaces; that list is stale, `wasi:sockets` 0.3 is there.)

Two host differences decide where the database has to live and how the program
may be written:

- **Address.** The component reaches the server over the network the host gives
  it. Under wasmtime-in-Docker that is a container IP; under `wash` it must be a
  **non-loopback** address, because wash routes loopback to a per-workload
  virtual network that never reaches the machine's own `127.0.0.1`.
- **Instance lifetime.** The program's top level runs once per *instance*, and
  wasmtime serve keeps one instance for the whole run while wasmCloud gives each
  request a fresh one. That is why the table is created with
  `create table if not exists` rather than dropped and recreated: a `drop` in
  the top level empties the table on every request under wasmCloud.
