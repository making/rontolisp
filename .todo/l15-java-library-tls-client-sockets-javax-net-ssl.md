# l15. Java library: TLS client sockets (`javax.net.ssl`) over `tls-upgrade`

Difficulty: High

**Not urgent (the user, 2026-11-22): it waits until a web application can use it.**
`wasmtime serve` does not host `wasi:tls` on 51.0.0 either (`.kb/tcp-sockets.md`, "WASM
TLS"), so a served program -- the Servlet and PostgreSQL application of `.todo/l11` under
`wasmtime serve` -- has no TLS socket; re-measured on each wasmtime bump
(`.kb/running-backends.md`, "The wasmtime pin"). A host owning the connection is the other
route, measured on Spin (`.kb/jdbc-postgresql.md`, "Through the host's connection").

**Read `.kb/java-frontend-provenance.md` before writing anything.** Theme: JDBC for
PostgreSQL (`.kb/jdbc-postgresql.md`; `jdbc-postgresql/` refuses `sslmode=require` until this is made: `.todo/l23`). pgjdbc's `sslmode` (`prefer` by default) sends an `SSLRequest` on
its plain socket and, when the server answers `S`, turns the socket into a TLS one
(`org.postgresql.ssl.MakeSSL.convert`): `SSLSocketFactory.createSocket(socket, host, port,
true)`, `SSLSocket.getSSLParameters` / `setSSLParameters` with
`setEndpointIdentificationAlgorithm`, `setUseClientMode`, `startHandshake`, `setSoTimeout`,
`getSession` for a `HostnameVerifier`; its `LibPQFactory` builds an `SSLContext` from key and
trust managers and certificate files. Even where the server answers `N`, the lowering reaches
those calls, so their classes have to exist for pgjdbc to compile.

- The program's own TLS client is `rontolisp:tls-upgrade` over a connected socket
  (interpreter, JVM, component; a call-time refusal on `--native`, `.kb/tcp-sockets.md`,
  "TLS"): what `javax.net.ssl` can be over it -- `SSLSocketFactory.getDefault()`, the
  layered `createSocket`, `SSLSocket` as a `Socket` whose streams are the upgraded handle's,
  the handshake's failure as `SSLHandshakeException` -- and what is refused by name
  (managers, contexts of the program's own, client certificates: `.kb/tcp-sockets.md`'s
  cl+ssl shim has the same edges).
- Grep pgjdbc's sources jar (`org.postgresql:postgresql`) for every `javax.net.ssl` member it
  calls, and measure each answer and refusal under a JDK. What a login reaches without
  running it (l01's census, 2026-11-22, `.kb/java-frontend.md`, "pgjdbc measured"):
  `SSLSocketFactory.createSocket(Socket, String, int, boolean)`, `SSLSocket.getSession`,
  `getSSLParameters`, `setSSLParameters`, `setUseClientMode`, `startHandshake`,
  `SSLParameters.setApplicationProtocols`, `SSLSession.getPeerCertificates`,
  `HostnameVerifier.verify`, `Certificate.getEncoded`, `X509Certificate.getSigAlgName`,
  `getSubjectAlternativeNames`, `getSubjectX500Principal`, `X500Principal.getName(String)`,
  `LdapName` and `Rdn`.
- `java.net.Socket` (k98) keeps a read buffer of 8 KiB in its stream: a TLS upgrade after a
  read must hand what the buffer holds to the upgraded socket, or take none (pgjdbc reads
  one byte, `S`, then upgrades).

On a `--no-wasi` module (`--host-sockets`, m44) the transport under it is `.todo/m49`.
