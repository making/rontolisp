;;;; runner-sockets.lisp -- rontolisp:tcp-* over the rltcp imports of a --native
;;;; executable's runner (rontolisp-native/runner/src/tcp.rs).
;;;;
;;;; The runner's provider of the dispatch layer sockets.lisp provides a
;;;; --component program: spliced by eval/SocketsLibrary when a --native program
;;;; references a rontolisp:tcp-* built-in or the usocket package. Same names,
;;;; same argument shapes: the WASM compiler's socket I/O rewrite
;;;; (WasmSocketsRewrite) redirects every socket-capable stream call site onto
;;;; the %io-* dispatch defuns below without knowing which provider it got, so a
;;;; name or a shape the providers define differently breaks every program on
;;;; the other (pinned by StdinLibraryTest). What differs is the host and the
;;;; blocking: the runner owns a std::net socket per handle and every import
;;;; BLOCKS the module's one thread, as the interpreter's java.net.Socket calls
;;;; do -- a Preview 1 module has no scheduler, and its async bodies run to their
;;;; end -- so the %...-future twins are plain defuns answering settled values
;;;; (rontolisp:await passes a non-future through) and nothing forces a future.
;;;;
;;;; Errors SIGNAL, with the interpreter's and the JVM's texts (.kb/tcp-sockets.md):
;;;; a host failure is `OP: cannot connect to H:P: MSG`, `OP: cannot listen on
;;;; H:P: MSG` or `OP: MSG` (MSG the OS's text as the runner words it), a handle
;;;; of the wrong kind `OP expects a ... handle`. %sock-fail is the one
;;;; signalling site, and its NAME puts the module in exception-handling mode
;;;; (WasmLispCompiler.usesEhForm), so the signal is a catchable simple-error
;;;; that reports its text when uncaught -- as sockets.lisp's handler-case forms
;;;; do for a component, where a bare (error ...) outside EH mode is a trap
;;;; carrying no text.
;;;;
;;;; Reads buffer one host chunk per socket (the component's documented
;;;; divergence from the byte-at-a-time interpreter/JVM reads): the chunk is a
;;;; packed (unsigned-byte 8) vector the read import fills, walked with aref --
;;;; BYTES, never decoded characters. The string-building reads and the write
;;;; side are sockets.lisp's, byte for byte: a line or a character is assembled
;;;; from wire bytes with %str-from-byte / %string-concat, and a write hands
;;;; the string's raw bytes (or an octet vector) to the host as they are.
;;;;
;;;; A SECOND HOST of the same layer is a --no-wasi module's (--host-sockets,
;;;; #+rontolisp-host-sockets): the JavaScript glue --emit-js-glue writes, over
;;;; connect() of cloudflare:sockets (compiler/HostSockets). It only connects --
;;;; a bind, a listen and an accept are refused here, by name -- and what it has
;;;; for the module comes while the module is suspended in it (JSPI): every wait
;;;; is its `wait`, the one import a read waits in, and a write is sent by the
;;;; time `flush` answers. The forms the two hosts differ in are apart below;
;;;; every dispatch defun is the one source.

;; The runner's host: a socket is the number the host answered, -1 a failure
;; whose text lastError holds. read fills the caller's buffer (the :bytes
;; shape: the trailing argument is the (unsigned-byte 8) vector) and answers
;; the count, 0 at the peer's close; write takes an octet vector or a string's
;; raw bytes into the socket's output buffer, which flush sends (the host also
;; sends it before any wait on a socket, at close and at the program's end);
;; readable answers 1 while bytes wait to be read, without blocking; wait takes
;; host numbers as an octet vector, four bytes each, least significant first.
;; socket makes a socket not connected yet, bound unless the port is -1 (an
;; empty host is every interface); begin starts its connect with no wait,
;; answering 1 once connected and 0 while the connect goes on, which wait
;; answers for once it has ended and end then finishes; shutdown ends the output
;; (1), after what is buffered, or the input (0) of a connected socket; option
;; gives one of its options to the host, or sends a byte of urgent data
;; (LispNames.TCP_OPTION_INTERNAL).
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-connect
                       :from "rltcp"
                       :as "connect"
                       :params '(:string :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-socket
                       :from "rltcp"
                       :as "socket"
                       :params '(:string :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-begin
                       :from "rltcp"
                       :as "begin"
                       :params '(:s32 :string :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-end
                       :from "rltcp"
                       :as "end"
                       :params '(:s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-shutdown
                       :from "rltcp"
                       :as "shutdown"
                       :params '(:s32 :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-option
                       :from "rltcp"
                       :as "option"
                       :params '(:s32 :s32 :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-listen
                       :from "rltcp"
                       :as "listen"
                       :params '(:string :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-accept
                       :from "rltcp"
                       :as "accept"
                       :params '(:s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-read
                       :from "rltcp"
                       :as "read"
                       :params '(:s32)
                       :returns :bytes)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-write
                       :from "rltcp"
                       :as "write"
                       :params '(:s32 :octets)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-flush
                       :from "rltcp"
                       :as "flush"
                       :params '(:s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-close
                       :from "rltcp"
                       :as "close"
                       :params '(:s32)
                       :returns :void)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-local-port
                       :from "rltcp"
                       :as "localPort"
                       :params '(:s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-local-address
                       :from "rltcp"
                       :as "localAddress"
                       :params '(:s32)
                       :returns :string)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-peer-address
                       :from "rltcp"
                       :as "peerAddress"
                       :params '(:s32)
                       :returns :string)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-peer-port
                       :from "rltcp"
                       :as "peerPort"
                       :params '(:s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-set-timeout
                       :from "rltcp"
                       :as "setTimeout"
                       :params '(:s32 :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-readable
                       :from "rltcp"
                       :as "readable"
                       :params '(:s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-wait
                       :from "rltcp"
                       :as "wait"
                       :params '(:octets :s32 :s32)
                       :returns :s32)
#-rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-last-error
                       :from "rltcp"
                       :as "lastError"
                       :params '()
                       :returns :string)

