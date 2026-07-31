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

;;; The stream DESIGNATOR is resolved HERE, before the nil test -- see the same
;;; comment in sockets.lisp.
(defun rontolisp::%io-read-line (&optional s)
  (let ((in (or s *standard-input*)))
    (if (integerp in)
        (rontolisp::%read-line-raw in)
        (rontolisp::%future-force (rontolisp::%read-line-future in)))))

(defun rontolisp::%io-read-char (&optional s)
  (let ((in (or s *standard-input*)))
    (if (integerp in)
        (rontolisp::%read-char-raw in)
        (rontolisp::%future-force (rontolisp::%read-char-future in)))))

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

;;; open-stream-p is redirected by the same rewrite, so it needs a definition
;;; here too or the call compiles to a call-time error and TRAPS -- which is what
;;; (open-stream-p *error-output*) used to do in a socket-free component. No
;;; socket can exist in this splice, so every non-nil designator answers t: the
;;; exact answer sockets.lisp's dispatcher gives a file/stdin handle.
(defun rontolisp::%io-open-stream-p (s)
  (if s t nil))

;;; The sequence ops and the eof-tolerant read-byte the rewrite also redirects:
;;; raw passthroughs here (no socket can exist). The %...-future twins are plain
;;; defuns -- rontolisp:await passes a settled plain value through -- so a
;;; top-level (read-byte f nil -1) keeps the native built-in's exact semantics.

(defun rontolisp::%io-read-byte-eof (s eof-error-p &optional eof-value)
  (rontolisp::%read-byte-raw s eof-error-p eof-value))

(defun rontolisp::%read-byte-eof-future (s eof-error-p &optional eof-value)
  (rontolisp::%read-byte-raw s eof-error-p eof-value))

(defun rontolisp::%io-read-sequence (seq s)
  (rontolisp::%read-sequence-raw seq s))

(defun rontolisp::%read-sequence-future (seq s)
  (rontolisp::%read-sequence-raw seq s))

(defun rontolisp::%io-write-sequence (seq s)
  (rontolisp::%write-sequence-raw seq s))
