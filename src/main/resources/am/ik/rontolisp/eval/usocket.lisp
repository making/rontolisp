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
;; - socket-option supports :receive-timeout only (a real SO_TIMEOUT read
;;   deadline on the interpreter/JVM; the primitive signals on the WASM
;;   backends), and wait-for-input is a listen-based poll (real wait on the
;;   interpreter/JVM; the WASM backends return immediately claiming
;;   readiness). Both decisions and their reasons: .kb/tcp-sockets.md.

(defparameter usocket:*wildcard-host* "0.0.0.0")

(defparameter usocket:*auto-port* 0)

(define-condition usocket:socket-condition (condition)
  ((message :initarg :message
            :initform "socket error"
            :reader usocket::%socket-condition-message))
  (:report
   (lambda (c s) (write-string (usocket::%socket-condition-message c) s))))

(define-condition usocket:socket-error (usocket:socket-condition error) ())

(define-condition usocket:connection-refused-error (usocket:socket-error) ())

(define-condition usocket:connection-aborted-error (usocket:socket-error) ())

(define-condition usocket:connection-reset-error (usocket:socket-error) ())

(define-condition usocket:timeout-error (usocket:socket-error) ())

(define-condition usocket:ns-error (usocket:socket-condition error) ())

(defun usocket::%usock-resignal (c)
  ;; Re-signal an underlying failure as a typed usocket:socket-error carrying
  ;; the original message (the format-control of the synthesized simple-error the
  ;; handler receives); the :report echoes it, so an uncaught error prints
  ;; unchanged.
  (error 'usocket:socket-error
         :message (if (stringp (simple-condition-format-control c))
                      (simple-condition-format-control c)
                      "socket error")))

(defun usocket::%usock-listen-host (host)
  ;; usocket passes the host first and uses *wildcard-host* / nil for "all
  ;; interfaces"; rontolisp:tcp-listen expresses that as a nil host argument.
  (if (null host) nil (if (string= host "0.0.0.0") nil host)))

(defun usocket:socket-connect (host port &key protocol element-type timeout
                                    deadline nodelay local-host local-port
                                    &allow-other-keys)
  ;; TCP only. :element-type/:timeout/:deadline/:nodelay/:local-host/
  ;; :local-port are accepted and ignored (no such knobs on
  ;; rontolisp:tcp-connect).
  ;; :datagram reads :DATAGRAM under the reader's upcase premise, as does a
  ;; user's :protocol :datagram argument, so one arm suffices.
  (when (eql protocol :datagram)
    (error
     "usocket:socket-connect: :protocol :datagram (UDP) is not supported"))
  (usocket::%usock-guard (rontolisp:tcp-connect host port)))