;; The host of a --no-wasi module (--host-sockets): the same numbers and the same
;; lastError, over a host that only connects and answers by suspending the module.
;; socket makes a socket not connected yet; begin starts its connect and answers 0,
;; which wait answers for once the connect has ended and end then finishes (-1: it
;; failed); read answers at most that many of the octets that have come, none at
;; the peer's close and where the socket failed, lastError then holding why (empty
;; at the close); write hands the octets to the host, which flush answers for once
;; they are sent; poll answers the index of the first socket of the host numbers
;; that has something for a read or whose connect has ended -- octets, the peer's
;; close, a failure -- or -1, with no wait; wait the same once one has, or -1 after
;; that many milliseconds (-1: no limit).
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-socket
                       :from "tcp"
                       :as "socket"
                       :params '()
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-begin
                       :from "tcp"
                       :as "begin"
                       :params '(:s32 :string :s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-end
                       :from "tcp"
                       :as "end"
                       :params '(:s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-shutdown
                       :from "tcp"
                       :as "shutdown"
                       :params '(:s32 :s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-option
                       :from "tcp"
                       :as "option"
                       :params '(:s32 :s32 :s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-read
                       :from "tcp"
                       :as "read"
                       :params '(:s32 :s32)
                       :returns :bytes)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-write
                       :from "tcp"
                       :as "write"
                       :params '(:s32 :octets)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-flush
                       :from "tcp"
                       :as "flush"
                       :params '(:s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-close
                       :from "tcp"
                       :as "close"
                       :params '(:s32)
                       :returns :void)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-local-port
                       :from "tcp"
                       :as "localPort"
                       :params '(:s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-local-address
                       :from "tcp"
                       :as "localAddress"
                       :params '(:s32)
                       :returns :string)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-peer-address
                       :from "tcp"
                       :as "peerAddress"
                       :params '(:s32)
                       :returns :string)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-peer-port
                       :from "tcp"
                       :as "peerPort"
                       :params '(:s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-readable
                       :from "tcp"
                       :as "readable"
                       :params '(:s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-poll
                       :from "tcp"
                       :as "poll"
                       :params '(:octets)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-wait
                       :from "tcp"
                       :as "wait"
                       :params '(:octets :s32)
                       :returns :s32)
#+rontolisp-host-sockets
(rontolisp:wasm-import 'rontolisp::%rltcp-last-error
                       :from "tcp"
                       :as "lastError"
                       :params '()
                       :returns :string)

