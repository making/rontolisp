# f46. JVM thread and socket primitives report host exceptions over a non-handle

Difficulty: Medium

Over an argument that is no handle, the interpreter signals the operator's own error and the JVM
leaks the host exception (measured 2026-10-11, `java -jar` vs `-o X.class`):

| Call | Interpreter | JVM |
|---|---|---|
| `(rontolisp:thread-alive-p 1)`, `destroy-thread`, `join-thread` | `THREAD-ALIVE-P expects a thread handle, got 1` | `class java.lang.Long cannot be cast to class [Ljava.lang.Object;` |
| `(rontolisp:tcp-local-port 1)` in a program with no file stream | `TCP-LOCAL-PORT expects a socket or listener handle` | `Cannot load from object array because "P._streams" is null` |

A `#'` value of each reports the same (its body is the call). The WASM component answers nil for
the socket ones (`.kb/tcp-sockets.md`: errors are nil there).

## Plan

1. Measure every `NativeCallShapes` `rontolisp:` thread and `tcp-*`/`tls-*` row over `1`, `nil`
   and a string on the interpreter and the JVM.
2. Give the JVM helpers (`JvmThreadRuntimeBuilder`, `JvmSocketRuntimeBuilder`) the interpreter's
   check and text, as a catchable error; decide whether it is a `type-error` on both.
3. Pin with a fixture on the interpreter and the JVM.
