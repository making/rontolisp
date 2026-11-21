# k98. Java library: `java.net.Socket` and the client socket family

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Theme: JDBC for
PostgreSQL (`.todo/l01`); pgjdbc talks to the server through a `Socket`.

Not there today: `java.net.Socket`, `ServerSocket`, `SocketTimeoutException`,
`javax.net.SocketFactory`, `Socket.getInputStream` / `getOutputStream`. There are
`InetAddress`, `InetSocketAddress`, `SocketException`, `ConnectException`,
`UnknownHostException` (k03, k04).

- Over the program's own `rontolisp:tcp-*` (`.kb/tcp-sockets.md`): the interpreter, the JVM,
  `--native` and `--component` (`wasi:sockets`); a plain Preview 1 module refuses as the
  HTTP server does (an `IOException` in the JDK's words where the JDK has some).
- What pgjdbc calls decides the first cut: grep its sources (`org.postgresql:postgresql`
  sources jar) for `Socket`, `SocketFactory`, `setSoTimeout`, `setTcpNoDelay`,
  `setKeepAlive`, `connect(addr, timeout)`, `shutdownOutput`, buffered streams over the
  socket streams. Measure each answer and exception text under a JDK.
- Reads block a thread of the program's scheduler on wasm, not the instance
  (`.kb/java-frontend.md`, threads; `--component` reads suspend, `.kb/tcp-sockets.md`).
- `ServerSocket` too if the cost is small (a test server in the same program is the natural
  probe); otherwise its own item.
- Pin with java-spec cases against a server in the same program or the test's own socket.
