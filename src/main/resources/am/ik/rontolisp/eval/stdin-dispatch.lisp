;;;; stdin-dispatch.lisp -- the %io-*/%*-future dispatch defuns for a --component
;;;; program that reads stdin WITHOUT touching sockets. When sockets.lisp is
;;;; spliced, ITS dispatchers own these names (falling through to the same
;;;; %stdin-*-or-raw-f helpers), and this file is not spliced.
;;;;
;;;; A top-level rontolisp::%io-read-line defun is the gate the WASM compiler's
;;;; socket/stdin I/O rewrite keys on: sync-context reads are redirected onto the
;;;; %io-* defuns (which force the async internals through the blocking scheduler
;;;; drive), async-context reads are promoted onto (rontolisp:await
;;;; (%...-future ...)) so a pending stdin read suspends the task. The write and
;;;; close entries are raw passthroughs here -- no socket can exist -- but they
;;;; must be defined because the rewrite redirects those built-ins whenever it
;;;; runs at all.

(rontolisp:async-defun rontolisp::%read-line-future (&optional s)
  (rontolisp:await (rontolisp::%stdin-read-line-or-raw-f s)))

(rontolisp:async-defun rontolisp::%read-char-future (&optional s)
  (rontolisp:await (rontolisp::%stdin-read-char-or-raw-f s)))

(rontolisp:async-defun rontolisp::%read-byte-future (&optional s)
  (rontolisp:await (rontolisp::%stdin-read-byte-or-raw-f s)))

(defun rontolisp::%io-read-line (&optional s)
  (if (null s)
      (rontolisp::%future-force (rontolisp::%read-line-future s))
      (rontolisp::%read-line-raw s)))

(defun rontolisp::%io-read-char (&optional s)
  (if (null s)
      (rontolisp::%future-force (rontolisp::%read-char-future s))
      (rontolisp::%read-char-raw s)))

(defun rontolisp::%io-read-byte (&optional s)
  (if (null s)
      (rontolisp::%future-force (rontolisp::%read-byte-future s))
      (rontolisp::%read-byte-raw s)))

(defun rontolisp::%io-write-line (s &optional stream)
  (rontolisp::%write-line-raw s stream))

(defun rontolisp::%io-write-string (s &optional stream)
  (rontolisp::%write-string-raw s stream))

(defun rontolisp::%io-write-byte (b stream)
  (rontolisp::%write-byte-raw b stream))

(defun rontolisp::%io-close (stream)
  (rontolisp::%close-raw stream))
