# 552. A stream IS an integer, so a binding cannot tell one from a file descriptor

Difficulty: High

A rontolisp stream is an opaque INTEGER handle, and `streamp` answers t for any integer.
A portable library that dispatches on "a Lisp stream vs. an OS file descriptor" therefore
picks the wrong arm, and there is no value it could be handed that would pick the right
one. Upstream cl+ssl is the measured case (`.kb/cffi.md`):

```lisp
(etypecase socket
  (integer (install-nonblock-flag socket) (ssl-set-fd handle socket))   ; socket BIO
  (stream  (ssl-set-bio handle (bio-new-lisp) (bio-new-lisp))))         ; Lisp BIO
```

Handle 3 is taken for file descriptor 3 and OpenSSL is told to read the wrong thing --
`SSL_get_error: 5` with an empty error queue. `:unwrap-stream-p nil` does not help: the
etypecase dispatches on the VALUE, not on the flag. With the arm forced to the Lisp BIO
in a scratch copy, the handshake completes, so this is the only thing between the real
cl+ssl and a working TLS client here.

The obvious way out -- hand it a Gray stream wrapping the socket -- does not work either:

```lisp
(typep <a rontolisp:fundamental-stream instance> 'stream)   ; => NIL here, T in CL
```

A Gray stream IS a stream in Common Lisp. Making `streamp` / `(typep x 'stream)` answer
t for a `rontolisp:fundamental-stream` instance is a smaller, general conformance fix
that would at least give such a library a value it can route correctly, and it is worth
doing on its own -- but it needs the class hierarchy at the test, which the compiled
backends answer through per-class dispatch rather than a registry, so it is a
four-backend change with a `.kb/gray-streams.md` rule and a pinning test.

The larger half -- whether a stream should stop being an integer -- is the same question
`.todo/156` asks about symbols, and should not be answered inside a binding probe.

## Order to consider it in

1. `streamp` / `(typep x 'stream)` for a Gray instance, all four backends. General, and
   it unblocks the "wrap it" route for any library shaped like cl+ssl.
2. Only then, whether the handle representation itself needs to change.
