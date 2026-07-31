;;;; stdin.lisp -- component stdin reads over wit-imported wasi:cli/stdin@0.3.0.
;;;;
;;;; This is the --component stdin machinery (spliced by eval/StdinLibrary): alone
;;;; with the stdin-dispatch.lisp dispatchers when an async program reads stdin,
;;;; or alongside sockets.lisp, whose %io-*/%*-future dispatchers fall through to
;;;; the %stdin-*-or-raw-f helpers below. The interpreter and the JVM keep their
;;;; native stdin; Preview 1 keeps the blocking fd_read; a NON-async component
;;;; program keeps the preview1 adapter's stdin branch untouched (it gains nothing
;;;; from the migration, and its bytes stay stable).
;;;;
;;;; Mechanics -- the preview1 adapter's stdin cache, in Lisp: ONE readable stream
;;;; from read-via-stream, cached in a defvar (the returned result future is
;;;; dropped immediately; EOF is signalled by the stream status, not the future),
;;;; plus a chunk buffer + cursor + eof mark. Reads buffer one host chunk at a
;;;; time, like sockets.lisp (the same documented divergence from the
;;;; byte-at-a-time interpreter/JVM reads). The %stdin-*-f internals are
;;;; async-defuns so an async body's promoted read suspends the task instead of
;;;; blocking the instance; the sync surface forces them at the dispatch level.

;; The WIT interface is lowered by StdinLibrary (which calls
;; WitImportDirective.lower itself), so this directive never reaches
;; WitImportInliner -- but it is written here, in stdin.lisp's own source, so the
;; file reads as the program it is. The component binds the interface against the
;; fixed import block's own stdin instance (WasmComponentBuilder lowers it FROM
;; the block; a second import of the same interface would be invalid).
(rontolisp:wit-import "stdin.wit" :interface "wasi:cli/stdin@0.3.0" :package %stdin)

;;; --- the cached stdin stream + chunk buffer (the adapter's cache, in Lisp) ---

(defvar rontolisp::*stdin-stream* nil)
(defvar rontolisp::*stdin-buf* nil)
(defvar rontolisp::*stdin-cursor* 0)
(defvar rontolisp::*stdin-eof* nil)

(defun rontolisp::%stdin-ensure ()
  (if rontolisp::*stdin-stream*
      rontolisp::*stdin-stream*
      (let ((pair (%stdin:read-via-stream)))
        (%stdin:stdin-future-drop-readable (car (cdr pair)))
        (setq rontolisp::*stdin-stream* (car pair))
        rontolisp::*stdin-stream*)))

(rontolisp:async-defun rontolisp::%stdin-fill ()
  ;; Refill the chunk buffer from the stdin stream; nil (and the eof mark) at EOF.
  (let ((chunk (rontolisp:await (%stdin:stdin-stream-read (rontolisp::%stdin-ensure)))))
    (if (and chunk (> (length chunk) 0))
        (progn (setq rontolisp::*stdin-buf* chunk)
               (setq rontolisp::*stdin-cursor* 0)
               t)
        (progn (setq rontolisp::*stdin-eof* t) nil))))

(defun rontolisp::%stdin-buf-ready ()
  (if rontolisp::*stdin-buf*
      (< rontolisp::*stdin-cursor* (length rontolisp::*stdin-buf*))
      nil))

(defun rontolisp::%stdin-pop-char ()
  (let ((c (char rontolisp::*stdin-buf* rontolisp::*stdin-cursor*)))
    (setq rontolisp::*stdin-cursor* (+ rontolisp::*stdin-cursor* 1))
    c))

(rontolisp:async-defun rontolisp::%stdin-read-char-f ()
  (if (rontolisp::%stdin-buf-ready)
      (rontolisp::%stdin-pop-char)
      (if rontolisp::*stdin-eof*
          nil
          (if (rontolisp:await (rontolisp::%stdin-fill))
              (rontolisp::%stdin-pop-char)
              nil))))

(rontolisp:async-defun rontolisp::%stdin-read-line-f ()
  (let ((acc "") (got nil) (done nil))
    (while (not done)
      (let ((c (rontolisp:await (rontolisp::%stdin-read-char-f))))
        (if (null c)
            (setq done t)
            (progn
              (setq got t)
              (if (= (char-code c) 10)
                  (setq done t)
                  (setq acc (concatenate 'string acc (princ-to-string c))))))))
    (if got
        (let ((len (length acc)))
          (if (and (> len 0) (= (char-code (char acc (- len 1))) 13))
              (subseq acc 0 (- len 1))
              acc))
        nil)))

;;; --- the stdin-or-raw helpers: the seam sockets.lisp's and stdin-dispatch.lisp's
;;; dispatchers share. A nil stream designator is stdin; anything else is a native
;;; handle (file / string stream) served by the %...-raw built-ins. stdin-stub.lisp
;;; defines the same three names as raw passthroughs for serve mode, where the
;;; service world has no stdin. ---

;;; A non-handle designator (nil, or the t the unbound *standard-input* holds)
;;; is the host stdin stream; a handle -- a WASI fd or a string input stream --
;;; goes to the native built-in.
(rontolisp:async-defun rontolisp::%stdin-read-line-or-raw-f (s)
  (let ((in (or s *standard-input*)))
    (if (integerp in)
        (rontolisp::%read-line-raw in)
        (rontolisp:await (rontolisp::%stdin-read-line-f)))))

(rontolisp:async-defun rontolisp::%stdin-read-char-or-raw-f (s)
  ;; The native read-char's 0/1-arg form signals at EOF (eof-error-p defaults
  ;; to t), so the stdin path must too -- message parity with the interpreter.
  (let ((in (or s *standard-input*)))
    (if (integerp in)
        (rontolisp::%read-char-raw in)
        (let ((c (rontolisp:await (rontolisp::%stdin-read-char-f))))
          (if c c (error "read-char: end of file"))))))

(rontolisp:async-defun rontolisp::%stdin-read-byte-or-raw-f (s)
  ;; read-byte has NO stdin designator (a stream argument is mandatory and must
  ;; be a binary stream on the interpreter/JVM), so a nil stream is an error
  ;; here too -- never a read of the stdin stream, which would silently overlap
  ;; the adapter's cached stream in a program that also calls the eof-arg
  ;; read forms the rewrite leaves native.
  (if (null s)
      (error "read-byte expects an input stream")
      (rontolisp::%read-byte-raw s)))
