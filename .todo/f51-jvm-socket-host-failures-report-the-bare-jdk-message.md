# f51. JVM socket host failures report the bare JDK message

Difficulty: Low

A socket operator whose host work fails (not its arguments, which agree since the argument
refusals were unified) reports differently (measured 2026-10-11, `java -jar` vs `-o X.class`):

| Call | Interpreter | JVM |
|---|---|---|
| `tcp-listen` on a busy port | `tcp-listen: cannot listen on 127.0.0.1:P: Address already in use` | `Address already in use` |
| `tcp-connect` / `tls-connect` refused | `tcp-connect: cannot connect to 127.0.0.1:P: Connection refused` | `Connection refused` |
| `tcp-connect` to an unknown host | `tcp-connect: cannot connect to no-such-host.invalid:80: no-such-host.invalid` | `no-such-host.invalid` |
| `tls-listen` of a missing keystore | `tls-listen: cannot listen on *:0: /nonexistent.p12 (No such file or directory)` | `/nonexistent.p12 (No such file or directory)` |

Both are `simple-error`s; only the text differs. The interpreter's prefix is
`SocketSupport`'s wrapping.

## Plan

1. List every `SocketSupport` wrapping (connect, listen, accept, TLS handshake, upgrade).
2. Give the JVM socket helpers (`JvmSocketRuntimeBuilder`) the same prefix: an exception
   handler around the host call that rethrows `new RuntimeException(prefix + e.getMessage())`.
3. Pin one refused connect and one busy listen on both backends (`HandleOperandFixture`'s
   suites).
