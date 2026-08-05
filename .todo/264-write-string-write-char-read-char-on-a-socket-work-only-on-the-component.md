# `write-string` / `write-char` / `read-char` on a socket work only on the component

Difficulty: Medium

Split out of `.todo/263` (the `--component` socket write that lost its stream
handle, fixed). While pinning that fix the same three stream built-ins turned out
to be REAL on the WASM component and BROKEN on the interpreter and the JVM --
the opposite direction from todo-263, and pre-existing on unmodified `develop`.

`.kb/tcp-sockets.md` still opens by listing `write-string` among the built-ins
that "work on sockets unchanged"; that sentence is true of the component only.

## Repro

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener)))
       (server (rontolisp:tcp-accept listener)))
  (write-string "abc" client)
  (write-line "" client)
  (print (read-line server))
  (close client) (close server) (close listener))
```

```bash
java -cp target/classes am.ik.rontolisp.cli.RontoLispCli ws.lisp
# LispEvalException: not an output stream: 4

java -cp target/classes am.ik.rontolisp.cli.RontoLispCli ws.lisp -o Ws.class && java Ws
# ClassCastException: class java.net.Socket cannot be cast to class java.io.Writer
#   at Ws._writeStr / Ws._writeString

java -cp target/classes am.ik.rontolisp.cli.RontoLispCli ws.lisp -o ws.wasm --component
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y ws.wasm
# "abc"     <- the component answers
```

`(write-char #\Z sock)` fails the same way on both (it lowers to `write-string`
everywhere). `(read-char sock)` fails because the interpreter's `read-char`
resolves its designator to a `BufferedReader` and the socket table entry is a
raw `java.net.Socket`.

## Why it survived this long

Every socket example and test writes with `write-line` / `write-byte`, which DO
have the branches. cl-postgres never calls `write-string` on the socket either
(its `write-str` goes through `write-byte` per UTF-8 byte). The component grew
the wider surface for free: sockets.lisp routes all of these through the one
`%io-*` dispatch over `%sock-write-string`.

## Where the branches are missing

- **Interpreter** (`Environment`, the `registerIO` output-stream resolver behind
  `write-string`): it accepts a handle whose table entry is a `Writer`-ish stream
  and signals `not an output stream` for a `Socket`. `SocketSupport` already has
  the byte-level write used by `write-line`; the resolver needs the same
  `instanceof Socket` arm, and `read-char` needs a socket arm that decodes ONE
  UTF-8 sequence off the socket's input stream (the component's
  `%sock-read-char-f` is the reference semantics -- code points, not octets).
- **JVM** (`JvmIoRuntimeBuilder`): the `instanceof Socket` arms exist in
  `_writeLine` / `_readLineStream` / `_readByte` / `_writeByte` / `_closeStream`
  only. `_writeStr` (reached from `_writeString`) needs one, which means a new
  `_sockWriteString` in `JvmSocketRuntimeBuilder` next to `_sockWriteLine`
  (same shape, no trailing newline), gated on the existing `usesSockets` so
  non-socket programs keep byte-identical stream runtime bodies. `_readChar`
  needs the decoding arm.

## Acceptance

- The repro above prints `"abc"` on all four backends (Preview 1 keeps its
  call-time tcp error, as documented).
- `read-char` on a socket answers the same code points on the interpreter, the
  JVM and the component -- cross-check against
  `componentTcpBinaryBytesAreWireTransparent`, whose `read-char` half is
  component-only today.
- `LispEvaluatorTest#tcp*` / `JvmLispCompilerTest#compileAndRunTcp*` gain the
  `write-string` / `write-char` / `read-char` legs.
- `.kb/tcp-sockets.md`: the opening list becomes true again and the
  "work only on the component" bullet goes away (or records whatever remains).
