# A `--component` socket write dies with `unknown handle index 0` mid-message

Difficulty: High

The four `--component` legs of `ClPostgresE2eTest` fail; the interpreter and JVM
legs of the same exercises pass. Split out of `.todo/262` (the packed
`concatenate` result type), which was the cause of the OTHER seven failures and
is fixed -- this one is independent and **pre-existing on unmodified `develop`**
(verified by stashing the todo-262 work, rebuilding, and reproducing the
identical backtrace).

Because of it, `ClPostgresE2eTest.{authLadder,scramAuth,crud,unicodeText}
OnWasmComponent` are `@Disabled` with a pointer here; the rest of the class runs
by default.

## Symptom

```
Caused by:
  0: error while executing at wasm backtrace:
     0:  0xd80 - <unknown>!fd_write
     ...
     10: 0x2fa1 - <unknown>!<wasm function 34>
  1: unknown handle index 0
```

`unknown handle index 0` is wasmtime rejecting a component resource handle the
guest passed: the stream handle the write went through is 0, i.e. not in the
instance's resource table. The `scram` leg traps in `stream_read`/`fd_read`
instead of `fd_write`, same root error.

## Repro (no Testcontainers)

```bash
docker run -d --name rlpg -e POSTGRES_HOST_AUTH_METHOD=trust \
  -e POSTGRES_PASSWORD=postgres -p 15432:5432 postgres:17-alpine
docker exec rlpg psql -U postgres -c \
  "create user trustuser; grant create on schema public to trustuser;"
```

```lisp
(ql:quickload "cl-postgres")
(print :a-before-connect)
(let ((conn (cl-postgres:open-database "postgres" "trustuser" nil "127.0.0.1" 15432)))
  (print :b-connected)
  (print (cl-postgres:exec-query conn "select 42" 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
```

```bash
java -cp target/classes am.ik.rontolisp.cli.RontoLispCli p.lisp -o p.wasm --component
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y p.wasm   # traps
java -cp target/classes am.ik.rontolisp.cli.RontoLispCli p.lisp             # fine
```

wasmtime 47.0.2, host and `ghcr.io/making/rontolisp-wasmtime:latest` alike.

## What is already ruled out

- **Not module size.** A build that compiles the same `open-database` /
  `exec-query` call graph in but never RUNS it (`(defun never () ...)`) is 8.1 MB
  and prints normally; the failing program is ~8.0 MB.
- **Not `open-database`.** Connect + full auth handshake (which writes AND reads)
  + `close-database` completes and prints on the component. The trap needs
  `exec-query`.
- **Not the plain socket path.** A hand-written component program that connects,
  writes a PostgreSQL startup packet byte-at-a-time, reads the reply bytes back
  and closes produces output identical to the interpreter -- with and without EH
  mode (`handler-case` around it).
- **Not stdout.** The first `print` APPEARS to be where it dies, but component
  stdout is buffered: `v3` above proves prints before the failing call do come
  out. Do not chase the `fd_write` frame as a printing bug.

## The lead

The server logs `incomplete message from client` for the failing run
(`log_statement=all`, `docker logs`). So the query message is PARTIALLY written
and the write then fails with an invalid stream handle -- the socket's output
stream resource is being dropped (or never re-acquired) partway through a
multi-part write, and the cached handle reads back as 0.

Start at the component socket write path and the "one host CHUNK per socket"
caching divergence documented in `.kb/tcp-sockets.md`: find who releases the
`wasi:io/streams` output-stream resource and whether a re-acquire is missing
after the first chunk boundary. A long single-shot write over a component socket
was tried as an isolation and produced a DIFFERENT error (`cannot write after
being notified that the readable end dropped`, i.e. the server hung up on the
garbage), so the boundary is not simply "N bytes".

## Acceptance

- The repro above prints `((42))` on the component, byte-identical to the
  interpreter and the JVM.
- The four `@Disabled` `ClPostgresE2eTest` component legs are re-enabled and
  green.
- `.kb/tcp-sockets.md` gains the mechanism (and the reason for any remaining
  divergence).