;;; --- the socket table: fd -> (host kind buf cursor limit eof mid-line
;;; deadline) ---
;;; host = the runner's number; kind 1 = connected, 2 = listener, 3 = not
;;; connected yet (%tcp-socket's, which %tcp-connect-socket connects in place);
;;; buf = the chunk buffer (a connected socket's), cursor/limit the unread bytes
;;; in it; deadline = the read deadline (a listener's: the accept deadline) in
;;; milliseconds, or nil, which the runner's socket keeps too: a thread of the
;;; Java subset waits for it.

(defvar rontolisp::*sock-table* (make-hash-table))
(defvar rontolisp::*sock-next-fd* 200)

;; The raw handle a stream designator names: an OPEN stream is a VALUE carrying
;; its handle in slot 0, and a synonym stream forwards to whatever its variable
;; holds now (sockets.lisp's %sock-handle, for the same reason).
(defun rontolisp::%sock-handle (s)
  (if (%obj-is s '%SYNONYM-STREAM)
      (rontolisp::%sock-handle (funcall (%obj-ref s 1)))
      (if (%obj-is s '%STREAM) (%obj-ref s 0) s)))

;; The stream VALUE %sock-entry resolved last and its entry: a program reading a
;; socket a byte at a time names the same stream on every call, and the table's
;; lookup cost it a third of each byte. Only an open stream value is remembered (a
;; synonym stream answers what its variable holds now), and every remhash of the
;; table forgets it.
(defvar rontolisp::*sock-last-stream* nil)
(defvar rontolisp::*sock-last-entry* nil)

(defun rontolisp::%sock-entry (fd)
  (if (and fd (eq fd rontolisp::*sock-last-stream*))
      rontolisp::*sock-last-entry*
      (let* ((h (rontolisp::%sock-handle fd))
             (e (if (integerp h) (gethash h rontolisp::*sock-table*) nil)))
        (when (and e (%obj-is fd '%STREAM))
          (setq rontolisp::*sock-last-stream* fd)
          (setq rontolisp::*sock-last-entry* e))
        e)))

(defun rontolisp::%sock-e-host (e) (car e))
(defun rontolisp::%sock-e-kind (e) (car (cdr e)))
(defun rontolisp::%sock-e-buf (e) (car (cdr (cdr e))))
(defun rontolisp::%sock-e-cursor (e) (car (cdr (cdr (cdr e)))))
(defun rontolisp::%sock-e-limit (e) (car (cdr (cdr (cdr (cdr e))))))
(defun rontolisp::%sock-e-eof (e) (car (cdr (cdr (cdr (cdr (cdr e)))))))
(defun rontolisp::%sock-e-mid-line (e)
  (car (cdr (cdr (cdr (cdr (cdr (cdr e))))))))

(defun rontolisp::%sock-set-mid-line (e mid-line)
  (rplaca (cdr (cdr (cdr (cdr (cdr (cdr e)))))) mid-line))

(defun rontolisp::%sock-set-buf (e cursor limit)
  (rplaca (cdr (cdr (cdr e))) cursor)
  (rplaca (cdr (cdr (cdr (cdr e)))) limit))

(defun rontolisp::%sock-set-eof (e) (rplaca (cdr (cdr (cdr (cdr (cdr e))))) t))

(defun rontolisp::%sock-e-deadline (e) (car (nthcdr 7 e)))

;; Answers the stream VALUE over the freshly-minted handle, so streamp and
;; (typep s 'stream) answer for a socket as on every other backend; the table
;; stays keyed by the raw handle.
(defun rontolisp::%sock-register (host kind)
  (let ((fd rontolisp::*sock-next-fd*))
    (setq rontolisp::*sock-next-fd* (+ fd 1))
    (setf (gethash fd rontolisp::*sock-table*)
          (list host kind
                (if (= kind 1)
                    (make-array 8192 :element-type '(unsigned-byte 8))
                    nil) 0 0 nil nil nil))
    (%obj-new '%STREAM fd (if (= kind 2) :socket-server :socket))))

;;; --- failures: one signalling site, the interpreter's texts ---

(defun rontolisp::%sock-fail (message) (error "~a" message))

(defun rontolisp::%sock-host-fail (prefix)
  (rontolisp::%sock-fail
   (concatenate 'string prefix (rontolisp::%rltcp-last-error))))

(defun rontolisp::%sock-decimal (n)
  (let ((s ""))
    (if (= n 0)
        "0"
        (progn
          (while (> n 0)
            (setq s
                  (concatenate 'string
                               (%princ-piece (code-char (+ 48 (mod n 10)))) s))
            (setq n (floor n 10)))
          s))))

(defun rontolisp::%sock-millis (ms)
  ;; A count of milliseconds an :s32 import takes: an argument past 2^30 is no
  ;; fixnum there and traps (.kb/wasm-import.md), and 2^30 - 1 ms is more than
  ;; twelve days.
  (min ms 1073741823))

(defun rontolisp::%sock-check-port (op port)
  (if (and (integerp port) (>= port 0) (<= port 65535))
      port
      (rontolisp::%sock-fail
       (concatenate 'string op
                    ": the port must be an integer from 0 to 65535"))))

(defun rontolisp::%sock-check-host (op host)
  (if (stringp host)
      host
      (rontolisp::%sock-fail
       (concatenate 'string op ": the host must be a string"))))

(defun rontolisp::%sock-listener (op fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if (and e (= (rontolisp::%sock-e-kind e) 2))
        e
        (rontolisp::%sock-fail
         (concatenate 'string op " expects a listener handle")))))

(defun rontolisp::%sock-connected (op fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if (and e (= (rontolisp::%sock-e-kind e) 1))
        e
        (rontolisp::%sock-fail
         (concatenate 'string op " expects a connected socket handle")))))

(defun rontolisp::%sock-any (op fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if e
        e
        (rontolisp::%sock-fail
         (concatenate 'string op " expects a socket or listener handle")))))

;;; --- the tcp-* surface. The %tcp-connect-f / %tcp-accept-f twins are what
;;; the rewrite promotes an async body's call onto; here they ARE the blocking
;;; call, and the await around them passes the handle through. ---

#-rontolisp-host-sockets
(defun rontolisp:tcp-connect (host port)
  (rontolisp::%sock-check-host "tcp-connect" host)
  (rontolisp::%sock-check-port "tcp-connect" port)
  (let ((h (rontolisp::%rltcp-connect host port)))
    (if (< h 0)
        (rontolisp::%sock-host-fail
         (concatenate 'string "tcp-connect: cannot connect to " host ":"
                      (rontolisp::%sock-decimal port) ": "))
        (rontolisp::%sock-register h 1))))

;; What a --no-wasi module's host refuses: it has no bind, no listen and no accept.
#+rontolisp-host-sockets
(defun rontolisp::%sock-outbound-only (said)
  (rontolisp::%sock-fail
   (concatenate 'string said
    ": the host of a --no-wasi module only connects (--host-sockets)")))

;; A --no-wasi module's host: a socket made, its connect begun, waited for in the
;; host and ended; a connect that failed leaves no socket.
#+rontolisp-host-sockets
(defun rontolisp:tcp-connect (host port)
  (rontolisp::%sock-check-host "tcp-connect" host)
  (rontolisp::%sock-check-port "tcp-connect" port)
  (let* ((fd (rontolisp::%tcp-socket nil nil))
         (e (rontolisp::%sock-entry fd))
         (h (rontolisp::%sock-e-host e)))
    (if (and (>= (rontolisp::%rltcp-begin h host port) 0)
             (progn
               (rontolisp::%sock-wait (list fd) -1 nil)
               (>= (rontolisp::%rltcp-end h) 0)))
        (progn
          (rontolisp::%sock-now-connected e)
          fd)
        (let ((why (rontolisp::%rltcp-last-error)))
          (rontolisp::%io-close fd)
          (rontolisp::%sock-connect-fail host port why)))))

(defun rontolisp::%tcp-connect-f (host port) (rontolisp:tcp-connect host port))

#+rontolisp-host-sockets
(defun rontolisp:tcp-listen (port &optional host)
  (when host (rontolisp::%sock-check-host "tcp-listen" host))
  (rontolisp::%sock-check-port "tcp-listen" port)
  (rontolisp::%sock-outbound-only
   (concatenate 'string "tcp-listen: cannot listen on " (if host host "*") ":"
                (rontolisp::%sock-decimal port))))

#-rontolisp-host-sockets
(defun rontolisp:tcp-listen (port &optional host)
  (when host (rontolisp::%sock-check-host "tcp-listen" host))
  (rontolisp::%sock-check-port "tcp-listen" port)
  (let ((h (rontolisp::%rltcp-listen (if host host "") port)))
    (if (< h 0)
        (rontolisp::%sock-host-fail
         (concatenate 'string "tcp-listen: cannot listen on " (if host host "*")
                      ":" (rontolisp::%sock-decimal port) ": "))
        (rontolisp::%sock-register h 2))))

#+rontolisp-host-sockets
(defun rontolisp:tcp-accept (listener)
  (rontolisp::%sock-listener "TCP-ACCEPT" listener)
  (rontolisp::%sock-outbound-only "tcp-accept"))

#-rontolisp-host-sockets
(defun rontolisp:tcp-accept (listener)
  ;; An accept past the listener's deadline (tcp-set-timeout) fails in the
  ;; runner with the JDK's words, `Accept timed out`.
  (let* ((e (rontolisp::%sock-listener "TCP-ACCEPT" listener))
         (h (rontolisp::%rltcp-accept (rontolisp::%sock-e-host e))))
    (if (< h 0)
        (rontolisp::%sock-host-fail "tcp-accept: ")
        (rontolisp::%sock-register h 1))))

(defun rontolisp::%tcp-accept-f (listener) (rontolisp:tcp-accept listener))

(defun rontolisp:tcp-local-port (fd)
  (rontolisp::%rltcp-local-port
   (rontolisp::%sock-e-host (rontolisp::%sock-any "TCP-LOCAL-PORT" fd))))

(defun rontolisp:tcp-local-address (fd)
  (rontolisp::%rltcp-local-address
   (rontolisp::%sock-e-host (rontolisp::%sock-any "TCP-LOCAL-ADDRESS" fd))))

(defun rontolisp:tcp-peer-address (fd)
  (rontolisp::%rltcp-peer-address
   (rontolisp::%sock-e-host
    (rontolisp::%sock-connected "TCP-PEER-ADDRESS" fd))))

(defun rontolisp:tcp-peer-port (fd)
  (rontolisp::%rltcp-peer-port
   (rontolisp::%sock-e-host (rontolisp::%sock-connected "TCP-PEER-PORT" fd))))

(defun rontolisp:tcp-set-timeout (fd ms)
  ;; A REAL per-socket read deadline (the runner's set_read_timeout), nil
  ;; clearing it; a read past it signals `OP: Read timed out` out of the
  ;; ordinary stream built-in, as on the interpreter and the JVM. A listener's
  ;; is its accept deadline, which the runner keeps too. The entry keeps either
  ;; for a thread of the Java subset, which waits for the socket among the
  ;; threads before it reads or accepts.
  (let ((e (rontolisp::%sock-any "TCP-SET-TIMEOUT" fd)))
    (unless (or (null ms) (and (integerp ms) (>= ms 0)))
      (rontolisp::%sock-fail
       "tcp-set-timeout: the deadline must be nil or a non-negative integer"))
    ;; a --no-wasi module's host keeps no deadline: a read waits for the entry's
    #-rontolisp-host-sockets
    (when (and (< (rontolisp::%sock-e-kind e) 3)
               (< (rontolisp::%rltcp-set-timeout (rontolisp::%sock-e-host e)
                   (if ms (rontolisp::%sock-millis ms) 0)) 0))
      (rontolisp::%sock-host-fail "tcp-set-timeout: "))
    (rplaca (nthcdr 7 e)
            (if (and ms (> ms 0)) (rontolisp::%sock-millis ms) nil))
    ms))

;; The read deadline of the connected socket FD, or the accept deadline of the
;; listener FD, in milliseconds; nil for none.
(defun rontolisp::%sock-read-deadline (fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if e (rontolisp::%sock-e-deadline e) nil)))

;;; --- a socket made before its connect (LispNames.TCP_SOCKET_INTERNAL,
;;; TCP_CONNECT_SOCKET_INTERNAL): bound first or not, and connected in place --
;;; the connect begun with no wait, the socket waited for until it can be
;;; written to or the time limit has come, then ended. A thread of the Java
;;; subset waits for it among the others (java.lisp), so the steps are apart. ---

#+rontolisp-host-sockets
(defun rontolisp::%tcp-socket (host port)
  ;; A socket not connected yet: a --no-wasi module's host binds none.
  (when (and port host) (rontolisp::%sock-check-host "%TCP-SOCKET" host))
  (when port
    (rontolisp::%sock-check-port "%TCP-SOCKET" port)
    (rontolisp::%sock-outbound-only
     (concatenate 'string "%tcp-socket: cannot bind to " (if host host "*") ":"
                  (rontolisp::%sock-decimal port))))
  (let ((h (rontolisp::%rltcp-socket)))
    (if (< h 0)
        (rontolisp::%sock-host-fail "%tcp-socket: ")
        (rontolisp::%sock-register h 3))))

#-rontolisp-host-sockets
(defun rontolisp::%tcp-socket (host port)
  ;; A socket not connected yet, bound to HOST:PORT for an integer PORT (every
  ;; interface for a nil HOST), else not bound.
  (when (and port host) (rontolisp::%sock-check-host "%TCP-SOCKET" host))
  (when port (rontolisp::%sock-check-port "%TCP-SOCKET" port))
  (let ((h
         (rontolisp::%rltcp-socket (if (and port host) host "")
                                   (if port port -1))))
    (if (< h 0)
        (rontolisp::%sock-host-fail
         (concatenate 'string "%tcp-socket: cannot bind to " (if host host "*")
                      ":" (rontolisp::%sock-decimal (if port port 0)) ": "))
        (rontolisp::%sock-register h 3))))

(defun rontolisp::%sock-unconnected (fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if (and e (= (rontolisp::%sock-e-kind e) 3))
        e
        (rontolisp::%sock-fail "%TCP-CONNECT-SOCKET expects a socket handle"))))

(defun rontolisp::%sock-connect-fail (host port why)
  (rontolisp::%sock-fail
   (concatenate 'string "tcp-connect: cannot connect to " host ":"
                (rontolisp::%sock-decimal port) ": " why)))

(defun rontolisp::%sock-now-connected (e)
  (rplaca (cdr e) 1)
  (rplaca (cdr (cdr e)) (make-array 8192 :element-type '(unsigned-byte 8))))

;; The connect of FD, a socket %tcp-socket made, to HOST:PORT begun with no wait:
;; t once connected, nil while it goes on -- %sock-wait answers for the socket
;; once it has ended, and %sock-connect-end ends it.
(defun rontolisp::%sock-connect-begin (fd host port)
  (rontolisp::%sock-check-host "%TCP-CONNECT-SOCKET" host)
  (rontolisp::%sock-check-port "%TCP-CONNECT-SOCKET" port)
  (let* ((e (rontolisp::%sock-unconnected fd))
         (r (rontolisp::%rltcp-begin (rontolisp::%sock-e-host e) host port)))
    (cond ((< r 0)
           (rontolisp::%sock-connect-fail host port
                                          (rontolisp::%rltcp-last-error)))
          ((= r 1)
           (rontolisp::%sock-now-connected e)
           t)
          (t nil))))

;; The connect %sock-connect-begin left going on, ended: FD, or the failure --
;; `Socket closed` for a socket closed meanwhile.
(defun rontolisp::%sock-connect-end (fd host port)
  (let ((e (rontolisp::%sock-entry fd)))
    (cond ((null e) (rontolisp::%sock-connect-fail host port "Socket closed"))
          ((< (rontolisp::%rltcp-end (rontolisp::%sock-e-host e)) 0)
           (rontolisp::%sock-connect-fail host port
                                          (rontolisp::%rltcp-last-error)))
          (t
           (rontolisp::%sock-now-connected e)
           fd))))

;; OPTION of the connected socket FD given to the host, VALUE its value, or for 5
;; the byte VALUE sent as urgent data after what is buffered
;; (LispNames.TCP_OPTION_INTERNAL). Answers nil.
(defun rontolisp::%tcp-option (fd option value)
  (let ((e (rontolisp::%sock-connected "%TCP-OPTION" fd)))
    (when (< (rontolisp::%rltcp-option (rontolisp::%sock-e-host e) option value)
             0)
      (rontolisp::%sock-host-fail "%tcp-option: "))
    nil))

;; Half of the connected socket FD ended (LispNames.TCP_SHUTDOWN_INTERNAL): with
;; OUTPUT its output, after what is buffered, else its input, whose reads answer
;; the end from now on. Answers nil.
(defun rontolisp::%tcp-shutdown (fd output)
  (let ((e (rontolisp::%sock-connected "%TCP-SHUTDOWN" fd)))
    (when (< (rontolisp::%rltcp-shutdown (rontolisp::%sock-e-host e)
                                         (if output 1 0)) 0)
      (rontolisp::%sock-host-fail "%tcp-shutdown: "))
    (unless output
      (rontolisp::%sock-set-buf e 0 0)
      (rontolisp::%sock-set-eof e))
    nil))

(defun rontolisp::%tcp-connect-socket (fd host port millis)
  ;; FD connected to HOST:PORT within MILLIS (nil or 0: as long as the host's
  ;; connect waits).
  (unless (or (null millis) (and (integerp millis) (>= millis 0)))
    (rontolisp::%sock-fail
     "%tcp-connect-socket: the limit must be nil or a non-negative integer"))
  (unless (rontolisp::%sock-connect-begin fd host port)
    (unless (rontolisp::%sock-wait (list fd)
                                   (if (and millis (> millis 0)) millis -1) nil)
      (rontolisp::%sock-connect-fail host port "Connect timed out"))
    (rontolisp::%sock-connect-end fd host port))
  fd)

;;; --- buffered reads: one host chunk per socket, walked by BYTE. OP names the
;;; public operator a host failure is reported under. ---

#+rontolisp-host-sockets
(defun rontolisp::%sock-fill (e op)
  ;; Refill the chunk buffer; nil (and the eof mark) at the peer's close. The wait
  ;; is the host's, for the entry's read deadline at most; the read then takes
  ;; what has come.
  (let ((host (rontolisp::%sock-e-host e))
        (deadline (rontolisp::%sock-e-deadline e))
        (buf (rontolisp::%sock-e-buf e)))
    (if (< (rontolisp::%sock-host-wait (rontolisp::%sock-hosts (list host))
                                       (if deadline deadline -1)) 0)
        (rontolisp::%sock-fail (concatenate 'string op ": Read timed out"))
        (let ((n (rontolisp::%rltcp-read host (length buf) buf)))
          (cond ((> n 0)
                 (rontolisp::%sock-set-buf e 0 n)
                 t)
                ((or (< n 0) (> (length (rontolisp::%rltcp-last-error)) 0))
                 (rontolisp::%sock-host-fail (concatenate 'string op ": ")))
                (t
                 (rontolisp::%sock-set-eof e)
                 nil))))))

#-rontolisp-host-sockets
(defun rontolisp::%sock-fill (e op)
  ;; Refill the chunk buffer; nil (and the eof mark) at the peer's close.
  (let ((n
         (rontolisp::%rltcp-read (rontolisp::%sock-e-host e)
                                 (rontolisp::%sock-e-buf e))))
    (if (< n 0)
        (rontolisp::%sock-host-fail (concatenate 'string op ": "))
        (if (> n 0)
            (progn
              (rontolisp::%sock-set-buf e 0 n)
              t)
            (progn
              (rontolisp::%sock-set-eof e)
              nil)))))

(defun rontolisp::%sock-buf-ready (e)
  (< (rontolisp::%sock-e-cursor e) (rontolisp::%sock-e-limit e)))

(defun rontolisp::%sock-pop-byte (e)
  (let* ((cursor (rontolisp::%sock-e-cursor e))
         (b (aref (rontolisp::%sock-e-buf e) cursor)))
    (rontolisp::%sock-set-buf e (+ cursor 1) (rontolisp::%sock-e-limit e))
    b))

;; The byte at the chunk's cursor, taken, or nil when the chunk holds none: a
;; read the chunk answers, in one walk of the entry.
(defun rontolisp::%sock-take-byte (e)
  (let* ((at (cdr (cdr e))) (more (cdr at)) (cursor (car more)))
    (declare (fixnum cursor))
    (if (< cursor (the fixnum (car (cdr more))))
        (let ((chunk (car at)))
          (declare (type (simple-array (unsigned-byte 8) (*)) chunk))
          (rplaca more (+ cursor 1))
          (aref chunk cursor))
        nil)))

(defun rontolisp::%sock-read-byte-f (e op)
  (let ((b (rontolisp::%sock-take-byte e)))
    (if b
        b
        (if (rontolisp::%sock-e-eof e)
            nil
            (if (rontolisp::%sock-fill e op)
                (rontolisp::%sock-pop-byte e)
                nil)))))

(defun rontolisp::%sock-read-char-f (e op)
  ;; One UTF-8 sequence assembled byte-wise (a refill may intervene between the
  ;; bytes); an invalid lead byte stands alone and decodes to whatever the
  ;; string layer's decoder yields for it.
  (let ((b0 (rontolisp::%sock-read-byte-f e op)))
    (if (null b0)
        nil
        (if (< b0 128)
            (code-char b0)
            (let ((n (if (< b0 192) 0 (if (< b0 224) 1 (if (< b0 240) 2 3))))
                  (acc (rontolisp::%str-from-byte b0)))
              (while (> n 0)
                (let ((bn (rontolisp::%sock-read-byte-f e op)))
                  (if (null bn)
                      (setq n 0)
                      (progn
                        ;; byte-level append: acc holds a PARTIAL UTF-8
                        ;; sequence, which the wrapped concatenate's
                        ;; character conversion would trap on
                        (setq acc
                         (%string-concat acc (rontolisp::%str-from-byte bn)))
                        (setq n (- n 1))))))
              (char acc 0))))))

(defun rontolisp::%sock-find-newline (chunk from limit)
  ;; The index of the first \n in chunk[from,limit), or nil.
  (declare (type (simple-array (unsigned-byte 8) (*)) chunk)
           (fixnum from limit))
  (let ((i from) (at nil))
    (declare (fixnum i))
    (while (and (< i limit) (null at))
      (if (= (aref chunk i) 10) (setq at i) (setq i (+ i 1))))
    at))

(defun rontolisp::%sock-join-runs (runs)
  ;; The strings of RUNS (in order) as one, by pairwise passes: a line that
  ;; spans many chunks costs O(total * log runs), where appending each to the
  ;; one before it copies the line so far every time.
  (cond ((null runs) "")
        ((null (cdr runs)) (car runs))
        (t (let ((out nil) (todo runs))
             (while todo
               (if (null (cdr todo))
                   (progn
                     (setq out (cons (car todo) out))
                     (setq todo nil))
                   (progn
                     (setq out
                      (cons (%string-concat (car todo) (car (cdr todo))) out))
                     (setq todo (cdr (cdr todo))))))
             (rontolisp::%sock-join-runs (nreverse out))))))

(defun rontolisp::%sock-read-line-f (e op)
  ;; Bytes up to \n (one trailing \r stripped, dropped at EOF too), assembled
  ;; into a string whose bytes ARE the wire bytes, a RUN of the chunk at a time
  ;; (sockets.lisp's, byte for byte). The second value is read-line's
  ;; missing-newline-p: t when the peer's close ended the line.
  (let ((runs nil) (got nil) (done nil) (cr nil) (newline nil))
    (while (not done)
      (if (rontolisp::%sock-buf-ready e)
          (let* ((chunk (rontolisp::%sock-e-buf e))
                 (from (rontolisp::%sock-e-cursor e))
                 (limit (rontolisp::%sock-e-limit e))
                 (nl (rontolisp::%sock-find-newline chunk from limit))
                 (stop (if nl nl limit)))
            (declare (type (simple-array (unsigned-byte 8) (*)) chunk)
                     (fixnum from limit stop))
            (setq got t)
            (rontolisp::%sock-set-buf e (if nl (+ stop 1) limit) limit)
            (if nl
                (progn
                  (setq newline t)
                  (setq done t)))
            (if (> stop from)
                (progn
                  (if cr (setq runs (cons (rontolisp::%str-from-byte 13) runs)))
                  (setq cr nil)
                  (if (= (aref chunk (- stop 1)) 13)
                      (progn
                        (setq cr t)
                        (setq stop (- stop 1))))
                  (if (> stop from)
                      (setq runs
                            (cons (rontolisp::%str-from-octets chunk from stop)
                                  runs))))))
          (if (or (rontolisp::%sock-e-eof e) (not (rontolisp::%sock-fill e op)))
              (setq done t))))
    (if got
        (%quiet-values (rontolisp::%sock-join-runs (nreverse runs))
                       (not newline))
        (%quiet-values nil t))))

(defun rontolisp::%sock-take-chunk (e seq start end)
  ;; Copies what the chunk holds into seq[start,end), as far as it goes: the
  ;; index past the last element stored. replace moves two octet vectors in one
  ;; array.copy (.kb/sequence-op-runtimes.md).
  (let* ((buf (rontolisp::%sock-e-buf e))
         (cursor (rontolisp::%sock-e-cursor e))
         (limit (rontolisp::%sock-e-limit e))
         (n (min (- end start) (- limit cursor))))
    (replace seq buf :start1 start :end1 (+ start n) :start2 cursor)
    (rontolisp::%sock-set-buf e (+ cursor n) limit)
    (+ start n)))

(defun rontolisp::%sock-read-seq-f (e seq start end)
  ;; Fill seq[start,end) with wire bytes, a chunk at a time; answers the index
  ;; of the first element NOT updated (CL read-sequence's value), so a short
  ;; read at EOF stays visible.
  (let ((i start) (stop nil))
    (while (and (< i end) (not stop))
      (if (rontolisp::%sock-buf-ready e)
          (setq i (rontolisp::%sock-take-chunk e seq i end))
          (if (or (rontolisp::%sock-e-eof e)
                  (not (rontolisp::%sock-fill e "read-sequence")))
              (setq stop t))))
    i))

;;; --- writes: the host takes the bytes whole into the socket's output buffer,
;;; blocking only when it sends. A string crosses as its raw bytes, an octet
;;; vector as it is. ---

(defun rontolisp::%sock-write-octets (e octets op)
  (when (< (rontolisp::%rltcp-write (rontolisp::%sock-e-host e) octets) 0)
    (rontolisp::%sock-host-fail (concatenate 'string op ": ")))
  octets)

(defun rontolisp::%sock-write-string (e s op)
  ;; The byte-length guard, not the char count: a %str-from-byte payload whose
  ;; byte is a UTF-8 continuation value has char count 0 but must still go out.
  (when (> (rontolisp::%str-byte-length s) 0)
    (rontolisp::%sock-write-octets e s op))
  s)

;; What characters leave of the line (.kb/standard-output-redirect.md, "A stream's
;; line start"). An octet moves nothing, so %io-write-byte has no call of it.
(defun rontolisp::%sock-track-line (e s)
  (let ((n (rontolisp::%str-byte-length s)))
    (if (> n 0)
        (rontolisp::%sock-set-mid-line e
         (not (= (rontolisp::%str-byte-ref s (- n 1)) 10))))))

(defun rontolisp::%sock-seq-octets (seq start end)
  ;; One write payload from seq[start,end): a STRING sequence goes out as its
  ;; own characters, any other vector one raw wire byte per element.
  (if (stringp seq)
      (subseq seq start end)
      (let ((v (make-array (- end start) :element-type '(unsigned-byte 8)))
            (i start))
        (while (< i end)
          (setf (aref v (- i start)) (aref seq i))
          (setq i (+ i 1)))
        v)))

;;; --- the %io-* dispatch defuns (the compiler rewrite's targets) and their
;;; %...-future twins. The socket arm is the blocking read above; a non-socket
;;; designator falls through to the %...-raw alias of the NATIVE built-in,
;;; byte-identical to the unrewritten form. The designator is resolved through
;;; %sock-handle before the table test, so a *standard-input* bound to a socket
;;; reaches the socket (sockets.lisp's rule); the raw arm gets the ORIGINAL
;;; argument, so its own designator defaulting is untouched. ---

(defun rontolisp::%read-line-future (&optional s)
  (let ((e (rontolisp::%sock-entry (or s *standard-input*))))
    (if e
        (rontolisp::%sock-read-line-f e "read-line")
        (rontolisp::%read-line-raw s))))

(defun rontolisp::%read-char-future (&optional s)
  ;; A bare (read-char s) carries CL's default eof-error-p t, so the socket arm
  ;; turns the read's nil-at-EOF into the end-of-file signal every backend
  ;; gives; the eof-carrying shape below applies the caller's arguments instead.
  (let ((e (rontolisp::%sock-entry (or s *standard-input*))))
    (if e
        (let ((c (rontolisp::%sock-read-char-f e "read-char")))
          (if c c (error 'end-of-file)))
        (rontolisp::%read-char-raw s))))

(defun rontolisp::%read-byte-future (&optional s)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((b (rontolisp::%sock-read-byte-f e "read-byte")))
          (if b b (error 'end-of-file)))
        (rontolisp::%read-byte-raw s))))

(defun rontolisp::%io-read-line (&optional s) (rontolisp::%read-line-future s))

(defun rontolisp::%io-read-char (&optional s) (rontolisp::%read-char-future s))

(defun rontolisp::%io-read-byte (&optional s) (rontolisp::%read-byte-future s))

(defun rontolisp::%read-byte-eof-future (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((b (rontolisp::%sock-read-byte-f e "read-byte")))
          (if b b (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp::%read-byte-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-byte-eof (s eof-error-p &optional eof-value)
  (rontolisp::%read-byte-eof-future s eof-error-p eof-value))

(defun rontolisp::%read-char-eof-future (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry (or s *standard-input*))))
    (if e
        (let ((c (rontolisp::%sock-read-char-f e "read-char")))
          (if c c (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp::%read-char-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-char-eof (s eof-error-p &optional eof-value)
  (rontolisp::%read-char-eof-future s eof-error-p eof-value))

(defun rontolisp::%read-line-eof-future (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry (or s *standard-input*))))
    (if e
        (let* ((vals
                (%quiet-multiple-value-list
                 (rontolisp::%sock-read-line-f e "read-line")))
               (l (car vals)))
          (if l
              (%quiet-values l (car (cdr vals)))
              (%quiet-values (if eof-error-p (error 'end-of-file) eof-value)
                             t)))
        (rontolisp::%read-line-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-line-eof (s eof-error-p &optional eof-value)
  (rontolisp::%read-line-eof-future s eof-error-p eof-value))

;; The sequence ops carry :start / :end all the way down (the shape the
;; Gray-streams fall-through arm emits). Only the SOCKET arm resolves a nil
;; :end to (length seq); the native arm takes nil as "the whole buffer", which
;; is what lets a rank-2 packed float array be read in bulk.
(defun rontolisp::%read-sequence-future (seq s &optional start end)
  (let ((a (if start start 0)))
    (let ((e (rontolisp::%sock-entry s)))
      (if e
          (rontolisp::%sock-read-seq-f e seq a (if end end (length seq)))
          (rontolisp::%read-sequence-raw seq s :start a :end end)))))

(defun rontolisp::%io-read-sequence (seq s &optional start end)
  (rontolisp::%read-sequence-future seq s start end))

(defun rontolisp::%io-write-sequence (seq s &optional start end)
  (let ((a (if start start 0)))
    (let ((e (rontolisp::%sock-entry s)))
      (if e
          (let ((octets
                 (rontolisp::%sock-seq-octets seq a (if end end (length seq)))))
            (rontolisp::%sock-write-octets e octets "write-sequence")
            (if (stringp seq) (rontolisp::%sock-track-line e octets))
            seq)
          (rontolisp::%write-sequence-raw seq s :start a :end end)))))

(defun rontolisp::%io-write-line (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          ;; %string-concat: s can hold raw wire bytes (a binary line read back
          ;; off a socket), which the wrapped concatenate's character
          ;; conversion would trap on or re-encode
          (rontolisp::%sock-write-string e
           (%string-concat s (%princ-piece #\Newline)) "write-line")
          (rontolisp::%sock-set-mid-line e nil)
          s)
        (rontolisp::%write-line-raw s stream))))

(defun rontolisp::%io-write-string (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (rontolisp::%sock-write-string e s "write-string")
          (rontolisp::%sock-track-line e s)
          s)
        (rontolisp::%write-string-raw s stream))))

(defun rontolisp::%io-fresh-line (&optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (if (rontolisp::%sock-e-mid-line e)
            (progn
              (rontolisp::%sock-write-string e (%princ-piece #\Newline)
                                             "write-string")
              (rontolisp::%sock-set-mid-line e nil)
              t)
            nil)
        (rontolisp::%fresh-line-raw stream))))

(defun rontolisp::%io-write-byte (b stream)
  ;; %str-from-byte, never code-char: a code point >= 128 is a TWO-byte UTF-8
  ;; sequence, and the wire wants the one byte.
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (rontolisp::%sock-write-string e (rontolisp::%str-from-byte b)
                                         "write-byte")
          b)
        (rontolisp::%write-byte-raw b stream))))

(defun rontolisp::%sock-readable (e)
  ;; Whether bytes wait in the kernel's receive buffer (the runner's FIONREAD,
  ;; the interpreter's available()): never blocks, consumes nothing.
  (let ((r (rontolisp::%rltcp-readable (rontolisp::%sock-e-host e))))
    (if (< r 0) (rontolisp::%sock-host-fail "listen: ") (= r 1))))

;; What has come of the connected socket FD into the octet vector OCTETS from
;; START below END (LispNames.TCP_READ_SOME_INTERNAL): with WAIT the first chunk
;; is waited for, then the bytes the kernel already holds are taken. The index
;; past the last octet stored; START at the end, or with no WAIT when nothing has
;; come.
(defun rontolisp::%tcp-read-some (fd octets start end wait)
  (let ((e (rontolisp::%sock-connected "%TCP-READ-SOME" fd))
        (at start)
        (going t))
    (if (and wait (< at end) (not (rontolisp::%sock-buf-ready e))
             (not (rontolisp::%sock-e-eof e)))
        (rontolisp::%sock-fill e "read-sequence"))
    (while (and going (< at end))
      (if (rontolisp::%sock-buf-ready e)
          (setq at (rontolisp::%sock-take-chunk e octets at end))
          (setq going
                (and (not (rontolisp::%sock-e-eof e))
                     (rontolisp::%sock-readable e)
                     (rontolisp::%sock-fill e "read-sequence")))))
    at))

(defun rontolisp::%io-listen (&optional s)
  ;; A connected socket answers whether a read would hand bytes over at once:
  ;; bytes left in the chunk, else bytes waiting in the kernel's receive buffer
  ;; (%sock-readable), the interpreter's and the JVM's answer. nil at the peer's
  ;; close and for a listener, as on the component. A non-socket designator falls
  ;; through to the native listen under its %listen-raw alias.
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (if (= (rontolisp::%sock-e-kind e) 1)
            (if (rontolisp::%sock-buf-ready e)
                t
                (if (rontolisp::%sock-e-eof e)
                    nil
                    (rontolisp::%sock-readable e)))
            nil)
        (rontolisp::%listen-raw s))))

#+rontolisp-host-sockets
(defun rontolisp::%sock-hosts (hosts)
  ;; The host numbers HOSTS as the octets the host takes them in: four each, least
  ;; significant first.
  (let ((octets
         (make-array (* 4 (length hosts)) :element-type '(unsigned-byte 8)))
        (at 0))
    (dolist (host hosts)
      (setf (aref octets at) (logand host 255))
      (setf (aref octets (+ at 1)) (logand (ash host -8) 255))
      (setf (aref octets (+ at 2)) (logand (ash host -16) 255))
      (setf (aref octets (+ at 3)) (logand (ash host -24) 255))
      (setq at (+ at 4)))
    octets))

;; How many waits of no time in a row found nothing: what the host has for the
;; module comes while the module is suspended in it, so a program that only ever
;; asks with no wait -- a thread turning while another waits for a socket -- is
;; suspended once in so many.
#+rontolisp-host-sockets (defvar rontolisp::*sock-idle-polls* 0)

#+rontolisp-host-sockets
(defun rontolisp::%sock-host-wait (octets millis)
  ;; The index of the first of the host's sockets OCTETS names that has something,
  ;; waited for until MILLIS have passed (0: no wait, -1: no limit), or -1.
  (let ((at (rontolisp::%rltcp-poll octets)))
    (cond ((>= at 0)
           (setq rontolisp::*sock-idle-polls* 0)
           at)
          ((and (= millis 0)
                (< (setq rontolisp::*sock-idle-polls*
                         (+ rontolisp::*sock-idle-polls* 1)) 256))
           -1)
          (t
           (setq rontolisp::*sock-idle-polls* 0)
           (rontolisp::%rltcp-wait octets millis)))))

#+rontolisp-host-sockets
(defun rontolisp::%sock-wait (fds millis server)
  ;; The runner's below, the wait the host's: until one of the sockets FDS has
  ;; something for a read -- bytes in its chunk or in the host, the peer's close, a
  ;; failure, a connect ended, or no entry any more (closed) -- or MILLIS have
  ;; passed (0: no wait, -1: no limit): the index of the first that has, else nil.
  ;; A host that calls the module for each request has no SERVER to wait for.
  (let ((i 0) (found nil) (rest fds))
    (while (and rest (null found))
      (let ((e (rontolisp::%sock-entry (car rest))))
        (if (or (null e)
                (and (= (rontolisp::%sock-e-kind e) 1)
                     (or (rontolisp::%sock-buf-ready e)
                         (rontolisp::%sock-e-eof e))))
            (setq found i)))
      (setq i (+ i 1))
      (setq rest (cdr rest)))
    (if found
        found
        (let ((hosts nil))
          (dolist (fd fds)
            (setq hosts
                  (cons (rontolisp::%sock-e-host (rontolisp::%sock-entry fd))
                        hosts)))
          (let ((r
                 (rontolisp::%sock-host-wait
                  (rontolisp::%sock-hosts (nreverse hosts))
                  (rontolisp::%sock-millis millis))))
            (if (< r 0) nil r))))))

#-rontolisp-host-sockets
(defun rontolisp::%sock-wait (fds millis server)
  ;; Waits until one of the sockets FDS has something for a read or an accept --
  ;; bytes in its chunk or in the kernel, the peer's close, a connection, a
  ;; failure, or no entry any more (closed) -- or SERVER, a server of the
  ;; runner's (http-serve.lisp) or nil, has its next request, or MILLIS have
  ;; passed (0: no wait, -1: no limit): the index of the first socket that has,
  ;; the length of FDS for the request, else nil. The Java subset's scheduler
  ;; waits here for the sockets its threads wait on and its server's requests.
  (let ((i 0) (found nil) (rest fds))
    (while (and rest (null found))
      (let ((e (rontolisp::%sock-entry (car rest))))
        (if (or (null e)
                (and (= (rontolisp::%sock-e-kind e) 1)
                     (or (rontolisp::%sock-buf-ready e)
                         (rontolisp::%sock-e-eof e))))
            (setq found i)))
      (setq i (+ i 1))
      (setq rest (cdr rest)))
    (if found
        found
        (let ((hosts
               (make-array (* 4 (length fds)) :element-type '(unsigned-byte 8)))
              (at 0))
          (dolist (fd fds)
            (let ((host (rontolisp::%sock-e-host (rontolisp::%sock-entry fd))))
              (setf (aref hosts at) (logand host 255))
              (setf (aref hosts (+ at 1)) (logand (ash host -8) 255))
              (setf (aref hosts (+ at 2)) (logand (ash host -16) 255))
              (setf (aref hosts (+ at 3)) (logand (ash host -24) 255))
              (setq at (+ at 4))))
          (let ((r
                 (rontolisp::%rltcp-wait hosts (or server -1)
                                         (rontolisp::%sock-millis millis))))
            (if (< r 0) nil r))))))

(defun rontolisp::%io-open-stream-p (s)
  ;; A socket entry lives in *sock-table* until %io-close drops it; a handle in
  ;; the socket fd range with no entry is a socket that has BEEN CLOSED.
  (if (rontolisp::%sock-entry s)
      t
      (let ((h (rontolisp::%sock-handle s)))
        (if (and (integerp h) (>= h 200) (< h rontolisp::*sock-next-fd*))
            nil
            (rontolisp::%open-stream-p-raw s)))))

(defun rontolisp::%io-close (stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (rontolisp::%rltcp-close (rontolisp::%sock-e-host e))
          (remhash (rontolisp::%sock-handle stream) rontolisp::*sock-table*)
          (setq rontolisp::*sock-last-stream* nil)
          (setq rontolisp::*sock-last-entry* nil)
          t)
        (rontolisp::%close-raw stream))))

;;; force-output and finish-output: a socket's output buffer lives in the host,
;;; which sends it and blocks until the kernel has the bytes.
(defun rontolisp::%sock-send-buffered (e op)
  ;; A listener has nothing to send, as the interpreter's has not.
  (when (and (= (rontolisp::%sock-e-kind e) 1)
             (< (rontolisp::%rltcp-flush (rontolisp::%sock-e-host e)) 0))
    (rontolisp::%sock-host-fail (concatenate 'string op ": ")))
  nil)

(defun rontolisp::%io-force-output (&optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (rontolisp::%sock-send-buffered e "force-output")
        (rontolisp::%force-output-raw stream))))

(defun rontolisp::%io-finish-output (&optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (rontolisp::%sock-send-buffered e "finish-output")
        (rontolisp::%force-output-raw stream))))