(defun usocket:socket-listen
    (host port &key reuse-address backlog element-type &allow-other-keys)
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

(defun usocket:socket-close (socket) (close socket))

(defun usocket:get-local-port (socket) (rontolisp:tcp-local-port socket))

(defun usocket:get-local-address (socket) (rontolisp:tcp-local-address socket))

(defun usocket:get-peer-port (socket) (rontolisp:tcp-peer-port socket))

(defun usocket:get-peer-address (socket) (rontolisp:tcp-peer-address socket))

(defun usocket:get-local-name (socket)
  ;; The literal (values ...) tail flows through the syntactic multiple-values
  ;; tier's user-function channel (see .kb/multiple-values.md), so a caller's
  ;; multiple-value-bind receives both the address and the port; an ordinary
  ;; single-value context receives the address.
  (values (rontolisp:tcp-local-address socket)
          (rontolisp:tcp-local-port socket)))

(defun usocket:get-peer-name (socket)
  (values (rontolisp:tcp-peer-address socket) (rontolisp:tcp-peer-port socket)))

(defun usocket:host-to-hostname (host)
  ;; Every host-designator shape upstream accepts, rendered the way upstream
  ;; renders it: a string passes through, a vector quad (or list of four
  ;; octets) and a host-byte-order 32-bit integer become the dotted quad, and
  ;; nil is the wildcard host.
  (cond ((null host) "0.0.0.0")
        ((stringp host) host)
        ((integerp host)
         (format nil "~D.~D.~D.~D" (logand (ash host -24) 255)
                 (logand (ash host -16) 255) (logand (ash host -8) 255)
                 (logand host 255)))
        ((or (vectorp host) (consp host))
         (format nil "~D.~D.~D.~D" (elt host 0) (elt host 1) (elt host 2)
                 (elt host 3)))
        (t (string host))))

;;; --- socket options ---

;; The one supported option is :receive-timeout, backed by the
;; rontolisp:tcp-set-timeout read deadline (real on the interpreter and the
;; JVM via SO_TIMEOUT; the WASM component primitive SIGNALS -- wasi:sockets
;; has no timeout knob and a timeout that never fires is the failure mode a
;; client sets it to avoid; Preview 1 keeps the tcp family's call-time error).
;; Every other option signals rather than silently ignoring. The alist below
;; is the read-back bookkeeping for the getter: the shim is the only writer,
;; so the recorded seconds are authoritative. Entries are not dropped on
;; socket-close (lite edge; a handle is never reused).

(defparameter usocket::*%usock-timeouts* nil)

(defun usocket::%usock-forget-timeout (handle alist)
  (if (null alist)
      nil
      (if (eql (car (car alist)) handle)
          (usocket::%usock-forget-timeout handle (cdr alist))
          (cons (car alist)
                (usocket::%usock-forget-timeout handle (cdr alist))))))

(defun usocket::%usock-option-check (option)
  (if (eql option :receive-timeout)
      option
      (error
       (format nil
               "usocket:socket-option: option ~S is not supported (only :receive-timeout)"
               option))))

(defun usocket:socket-option (socket option)
  ;; Reads the :receive-timeout previously set through the setf place below,
  ;; in seconds; nil when none was set. (Upstream's &key tail is dropped: no
  ;; option here takes one.)
  (usocket::%usock-option-check option)
  (let ((%so-pair (assoc socket usocket::*%usock-timeouts*)))
    (if %so-pair (cdr %so-pair) nil)))

(defun (setf usocket:socket-option) (new-value socket option)
  ;; (setf (usocket:socket-option s :receive-timeout) seconds) -- the portable
  ;; usocket spelling of a read timeout (dexador sets it on every connection).
  ;; Seconds may be any non-negative real; nil clears the deadline.
  (usocket::%usock-option-check option)
  (usocket::%usock-guard
   (rontolisp:tcp-set-timeout socket
    (if (null new-value) nil (round (* new-value 1000)))))
  (setq usocket::*%usock-timeouts*
        (if (null new-value)
            (usocket::%usock-forget-timeout socket usocket::*%usock-timeouts*)
            (cons (cons socket new-value)
                  (usocket::%usock-forget-timeout socket
                                                  usocket::*%usock-timeouts*))))
  new-value)

;;; --- wait-for-input ---

(defun usocket::%usock-ready (sockets)
  ;; The subset of SOCKETS with immediately-available input, probed through
  ;; cl:listen: the kernel receive buffer on the interpreter/JVM, the chunk
  ;; readahead buffer on the WASM component (listen's own documented
  ;; divergence there). Stream sockets only -- listen on a listener handle
  ;; signals (no backend has a non-blocking accept probe).
  (let ((%wfi-ready nil))
    (dolist (%wfi-s sockets)
      (when (listen %wfi-s) (setq %wfi-ready (cons %wfi-s %wfi-ready))))
    (reverse %wfi-ready)))

(defun usocket::%usock-wfi-result
    (socket-or-sockets all ready wasm ready-only timeout start)
  ;; Upstream's two return values: the ready sockets (the original argument
  ;; unless :ready-only) and the time remaining within :timeout (nil once it
  ;; elapsed, or when none was given). On the WASM backends an empty ready set
  ;; still CLAIMS readiness (see wait-for-input below), so :ready-only answers
  ;; the full list there.
  (let ((%wfi-claimed (if (if wasm (null ready) nil) all ready))
        (%wfi-remaining
         (if timeout
             (let ((%wfi-elapsed
                    (/ (- (get-internal-real-time) start)
                       internal-time-units-per-second)))
               (if (< %wfi-elapsed timeout) (- timeout %wfi-elapsed) nil))
             nil)))
    (values (if ready-only %wfi-claimed socket-or-sockets) %wfi-remaining)))

(defun usocket:wait-for-input
    (socket-or-sockets &key timeout ready-only &allow-other-keys)
  ;; Waits until at least one of the given stream sockets has readable input,
  ;; polling cl:listen every 10 ms (a timeout of 0 polls once); without
  ;; :timeout it waits indefinitely. A REAL wait on the interpreter and the
  ;; JVM, where listen asks the kernel. On the WASM backends it returns
  ;; IMMEDIATELY claiming readiness when nothing is buffered (reads block
  ;; anyway, so the wait-then-read loop behaves identically; a poll loop
  ;; cannot be honoured there -- component listen sees only the chunk buffer,
  ;; so a real poll would spin forever on data waiting host-side). wait-list
  ;; objects are not supported.
  (if (null socket-or-sockets)
      (progn
        (when timeout (sleep timeout))
        nil)
      (let* ((%wfi-list
              (if (consp socket-or-sockets)
                  socket-or-sockets
                  (list socket-or-sockets)))
             (%wfi-start (get-internal-real-time))
             (%wfi-deadline
              (if timeout
                  (+ %wfi-start
                     (round (* timeout internal-time-units-per-second)))
                  nil))
             (%wfi-wasm (if (member :rontolisp-wasm *features*) t nil)))
        (do ((%wfi-ready
              (usocket::%usock-ready %wfi-list)
              (usocket::%usock-ready %wfi-list)))
            ((or %wfi-ready %wfi-wasm
                 (if %wfi-deadline
                     (>= (get-internal-real-time) %wfi-deadline)
                     nil))
             (usocket::%usock-wfi-result socket-or-sockets %wfi-list %wfi-ready
                                         %wfi-wasm ready-only timeout
                                         %wfi-start))
          (sleep 0.01)))))

(defun usocket:get-host-by-name (name)
  ;; Lite, and identically so on every backend: rontolisp has no name-resolution
  ;; primitive at all, so this cannot return upstream's resolved vector quad. It
  ;; renders the designator instead, which keeps clack's normalize-then-hand-on
  ;; chain -- (host-to-hostname (get-host-by-name address)) -- an identity on the
  ;; address it is given; the tcp-connect/tcp-listen call that address eventually
  ;; reaches does the real resolution inside the host (natively on the
  ;; interpreter and the JVM; IPv4 literals only on WASM).
  ;;
  ;; Deliberately NOT resolved on the interpreter/JVM only: a per-backend answer
  ;; here would buy nothing (both sides hand the result straight back to a socket
  ;; call that resolves anyway) and would cost a backend divergence in a library
  ;; every socket program splices. Re-evaluate when a cross-backend resolver
  ;; primitive exists -- wiring wasi:sockets/ip-name-lookup is what that waits on
  ;; -- at which point this should return a real vector quad on all four.
  (usocket:host-to-hostname name))
