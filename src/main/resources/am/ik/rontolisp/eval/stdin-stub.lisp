;;;; stdin-stub.lisp -- the serve-mode stand-in for stdin.lisp.
;;;;
;;;; A served component has no stdin (the http-server bridge's fd_read is EOF by
;;;; construction) and the wasi:http service world does not import
;;;; wasi:cli/stdin at all -- so when sockets.lisp is spliced into a serve
;;;; program, the stdin fallthrough of its dispatchers binds THESE raw
;;;; passthroughs instead of the real stdin.lisp machinery. A nil stream
;;;; designator reaches the native built-in, whose bridge fd_read answers EOF.
;;;; async-defuns so the (rontolisp:await ...) call sites in sockets.lisp's
;;;; %*-future dispatchers stay well-typed (they settle immediately).

(rontolisp:async-defun rontolisp::%stdin-read-line-or-raw-f (s)
  (rontolisp::%read-line-raw s))

(rontolisp:async-defun rontolisp::%stdin-read-char-or-raw-f (s)
  (rontolisp::%read-char-raw s))

(rontolisp:async-defun rontolisp::%stdin-read-byte-or-raw-f (s)
  (rontolisp::%read-byte-raw s))
