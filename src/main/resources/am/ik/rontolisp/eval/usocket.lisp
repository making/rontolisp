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
;;   re-signal always uses socket-error (lite; catch socket-error). On the
;;   WASM component the primitives answer nil instead of signalling; the three
;;   signal socket-error for that nil (usocket::%usock-failed), so a usocket
;;   failure signals on every backend.
;; - The with-* convenience macros are built-in LispMacroExpander expansions
;;   (see .kb/tcp-sockets.md), not defuns in this file.
;; - socket-option supports :receive-timeout only (a real read deadline
;;   wherever the tcp built-ins run), and wait-for-input is a listen-based poll
;;   (a real wait wherever the tcp built-ins run, since listen asks the kernel
;;   or the host on each). Both decisions and their reasons: .kb/tcp-sockets.md.

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

(defun usocket::%usock-failed (message)
  ;; The signal for a primitive that answered nil, the WASM component's failure
  ;; convention (the interpreter, the JVM and --native signal themselves, with
  ;; the same text plus the host's reason, which the component does not surface).
  ;; A library must never carry a nil socket on: into a stream call it is an
  ;; unreadable cast-failure trap, and dexador reconnects for ever.
  (error 'usocket:socket-error :message message))

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
  (or (usocket::%usock-guard (rontolisp:tcp-connect host port))
      (usocket::%usock-failed
       (format nil "tcp-connect: cannot connect to ~A:~A" host port))))

(defun usocket:socket-listen
    (host port &key reuse-address backlog element-type &allow-other-keys)
  ;; Note the flipped argument order vs rontolisp:tcp-listen (port &optional
  ;; host). reuse-address/backlog/element-type are accepted and ignored
  ;; (the backlog is the runtime default). tcp-listen rejects an explicit nil
  ;; host, so the all-interfaces case omits the argument.
  (let ((%usock-host (usocket::%usock-listen-host host)))
    (or (usocket::%usock-guard
         (if (null %usock-host)
             (rontolisp:tcp-listen port)
             (rontolisp:tcp-listen port %usock-host)))
        (usocket::%usock-failed
         (format nil "tcp-listen: cannot listen on ~A:~D" (or %usock-host "*")
                 port)))))

(defun usocket:socket-accept (socket &key element-type &allow-other-keys)
  (or (usocket::%usock-guard (rontolisp:tcp-accept socket))
      (usocket::%usock-failed "tcp-accept: no connection was accepted")))

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
;; rontolisp:tcp-set-timeout read deadline (SO_TIMEOUT on the interpreter and
;; the JVM, the runner's set_read_timeout in a native executable, a read raced
;; against a timer on the WASM component; Preview 1 keeps the tcp family's
;; call-time error).
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
  ;; cl:listen: the kernel receive buffer on the interpreter, the JVM and in a
  ;; native executable, what the host hands over at once on the WASM component.
  ;; Stream sockets only -- listen on a listener handle signals on the
  ;; interpreter and the JVM (no backend has a non-blocking accept probe).
  (let ((%wfi-ready nil))
    (dolist (%wfi-s sockets)
      (when (listen %wfi-s) (setq %wfi-ready (cons %wfi-s %wfi-ready))))
    (reverse %wfi-ready)))

(defun usocket::%usock-wfi-result
    (socket-or-sockets ready ready-only timeout start)
  ;; Upstream's two return values: the ready sockets (the original argument
  ;; unless :ready-only) and the time remaining within :timeout (nil once it
  ;; elapsed, or when none was given).
  (let ((%wfi-remaining
         (if timeout
             (let ((%wfi-elapsed
                    (/ (- (get-internal-real-time) start)
                       internal-time-units-per-second)))
               (if (< %wfi-elapsed timeout) (- timeout %wfi-elapsed) nil))
             nil)))
    (values (if ready-only ready socket-or-sockets) %wfi-remaining)))

(defun usocket:wait-for-input
    (socket-or-sockets &key timeout ready-only &allow-other-keys)
  ;; Waits until at least one of the given stream sockets has readable input,
  ;; polling cl:listen every 10 ms (a timeout of 0 polls once); without
  ;; :timeout it waits indefinitely. A REAL wait on every backend with sockets:
  ;; listen asks the kernel on the interpreter, the JVM and in a native
  ;; executable, and the host (a read started and withdrawn) on the WASM
  ;; component. wait-list objects are not supported.
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
                  nil)))
        (do ((%wfi-ready
              (usocket::%usock-ready %wfi-list)
              (usocket::%usock-ready %wfi-list)))
            ((or %wfi-ready
                 (if %wfi-deadline
                     (>= (get-internal-real-time) %wfi-deadline)
                     nil))
             (usocket::%usock-wfi-result socket-or-sockets %wfi-ready ready-only
                                         timeout %wfi-start))
          (sleep 0.01)))))

(defun usocket:get-host-by-name (name)
  ;; Lite, and identically so on every backend: rontolisp has no name-resolution
  ;; primitive at all, so this cannot return upstream's resolved vector quad. It
  ;; renders the designator instead, which keeps clack's normalize-then-hand-on
  ;; chain -- (host-to-hostname (get-host-by-name address)) -- an identity on the
  ;; address it is given; the tcp-connect/tcp-listen call that address eventually
  ;; reaches does the real resolution (the JDK's on the interpreter and the JVM,
  ;; the runner's under --native, wasi:sockets/ip-name-lookup on the component).
  ;;
  ;; Deliberately NOT resolved on some backends only: a per-backend answer here
  ;; would buy nothing (every side hands the result straight back to a socket
  ;; call that resolves anyway) and would cost a backend divergence in a library
  ;; every socket program splices. Re-evaluate when a resolver primitive exists
  ;; on all four, at which point this should return a real vector quad there.
  (usocket:host-to-hostname name))
