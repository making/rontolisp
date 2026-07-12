;; The usocket package: a usocket-compatible API shim over the built-in
;; rontolisp:tcp-* socket functions, written in rontolisp itself so a single
;; implementation runs on every backend: the interpreter loads these
;; definitions lazily on first use of a usocket: function (or variable), and
;; the compile path splices them into the program when it references the
;; package (see UsocketLibrary.java). The package is also registered as the
;; built-in ASDF system "usocket", so (asdf:load-system "usocket"),
;; (ql:quickload :usocket) and :depends-on ("usocket") resolve to this file
;; without touching the network.
;;
;; Compatibility notes (see the TCP sockets guide for the full list):
;; - TCP only: :protocol :datagram (UDP) signals an error.
;; - A socket object IS its stream handle (the rontolisp:tcp-* handle shares
;;   the file-stream handle space), so socket-stream is the identity function
;;   and read-line/write-line/read-byte/write-byte/close work on it directly.
;; - :element-type is accepted and ignored: a rontolisp socket handle is
;;   always bidirectional and supports both the line and the byte built-ins.
;; - The condition types below make connection failures CATCHABLE by type on
;;   the interpreter and the JVM: socket-connect/socket-listen/socket-accept
;;   re-signal any underlying error as usocket:socket-error through the
;;   usocket::%usock-guard internal form (a handler-case wrap there, a plain
;;   pass-through on WASM, where errors are uncatchable traps). The :report
;;   echoes the underlying message, so an uncaught failure prints exactly as
;;   before. connection-refused-error &c are defined as subtypes but the
;;   re-signal always uses socket-error (lite; catch socket-error).
;; - The with-* convenience macros are built-in LispMacroExpander expansions
;;   (see .kb/tcp-sockets.md), not defuns in this file.

(defparameter usocket:*wildcard-host* "0.0.0.0")

(defparameter usocket:*auto-port* 0)

(define-condition usocket:socket-condition (condition)
  ((message :initarg :message :initform "socket error"
            :reader usocket::%socket-condition-message))
  (:report (lambda (c s) (write-string (usocket::%socket-condition-message c) s))))

(define-condition usocket:socket-error (usocket:socket-condition error) ())

(define-condition usocket:connection-refused-error (usocket:socket-error) ())

(define-condition usocket:connection-aborted-error (usocket:socket-error) ())

(define-condition usocket:connection-reset-error (usocket:socket-error) ())

(define-condition usocket:timeout-error (usocket:socket-error) ())

(define-condition usocket:ns-error (usocket:socket-condition error) ())

(defun usocket::%usock-resignal (c)
  ;; Re-signal an underlying failure as a typed usocket:socket-error carrying
  ;; the original message (slot 1 of the synthesized simple-error the handler
  ;; receives); the :report echoes it, so an uncaught error prints unchanged.
  (error 'usocket:socket-error :message
         (if (stringp (nth 1 c)) (nth 1 c) "socket error")))

(defun usocket::%usock-listen-host (host)
  ;; usocket passes the host first and uses *wildcard-host* / nil for "all
  ;; interfaces"; rontolisp:tcp-listen expresses that as a nil host argument.
  (if (null host)
      nil
      (if (string= host "0.0.0.0") nil host)))

(defun usocket:socket-connect (host port &key protocol element-type timeout
                                    deadline nodelay local-host local-port
                                    &allow-other-keys)
  ;; TCP only. :element-type/:timeout/:deadline/:nodelay/:local-host/
  ;; :local-port are accepted and ignored (no such knobs on
  ;; rontolisp:tcp-connect).
  (when (eql protocol :datagram)
    (error "usocket:socket-connect: :protocol :datagram (UDP) is not supported"))
  (usocket::%usock-guard (rontolisp:tcp-connect host port)))

(defun usocket:socket-listen (host port &key reuse-address backlog element-type
                                   &allow-other-keys)
  ;; Note the flipped argument order vs rontolisp:tcp-listen (port &optional
  ;; host). reuse-address/backlog/element-type are accepted and ignored
  ;; (the backlog is the runtime default). tcp-listen rejects an explicit nil
  ;; host, so the all-interfaces case omits the argument.
  (let ((%usock-host (usocket::%usock-listen-host host)))
    (usocket::%usock-guard
     (if (null %usock-host)
         (rontolisp:tcp-listen port)
         (rontolisp:tcp-listen port %usock-host)))))

(defun usocket:socket-accept (socket &key element-type &allow-other-keys)
  (usocket::%usock-guard (rontolisp:tcp-accept socket)))

(defun usocket:socket-stream (socket)
  ;; The handle IS the stream: read-line/write-line/read-byte/write-byte/close
  ;; work on it directly.
  socket)

(defun usocket:socket-close (socket)
  (close socket))

(defun usocket:get-local-port (socket)
  (rontolisp:tcp-local-port socket))

(defun usocket:get-local-address (socket)
  (rontolisp:tcp-local-address socket))

(defun usocket:get-peer-port (socket)
  (rontolisp:tcp-peer-port socket))

(defun usocket:get-peer-address (socket)
  (rontolisp:tcp-peer-address socket))

(defun usocket:get-local-name (socket)
  ;; The literal (values ...) tail flows through the syntactic multiple-values
  ;; tier's user-function channel (see .kb/multiple-values.md), so a caller's
  ;; multiple-value-bind receives both the address and the port; an ordinary
  ;; single-value context receives the address.
  (values (rontolisp:tcp-local-address socket) (rontolisp:tcp-local-port socket)))

(defun usocket:get-peer-name (socket)
  (values (rontolisp:tcp-peer-address socket) (rontolisp:tcp-peer-port socket)))
