# g58: a tls-connect to a reused port hangs the test suite

Difficulty: Medium

`JvmThreadTest.socketHostFailuresReadAsTheInterpretersDo` (program
`HandleOperandFixture.HOST_FAILURE_PROGRAM`) closes a listener and then expects
`tcp-connect`/`tls-connect` to that port to fail with `Connection refused`. Under the
parallel suite another test bound the same ephemeral port in between, the connect
succeeded, and `tls-connect` blocked forever in `SSLSocketImpl.startHandshake`
(`SocketDispatcher.read0`) -- a local full `./mvnw test` on 2026-10-23 sat there for
8 hours; JUnit's timeout did not end it.

Two defects:

- The fixture assumes a closed ephemeral port stays free. Use a port that cannot be
  reused (e.g. keep a bound-but-not-listening socket, or a guaranteed-refusing address)
  so the refusal is deterministic. The interpreter's twin of this check (`ThreadTest`)
  and any WASM twin likely share the assumption.
- `tls-connect` has no handshake timeout, so a peer that accepts and never speaks TLS
  hangs the caller. Decide a bounded default (or honour a timeout option) on every
  backend that has `tls-connect`, with its behavior documented.

## Goal

The test cannot hang or flake on a reused port, and a silent peer cannot hang
`tls-connect` indefinitely.
