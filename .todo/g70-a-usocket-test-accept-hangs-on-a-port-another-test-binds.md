# g70: a usocket test's accept hangs when another test binds the same port on loopback

Difficulty: Medium

`JvmLispCompilerTest.compileAndRunUsocketAddressAccessors` listens on the wildcard host
(`usocket:*wildcard-host*`, port 0), connects to `127.0.0.1:P` and then calls
`socket-accept`. On macOS a socket bound to `127.0.0.1:P` can coexist with one listening
on `*:P`, and the more specific one receives loopback connections -- so when a parallel
test binds `127.0.0.1:P` in between, the client lands there and `socket-accept` waits
forever. A local full `./mvnw test` on 2026-10-23 sat in `Net.accept` for 22 minutes; the
JUnit timeout did not end it.

The same trap: g58 met it for `*:P` versus `127.0.0.1:P` in `HOST_FAILURE_PROGRAM`
(`.kb/tcp-sockets.md`).

## Goal

- Sweep the socket tests on every backend (interpreter, JVM, both WASM; usocket and the
  rontolisp tcp/tls operators; ci-spec cases) for a wildcard listener reached through
  `127.0.0.1`, and listen on the address the client connects to (or connect to the
  address listened on), so a parallel test cannot take the connection.
- No socket test can hang the suite: every accept/read in a test is bounded (a socket
  timeout in the program, or a hang guard that actually interrupts a blocked accept --
  check why the JUnit timeout did not).
- Record the rule in `.kb/tcp-sockets.md`.
