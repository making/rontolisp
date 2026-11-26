# m49. TLS on a reactor's host sockets (`--host-sockets`)

Difficulty: Medium

Left by m44 (2026-11-25). A `--no-wasi` module's sockets are its host's, `connect()` of
`cloudflare:sockets` (`.kb/tcp-sockets.md`, "A reactor's sockets"), in the clear:
`rontolisp:tls-connect` and `rontolisp:tls-upgrade` keep the call-time error there, and
`jdbc-postgresql/` refuses `sslmode=require` on every target (`.todo/l15`). So a Worker
reaches a server that requires TLS of it only through Hyperdrive.

- The host has both shapes: `secureTransport: "on"` for `tls-connect`, and `"starttls"`
  with `startTls({ expectedServerHostname })` for `tls-upgrade` (a PostgreSQL
  `SSLRequest`). `startTls()` answers a NEW socket and the old one is unusable: the
  read-ahead in flight on it (`pump`) has to be given up first (`reader.releaseLock()`),
  and a socket to be upgraded later must have been made `"starttls"` -- measure whether
  every connect can be, or the module has to say so at its connect.
- The module side: `tls-upgrade` swaps the entry in place on the component and answers a
  new stream on the interpreter and the JVM; decide which a reactor does. `:insecure`
  has no knob in `connect()`: refuse it by name, as the component does.
- The test host: `TcpLinePeer.NODE_CONNECT` over `node:tls`, against `TlsTestSupport`'s
  peer; then Java's `javax.net.ssl` over it once `.todo/l15` has the classes.
- Measure under `wrangler dev` first: what `startTls()` does with a certificate the
  platform does not trust, and its error's words.
