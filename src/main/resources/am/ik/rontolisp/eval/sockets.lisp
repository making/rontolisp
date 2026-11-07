;;;; sockets.lisp -- rontolisp:tcp-* over wit-imported wasi:sockets@0.3.0.
;;;;
;;;; This is the --component implementation (spliced by eval/SocketsLibrary when a
;;;; --component program references a rontolisp:tcp-* built-in or the usocket
;;;; package). The interpreter and the JVM keep their java.net.Socket
;;;; implementations; Preview 1 keeps the compile error (no host sockets).
;;;;
;;;; The whole of it is ordinary Lisp over the WIT bindings -- no core codegen and
;;;; no hand-written adapter (this file replaced adapter-sockets.wat). Mechanics:
;;;;   - `connect` is an `async func`, so its binding returns a FIRST-CLASS FUTURE;
;;;;     the synchronous tcp-connect surface forces it with rontolisp::%future-force
;;;;     (the blocking scheduler drive), and async bodies get the await-shaped
;;;;     promotion instead (the compile-time rewrite in the WASM compiler).
;;;;   - `listen` returns a stream<tcp-socket>; accept = one element read of that
;;;;     stream through the accept-stream alias built-in (a 4-byte handle element,
;;;;     the one non-u8 stream lift).
;;;;   - a connected socket's READ half is plumbed eagerly like the old adapter:
;;;;     receive() yields the recv stream (its future dropped immediately -- EOF
;;;;     is the stream status). The WRITE half is deferred to the first write
;;;;     (%sock-ensure-tx): a fresh stream.new pair's read end goes to send()
;;;;     (called at most once) and the write end stays for the write built-ins.
;;;;     The deferral is what lets tls.lisp's tls-upgrade route send() through
;;;;     the wasi:tls transform instead.
;;;;   - the socket HANDLE is an integer >= 200 in the file-stream handle space;
;;;;     the entry lives in the Lisp-side *sock-table*. The stream built-ins
;;;;     (read-line/read-byte/read-char/write-line/write-byte/close, plus
;;;;     read-sequence/write-sequence and the eof-tolerant 2-3-arg read-byte)
;;;;     reach it through the %io-* dispatch defuns the compiler rewrite
;;;;     targets; reads buffer one host chunk per socket (a documented
;;;;     divergence from the byte-at-a-time interpreter/JVM reads), and the
;;;;     chunk bookkeeping walks the chunk's BYTES -- the wire truth, a packed
;;;;     (unsigned-byte 8) vector -- with aref, never UTF-8-decoded characters
;;;;     (%str-byte-length still guards the WRITE side, where a payload is a
;;;;     string whose bytes are the wire's).
;;;;   - a READ DEADLINE (tcp-set-timeout) has no host knob -- wasi:sockets@0.3.0
;;;;     has no receive timeout -- so a read the host cannot answer at once races a
;;;;     wait-for timer (%future-race), and the loser is WITHDRAWN
;;;;     (stream.cancel-read). Nothing is ever left in flight on a recv stream:
;;;;     the same withdrawal is what makes listen a real probe (start a read,
;;;;     take it back when the host has nothing) and what lets close drop a stream
;;;;     another task is still reading.
;;;;   - a TLS entry (tls.lisp) BUFFERS its writes and sends them at a read,
;;;;     listen, force-output / finish-output, close or a full buffer: the host's
;;;;     wasi:tls keeps back the tail of unbuffered writes ("the TLS output
;;;;     buffer", below).

;; The WIT interfaces are lowered by SocketsLibrary (which calls
;; WitImportDirective.lower itself), so these directives never reach
;; WitImportInliner -- but they are written here, in sockets.lisp's own source, so
;; the file reads as the program it is.
(rontolisp:wit-import "sockets.wit"
                      :interface "wasi:sockets/types@0.3.0"
                      :package %sock)

(rontolisp:wit-import "sockets.wit"
                      :interface "wasi:sockets/ip-name-lookup@0.3.0"
                      :package %sock-dns)

;;; --- the socket table: fd -> (socket kind recv tx buf cursor eof mid-line
;;; deadline out out-fill flushing) ---
;;; kind 1 = connected, 2 = listener; recv = the recv byte stream (or, for a
;;; listener, the accept stream); tx = the write end of the send pair; mid-line =
;;; whether the characters written so far left the line open; deadline = the read
;;; deadline in milliseconds, or nil; out = the output buffer of a TLS entry (nil
;;; for a plain socket, which writes through), out-fill = its byte count,
;;; flushing = whether a flush has a piece in flight.

(defvar rontolisp::*sock-table* (make-hash-table))
(defvar rontolisp::*sock-next-fd* 200)

;; The raw handle a stream designator names: an OPEN stream is a VALUE carrying its
;; handle in slot 0, and a synonym stream forwards to whatever its variable holds
;; now. This is the prelude's %stream-target written out of the %obj-* primitives,
;; deliberately: sockets.lisp is spliced by pipelines that do not all run the
;; prelude selection, and every socket operation goes through %sock-entry.
(defun rontolisp::%sock-handle (s)
  (if (%obj-is s '%SYNONYM-STREAM)
      (rontolisp::%sock-handle (funcall (%obj-ref s 1)))
      (if (%obj-is s '%STREAM) (%obj-ref s 0) s)))

;; The table key is the RAW handle, so the designator is resolved first.
(defun rontolisp::%sock-entry (fd)
  (let ((h (rontolisp::%sock-handle fd)))
    (if (integerp h) (gethash h rontolisp::*sock-table*) nil)))

(defun rontolisp::%sock-handle-p (fd) (if (rontolisp::%sock-entry fd) t nil))

(defun rontolisp::%sock-e-sock (e) (car e))
(defun rontolisp::%sock-e-kind (e) (car (cdr e)))
(defun rontolisp::%sock-e-recv (e) (car (cdr (cdr e))))
(defun rontolisp::%sock-e-tx (e) (car (cdr (cdr (cdr e)))))
(defun rontolisp::%sock-e-buf (e) (car (cdr (cdr (cdr (cdr e))))))
(defun rontolisp::%sock-e-cursor (e) (car (cdr (cdr (cdr (cdr (cdr e)))))))
(defun rontolisp::%sock-e-eof (e) (car (cdr (cdr (cdr (cdr (cdr (cdr e))))))))
(defun rontolisp::%sock-e-mid-line (e)
  (car (cdr (cdr (cdr (cdr (cdr (cdr (cdr e)))))))))

(defun rontolisp::%sock-e-deadline (e)
  (car (cdr (cdr (cdr (cdr (cdr (cdr (cdr (cdr e))))))))))

(defun rontolisp::%sock-set-mid-line (e mid-line)
  (rplaca (cdr (cdr (cdr (cdr (cdr (cdr (cdr e))))))) mid-line))

(defun rontolisp::%sock-set-deadline (e ms)
  (rplaca (cdr (cdr (cdr (cdr (cdr (cdr (cdr (cdr e)))))))) ms))

(defun rontolisp::%sock-set-buf (e chunk cursor)
  (rplaca (cdr (cdr (cdr (cdr e)))) chunk)
  (rplaca (cdr (cdr (cdr (cdr (cdr e))))) cursor))

(defun rontolisp::%sock-set-eof (e)
  (rplaca (cdr (cdr (cdr (cdr (cdr (cdr e)))))) t))

(defun rontolisp::%sock-set-tx (e tx) (rplaca (cdr (cdr (cdr e))) tx))

(defun rontolisp::%sock-e-out (e) (car (nthcdr 9 e)))
(defun rontolisp::%sock-e-out-fill (e) (car (nthcdr 10 e)))
(defun rontolisp::%sock-e-flushing (e) (car (nthcdr 11 e)))
(defun rontolisp::%sock-set-out-fill (e n) (rplaca (nthcdr 10 e) n))
(defun rontolisp::%sock-set-flushing (e flag) (rplaca (nthcdr 11 e) flag))

(defun rontolisp::%sock-set-streams (e recv tx)
  ;; tls.lisp's tls-upgrade swaps the entry onto the cleartext stream ends IN
  ;; PLACE -- same fd before and after the upgrade (the component divergence
  ;; from the interpreter/JVM's new-handle convention, .kb/tcp-sockets.md), so
  ;; every stream built-in keeps dispatching on it unchanged.
  (rplaca (cdr (cdr e)) recv)
  (rontolisp::%sock-set-tx e tx))

;; Answers the stream VALUE over the freshly-minted handle: a socket is a stream
;; like any other here, so streamp / (typep s 'stream) answer for it exactly as
;; they do on the interpreter and the JVM. The TABLE is still keyed by the raw
;; handle -- %sock-entry resolves a designator down to it.
(defun rontolisp::%sock-register (sock kind recv tx)
  (let ((fd rontolisp::*sock-next-fd*))
    (setq rontolisp::*sock-next-fd* (+ fd 1))
    (setf (gethash fd rontolisp::*sock-table*)
          (list sock kind recv tx nil 0 nil nil nil nil 0 nil))
    (%obj-new '%STREAM fd (if (= kind 2) :socket-server :socket))))

;;; --- addresses: IPv4 literals parsed here, every other name looked up ---

(defun rontolisp::%sock-parse-ipv4 (host)
  ;; "a.b.c.d" -> (a b c d), or nil when the string is not a dotted quad. A plain
  ;; char-code walk (the url.lisp idiom): octet accumulator + dot counter, bad
  ;; characters and out-of-range octets bail to nil.
  (let ((len (length host)) (i 0) (n 0) (digits 0) (octets nil) (bad nil))
    (if (= len 0)
        nil
        (progn
          (while (< i len)
            (let ((c (char-code (char host i))))
              (if (= c 46)
                  (if (or (= digits 0) (> n 255))
                      (progn
                        (setq bad t)
                        (setq i len))
                      (progn
                        (setq octets (cons n octets))
                        (setq n 0)
                        (setq digits 0)))
                  (let ((d (- c 48)))
                    (if (or (< d 0) (> d 9) (> digits 2))
                        (progn
                          (setq bad t)
                          (setq i len))
                        (progn
                          (setq n (+ (* n 10) d))
                          (setq digits (+ digits 1))))))
              (setq i (+ i 1))))
          (if (or bad (= digits 0) (> n 255))
              nil
              (let ((quad (reverse (cons n octets))))
                (if (= (length quad) 4) quad nil)))))))

(rontolisp:async-defun rontolisp::%sock-addr-f (host port)
  ;; The ip-socket-address of HOST:PORT for tcp-socket-connect / -bind. An IPv4
  ;; literal, and localhost, is parsed here and needs no lookup permission; any
  ;; other name goes to wasi:sockets/ip-name-lookup (wasmtime's
  ;; -S allow-ip-name-lookup=y), and the first IPv4 address in the resolver's
  ;; preference order is the one taken -- the address the JDK's InetAddress
  ;; picks on the interpreter and the JVM, IPv4 first. The sockets here are IPv4
  ;; only, so a name with no IPv4 address fails like a lookup error: a wit-error,
  ;; which the tcp-* surface turns into nil.
  (let ((quad
         (rontolisp::%sock-parse-ipv4
          (if (string= host "localhost") "127.0.0.1" host))))
    (if quad
        (cons :ipv4 (list :port port :address quad))
        (let ((addrs (rontolisp:await (%sock-dns:resolve-addresses host))))
          ;; An ip-address lifts as (:IPV4 a b c d) / (:IPV6 h ...); this file's
          ;; :ipv4 reads :IPV4 under the reader's upcase premise.
          (while (and addrs (not (eq (car (car addrs)) :ipv4)))
            (setq addrs (cdr addrs)))
          (if addrs
              (cons :ipv4 (list :port port :address (cdr (car addrs))))
              (error 'rontolisp:wit-error
                     :payload :name-unresolvable
                     :message (concatenate 'string "tcp: no IPv4 address for "
                                           host)))))))

;;; --- plumbing (the old adapter's $plumb): the recv stream eagerly, the send
;;; half DEFERRED to the first write ---

(defun rontolisp::%sock-plumb (sock kind)
  ;; receive() is taken eagerly (its future dropped immediately -- EOF is the
  ;; stream status). The send half is deferred to %sock-ensure-tx at the first
  ;; write: tcp-socket.send may be called at most once, and deferring it is
  ;; what leaves room for tls.lisp's tls-upgrade to interpose the wasi:tls
  ;; transform between the cleartext writes and the wire. The peer sees no
  ;; difference -- nothing went out before the first write either way.
  (let* ((rpair (%sock:tcp-socket-receive sock)) (recv (car rpair)))
    (%sock:sock-future-drop-readable (car (cdr rpair)))
    (rontolisp::%sock-register sock kind recv nil)))

(defun rontolisp::%sock-ensure-tx (e)
  ;; First write: make the write pair and hand its read end to the (at most
  ;; once) tcp-socket.send. No await sits between the test and the store, so
  ;; the deferral is atomic under the single-threaded scheduler -- two tasks
  ;; sharing one socket cannot both call send.
  (let ((tx (rontolisp::%sock-e-tx e)))
    (if tx
        tx
        (let ((spair (%sock:sock-stream-new)))
          (%sock:sock-future-drop-readable
           (%sock:tcp-socket-send (rontolisp::%sock-e-sock e) (car spair)))
          (rontolisp::%sock-set-tx e (cdr spair))
          (cdr spair)))))

;;; --- the tcp-* surface (sync wrappers force; async bodies are rewritten onto
;;; the %...-future internals by the compiler's promotion pass) ---

(rontolisp:async-defun rontolisp::%tcp-connect-f (host port)
  ;; Failure (unreachable host, refused connection, a name that does not
  ;; resolve) yields nil, the WASM error convention -- matching fetch. The nil
  ;; convention lives HERE so the sync surface (a %future-force of this) and an
  ;; async body's promoted (await (%tcp-connect-f ...)) behave identically.
  (handler-case (let* ((addr
                        (rontolisp:await (rontolisp::%sock-addr-f host port)))
                       (sock (%sock:tcp-socket-create :ipv4)))
                  (rontolisp:await (%sock:tcp-socket-connect sock addr))
                  (rontolisp::%sock-plumb sock 1))
    (rontolisp:wit-error () nil)))

(defun rontolisp:tcp-connect (host port)
  (rontolisp::%future-force (rontolisp::%tcp-connect-f host port)))

(defun rontolisp:tcp-listen (port &optional host)
  (handler-case (let ((sock (%sock:tcp-socket-create :ipv4)))
                  (%sock:tcp-socket-bind sock
                                         (rontolisp::%future-force
                                          (rontolisp::%sock-addr-f
                                           (if host host "0.0.0.0") port)))
                  (rontolisp::%sock-register sock 2
                                             (%sock:tcp-socket-listen sock)
                                             nil))
    (rontolisp:wit-error () nil)))

(rontolisp:async-defun rontolisp::%tcp-accept-f (listener)
  ;; A non-listener handle (or a host error) yields nil, like the old adapter's
  ;; errno convention.
  (handler-case (let ((e (rontolisp::%sock-entry listener)))
                  (if (and e (= (rontolisp::%sock-e-kind e) 2))
                      (let ((sock
                             (rontolisp:await
                              (%sock:accept-stream-read
                               (rontolisp::%sock-e-recv e)))))
                        (if sock (rontolisp::%sock-plumb sock 1) nil))
                      nil))
    (rontolisp:wit-error () nil)))

(defun rontolisp:tcp-accept (listener)
  (rontolisp::%future-force (rontolisp::%tcp-accept-f listener)))

(defun rontolisp::%sock-local-address (fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if e
        (handler-case (%sock:tcp-socket-get-local-address
                       (rontolisp::%sock-e-sock e))
          (rontolisp:wit-error () nil))
        nil)))

(defun rontolisp::%sock-remote-address (fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if e
        (handler-case (%sock:tcp-socket-get-remote-address
                       (rontolisp::%sock-e-sock e))
          (rontolisp:wit-error () nil))
        nil)))

(defun rontolisp::%sock-format-quad (quad)
  (concatenate 'string (rontolisp::%sock-octet-string (car quad)) "."
               (rontolisp::%sock-octet-string (car (cdr quad))) "."
               (rontolisp::%sock-octet-string (car (cdr (cdr quad)))) "."
               (rontolisp::%sock-octet-string (car (cdr (cdr (cdr quad)))))))

(defun rontolisp::%sock-octet-string (n)
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

(defun rontolisp::%sock-addr-string (addr)
  ;; (:ipv4 . (:port N :address (a b c d))) -> "a.b.c.d"; ipv6 unsupported -> nil.
  ;; The lifted variant tag reads UPCASED (:IPV4), and this file's own :ipv4 reads
  ;; :IPV4 too under the reader's upcase premise, so one upcased arm suffices.
  (if (and (consp addr) (eq (car addr) :ipv4))
      (rontolisp::%sock-format-quad (getf (cdr addr) :ADDRESS))
      nil))

(defun rontolisp:tcp-local-port (fd)
  (let ((addr (rontolisp::%sock-local-address fd)))
    (if (consp addr) (getf (cdr addr) :PORT) nil)))

(defun rontolisp:tcp-local-address (fd)
  (rontolisp::%sock-addr-string (rontolisp::%sock-local-address fd)))

(defun rontolisp:tcp-peer-address (fd)
  (rontolisp::%sock-addr-string (rontolisp::%sock-remote-address fd)))

(defun rontolisp:tcp-peer-port (fd)
  (let ((addr (rontolisp::%sock-remote-address fd)))
    (if (consp addr) (getf (cdr addr) :PORT) nil)))

(defun rontolisp:tcp-set-timeout (fd ms)
  ;; The socket's READ deadline: a read waiting longer than MS milliseconds for
  ;; its next chunk signals `OP: Read timed out`, the SO_TIMEOUT text of the
  ;; interpreter and the JVM, and leaves the socket usable. nil clears it, and so
  ;; does 0 (SO_TIMEOUT's "none"). It lives in the entry -- wasi:sockets@0.3.0 has
  ;; no receive timeout -- and %sock-fill enforces it by racing the read against a
  ;; wait-for timer; tls.lisp's handshake honours it the same way. A handle that
  ;; is no connected socket answers nil (the WASM error convention).
  (unless (or (null ms) (and (integerp ms) (>= ms 0)))
    (error
     "tcp-set-timeout: the deadline must be nil or a non-negative integer"))
  (let ((e (rontolisp::%sock-entry fd)))
    (if (and e (= (rontolisp::%sock-e-kind e) 1))
        (progn
          (rontolisp::%sock-set-deadline e
           (if (and ms (> ms 0)) (min ms 2147483647) nil))
          ms)
        nil)))

;;; --- the future race: the deadline substrate. A read and a TLS handshake are
;;; futures, and so is a wait-for timer; a deadline is the race of the two. ---

(defun rontolisp::%future-race (futures)
  ;; A future settling to the INDEX of the first of FUTURES to settle, fulfilled
  ;; or rejected. A value that is no future, or a future settled already, settles
  ;; it at once, the first such in list order winning. The index and not the
  ;; value, because a winner may answer nil (a timer always does, a read at its
  ;; end too) and its error is its own: the caller awaits the winner for either.
  ;; The losers run on -- the race only stops listening to them -- so a loser
  ;; holding a host operation is the caller's to withdraw. One arm per future, an
  ;; async-lambda awaiting it: the scheduler's own waiter machinery wakes the
  ;; race, task ownership included.
  (let ((race (rontolisp::%future-new)) (i 0))
    (while futures
      (rontolisp::%future-race-arm race (car futures) i)
      (setq futures (cdr futures))
      (setq i (+ i 1)))
    race))

(defun rontolisp::%future-race-arm (race f i)
  (funcall
   (rontolisp:async-lambda ()
     (handler-case (rontolisp:await f) (condition () nil))
     (rontolisp::%future-settle race i))))

;;; --- buffered reads (chunked; the %...-future internals are async-defuns so an
;;; async body's promoted read suspends instead of blocking the task). A chunk
;;; is a packed (unsigned-byte 8) vector holding exactly the bytes the host
;;; delivered, and the cursor walks those bytes with aref -- never characters:
;;; a decoded chunk would collapse binary bytes that happen to form a valid
;;; multi-byte sequence (a PostgreSQL BackendKeyData secret, say) into one char
;;; and shift everything after them. OP names the public operator a timed-out
;;; read is reported under. ---

(rontolisp:async-defun rontolisp::%sock-fill (e op)
  ;; Refill the chunk buffer from the recv stream; nil (and the eof mark) at FIN.
  ;; A chunk is a packed (unsigned-byte 8) vector -- the stream<u8> read lifts
  ;; the octets as they are -- so the buffer cursor below walks it with aref.
  ;; Under a read deadline, a read the host could not answer at once races a
  ;; timer. When the timer wins the read is withdrawn; a chunk that landed
  ;; meanwhile is still taken, and an empty withdrawal (status 2, cancelled) is
  ;; the timeout. Nothing stays in flight, so the next read starts afresh, as
  ;; after SO_TIMEOUT. A TLS entry's buffered output goes first: the peer may be
  ;; waiting for it.
  (when (rontolisp::%sock-e-out e)
    (rontolisp:await (rontolisp::%sock-flush-f e)))
  (let* ((recv (rontolisp::%sock-e-recv e))
         (rd (%sock:sock-stream-read recv))
         (ms (rontolisp::%sock-e-deadline e))
         (status nil))
    (when (and ms (not (rontolisp::%future-settled-p rd)))
      (let ((winner
             (rontolisp:await
              (rontolisp::%future-race (list rd (rontolisp:wait-for ms))))))
        (when (eql winner 1)
          (setq status (%sock:sock-stream-cancel-read recv)))))
    (let ((chunk (rontolisp:await rd)))
      (if (and chunk (> (length chunk) 0))
          (progn
            (rontolisp::%sock-set-buf e chunk 0)
            t)
          (if (eql status 2)
              (error (concatenate 'string op ": Read timed out"))
              (progn
                (rontolisp::%sock-set-eof e)
                nil))))))

(defun rontolisp::%sock-buf-ready (e)
  (let ((buf (rontolisp::%sock-e-buf e)))
    (and buf (< (rontolisp::%sock-e-cursor e) (length buf)))))

(defun rontolisp::%sock-pop-byte (e)
  (let* ((buf (rontolisp::%sock-e-buf e))
         (cursor (rontolisp::%sock-e-cursor e))
         (b (aref buf cursor)))
    (rontolisp::%sock-set-buf e buf (+ cursor 1))
    b))

(rontolisp:async-defun rontolisp::%sock-read-byte-f (e op)
  (if (rontolisp::%sock-buf-ready e)
      (rontolisp::%sock-pop-byte e)
      (if (rontolisp::%sock-e-eof e)
          nil
          (if (rontolisp:await (rontolisp::%sock-fill e op))
              (rontolisp::%sock-pop-byte e)
              nil))))

(rontolisp:async-defun rontolisp::%sock-read-char-f (e op)
  ;; One UTF-8 sequence assembled byte-wise (a refill may intervene between the
  ;; bytes, so a sequence split across host chunks still decodes whole). An
  ;; invalid lead byte stands alone and decodes to whatever the string layer's
  ;; decoder yields for it -- the same bytes the interpreter's UTF-8 decode sees.
  (let ((b0 (rontolisp:await (rontolisp::%sock-read-byte-f e op))))
    (if (null b0)
        nil
        (if (< b0 128)
            (code-char b0)
            (let ((n (if (< b0 192) 0 (if (< b0 224) 1 (if (< b0 240) 2 3))))
                  (acc (rontolisp::%str-from-byte b0)))
              (while (> n 0)
                (let ((bn
                       (rontolisp:await (rontolisp::%sock-read-byte-f e op))))
                  (if (null bn)
                      (setq n 0)
                      (progn
                        (setq acc
                         ;; byte-level append: acc holds a PARTIAL UTF-8
                         ;; sequence, which the wrapped concatenate's
                         ;; character conversion would trap on
                         (%string-concat acc (rontolisp::%str-from-byte bn)))
                        (setq n (- n 1))))))
              (char acc 0))))))

(rontolisp:async-defun rontolisp::%sock-read-line-f (e op)
  ;; Bytes up to \n (one trailing \r stripped, dropped at EOF too), assembled
  ;; into a string whose bytes ARE the wire bytes -- so a UTF-8 line decodes
  ;; exactly like the interpreter's byte-collecting readLine, chunk boundaries
  ;; included. The pending-cr flag holds a \r back until the next byte shows it
  ;; is not the one before the terminating \n. The second value is read-line's
  ;; missing-newline-p: t when the peer's close, not a \n, ended the line.
  (let ((acc "") (got nil) (done nil) (cr nil) (newline nil))
    (while (not done)
      (let ((b (rontolisp:await (rontolisp::%sock-read-byte-f e op))))
        (if (null b)
            (setq done t)
            (progn
              (setq got t)
              (if (= b 10)
                  (progn
                    (setq newline t)
                    (setq done t))
                  (progn
                    (if cr
                        ;; byte-level appends: acc's bytes ARE the wire bytes
                        ;; (possibly mid-UTF-8-sequence, possibly not UTF-8 at
                        ;; all), so the wrapped concatenate's character
                        ;; conversion must not touch them
                        (setq acc
                         (%string-concat acc (rontolisp::%str-from-byte 13))))
                    (if (= b 13)
                        (setq cr t)
                        (progn
                          (setq cr nil)
                          (setq acc
                                (%string-concat acc
                                 (rontolisp::%str-from-byte b)))))))))))
    (if got (%quiet-values acc (not newline)) (%quiet-values nil t))))

;;; --- the %io-* dispatch defuns (the compiler rewrite's targets; the %...-raw
;;; names compile to the NATIVE built-ins, so there is no rewrite recursion).
;;; A non-socket handle falls through to the %stdin-*-or-raw-f helpers -- the
;;; real stdin.lisp machinery, or its serve-mode raw-passthrough stub; both are
;;; spliced by StdinLibrary whenever this file is. ---

(rontolisp:async-defun rontolisp::%read-line-future (&optional s)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (rontolisp:await (rontolisp::%sock-read-line-f e "read-line"))
        (rontolisp:await (rontolisp::%stdin-read-line-or-raw-f s)))))

(rontolisp:async-defun rontolisp::%read-char-future (&optional s)
  ;; The BARE (read-char s) carries CL's default eof-error-p t, so the socket arm
  ;; turns the read's nil-at-EOF into the same end-of-file signal the interpreter,
  ;; the JVM and this backend's own non-socket designators give -- read-line is the
  ;; one that answers nil at peer close, and only because rontolisp's read-line
  ;; defaults eof-error-p to nil. The nil convention stays BELOW this entry
  ;; (%sock-read-char-f keeps it) because %read-char-eof-future needs the raw nil to
  ;; apply the caller's own eof arguments. The stdin arm already signals.
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((c
               (rontolisp:await (rontolisp::%sock-read-char-f e "read-char"))))
          (if c c (error 'end-of-file)))
        (rontolisp:await (rontolisp::%stdin-read-char-or-raw-f s)))))

(rontolisp:async-defun rontolisp::%read-byte-future (&optional s)
  ;; Same eof contract as %read-char-future above, for the same reason: a bare
  ;; (read-byte s) carries eof-error-p t, and %read-byte-eof-future is where the
  ;; caller's own eof arguments are applied to the raw nil.
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((b
               (rontolisp:await (rontolisp::%sock-read-byte-f e "read-byte"))))
          (if b b (error 'end-of-file)))
        (rontolisp:await (rontolisp::%stdin-read-byte-or-raw-f s)))))

;;; The stream DESIGNATOR is resolved HERE, before the nil test: an omitted
;;; argument and an explicit nil both mean the current *standard-input*, so a
;;; (with-input-from-string (*standard-input* ...) ...) around a read reaches
;;; the string stream instead of the host stdin cache. A program that never
;;; binds the variable compiles the bare read to the constant t, so the
;;; not-a-handle test below picks the same stdin path it always did.
;;;
;;; %sock-handle is part of the SAME resolution: a synonym stream and an open
;;; stream are both values, not handles, so without it the not-a-handle test would
;;; send a read through one to the host stdin cache. This rewrite replaces the read
;;; built-ins, so the compiler's own designator seam never sees these call sites.
(defun rontolisp::%io-read-line (&optional s)
  (let ((in (rontolisp::%sock-handle (or s *standard-input*))))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force (rontolisp::%read-line-future in))
        (rontolisp::%read-line-raw in))))

(defun rontolisp::%io-read-char (&optional s)
  (let ((in (rontolisp::%sock-handle (or s *standard-input*))))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force (rontolisp::%read-char-future in))
        (rontolisp::%read-char-raw in))))

(defun rontolisp::%io-read-byte (&optional s)
  (if (or (rontolisp::%sock-entry s) (null s))
      (rontolisp::%future-force (rontolisp::%read-byte-future s))
      (rontolisp::%read-byte-raw s)))

;;; --- the eof-tolerant reads and the bounded sequence ops (what cl-postgres'
;;; read-bytes / write-bytes / read-simple-str need on a socket handle, and what
;;; the Gray-streams dispatch helpers' fall-through arms spell -- ALWAYS with the
;;; eof / :start / :end arguments filled in, which is why every one of these
;;; shapes has to have a dispatch target). A non-socket designator falls through
;;; to the native built-in / its expansion under the %...-raw alias,
;;; byte-identical semantics to the unrewritten form. ---

(rontolisp:async-defun rontolisp::%sock-read-seq-f (e seq start end)
  ;; Fill seq[start,end) with wire bytes; returns the index of the first element
  ;; NOT updated (CL read-sequence's value), so a short read at EOF stays visible.
  (let ((i start) (stop nil))
    (while (and (< i end) (not stop))
      (let ((b
             (rontolisp:await
              (rontolisp::%sock-read-byte-f e "read-sequence"))))
        (if (null b)
            (setq stop t)
            (progn
              (setf (aref seq i) b)
              (setq i (+ i 1))))))
    i))

(defun rontolisp::%sock-seq-payload (seq start end)
  ;; One write payload from seq[start,end). A STRING sequence goes out as its own
  ;; characters (what the native write-sequence expansion does for a string), any
  ;; other vector one raw wire byte per element (socket write-sequence elements are
  ;; bytes, like the interpreter's socket branch), copied into an octet vector the
  ;; stream write stages as it is.
  (if (stringp seq)
      (subseq seq start end)
      (let ((v (make-array (- end start) :element-type '(unsigned-byte 8)))
            (i start))
        (while (< i end)
          (setf (aref v (- i start)) (aref seq i))
          (setq i (+ i 1)))
        v)))

(rontolisp:async-defun rontolisp::%read-byte-eof-future
    (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((b
               (rontolisp:await (rontolisp::%sock-read-byte-f e "read-byte"))))
          (if b b (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp::%read-byte-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-byte-eof (s eof-error-p &optional eof-value)
  (if (rontolisp::%sock-entry s)
      (rontolisp::%future-force
       (rontolisp::%read-byte-eof-future s eof-error-p eof-value))
      (rontolisp::%read-byte-raw s eof-error-p eof-value)))

;;; read-char / read-line with the eof parameters, the %io-read-byte-eof shape: the
;;; socket reads answer nil at EOF, so the eof contract is one test on top of them.
;;; The signalled condition is the SAME end-of-file class the native lowering uses
;;; (LispMacroExpander.endOfFileSignal), so handler-case behaves identically whether
;;; or not the designator turned out to be a socket.
(rontolisp:async-defun rontolisp::%read-char-eof-future
    (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((c
               (rontolisp:await (rontolisp::%sock-read-char-f e "read-char"))))
          (if c c (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp:await
         (rontolisp::%stdin-read-char-eof-or-raw-f s eof-error-p eof-value)))))

(defun rontolisp::%io-read-char-eof (s eof-error-p &optional eof-value)
  (let ((in (rontolisp::%sock-handle (or s *standard-input*))))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force
         (rontolisp::%read-char-eof-future in eof-error-p eof-value))
        (rontolisp::%read-char-raw in eof-error-p eof-value))))

(rontolisp:async-defun rontolisp::%read-line-eof-future
    (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let* ((vals
                (%quiet-multiple-value-list
                 (rontolisp:await
                  (rontolisp::%sock-read-line-f e "read-line"))))
               (l (car vals)))
          (if l
              (%quiet-values l (car (cdr vals)))
              (%quiet-values (if eof-error-p (error 'end-of-file) eof-value)
                             t)))
        (rontolisp:await
         (rontolisp::%stdin-read-line-eof-or-raw-f s eof-error-p eof-value)))))

(defun rontolisp::%io-read-line-eof (s eof-error-p &optional eof-value)
  (let ((in (rontolisp::%sock-handle (or s *standard-input*))))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force
         (rontolisp::%read-line-eof-future in eof-error-p eof-value))
        (rontolisp::%read-line-raw in eof-error-p eof-value))))

;;; The sequence ops carry :start / :end all the way down: the bounds are NOT a
;;; caller convenience here, they are the shape the Gray-streams fall-through arm
;;; emits, and an unbounded-only dispatch left that arm on the native built-in.
;;; Only the SOCKET arm resolves a nil :end to (length seq) -- a socket buffer is
;;; a rank-1 vector; the native arm takes nil as "the whole buffer" itself, which
;;; is what lets a rank-2 packed float array be read in bulk
;;; (.kb/binary-sequence-io.md).
(rontolisp:async-defun rontolisp::%read-sequence-future
    (seq s &optional start end)
  (let ((a (if start start 0)))
    (let ((e (rontolisp::%sock-entry s)))
      (if e
          (rontolisp:await
           (rontolisp::%sock-read-seq-f e seq a (if end end (length seq))))
          (rontolisp::%read-sequence-raw seq s :start a :end end)))))

(defun rontolisp::%io-read-sequence (seq s &optional start end)
  (let ((a (if start start 0)))
    (if (rontolisp::%sock-entry s)
        (rontolisp::%future-force
         (rontolisp::%read-sequence-future seq s a (if end end (length seq))))
        (rontolisp::%read-sequence-raw seq s :start a :end end))))

(defun rontolisp::%io-write-sequence (seq s &optional start end)
  (let ((a (if start start 0)))
    (let ((e (rontolisp::%sock-entry s)))
      (if e
          (let ((payload
                 (rontolisp::%sock-seq-payload seq a
                                               (if end end (length seq)))))
            (rontolisp::%sock-write-string e payload)
            (if (stringp seq) (rontolisp::%sock-track-line e payload))
            seq)
          (rontolisp::%write-sequence-raw seq s :start a :end end)))))

;;; --- writes (the synchronous stream.write built-in blocks until the peer has
;;; taken the bytes; FIN = dropping the write end at close) ---

(defun rontolisp::%sock-payload-length (s)
  ;; A payload's byte count: a string's, not its char count (a %str-from-byte
  ;; payload whose byte is a UTF-8 continuation value has char count 0 but must
  ;; still go out), or an octet vector's length.
  (if (stringp s) (rontolisp::%str-byte-length s) (length s)))

(defun rontolisp::%sock-write-string (e s)
  ;; S is a string or an octet vector (%sock-seq-payload); the stream write takes
  ;; either as its bytes. %sock-ensure-tx makes the write pair (and calls the
  ;; at-most-once tcp-socket.send) on the first write -- the deferral tls-upgrade
  ;; rests on. A TLS entry buffers instead (%sock-buffer-bytes, below).
  (when (> (rontolisp::%sock-payload-length s) 0)
    (if (rontolisp::%sock-e-out e)
        (rontolisp::%sock-buffer-bytes e s)
        (%sock:sock-stream-write (rontolisp::%sock-ensure-tx e) s)))
  s)

;;; --- the TLS output buffer. wasmtime's wasi:tls (49) hands each cleartext
;;; write to rustls and reports it written once rustls has ACCEPTED it; the
;;; records rustls could not pass on yet -- the host pipe behind it was full, or
;;; the task had used up its I/O budget for this turn -- wait for the next
;;; cleartext write, since nothing on the host flushes them. A write followed by
;;; a read therefore lost its tail: about 128 small writes in a row, or one write
;;; past the pipe's 16 KiB. So a TLS entry collects its writes here and sends
;;; them at a flush: a read or listen that has to ask the host, force-output /
;;; finish-output, close, or a full buffer -- the points real cl+ssl flushes its
;;; own output buffer at, and then some. Each piece (at most one buffer, whose
;;; record fits the pipe) is sent after a 1 ms wait, which lets the host drain
;;; the pipe and starts a fresh budget, so rustls can pass the whole record on.
;;; (.kb/tcp-sockets.md, "WASM TLS".) ---

(defun rontolisp::%sock-buffer-output (e)
  ;; tls.lisp's tls-upgrade turns buffering on once the handshake is done.
  (rplaca (nthcdr 9 e) (make-array 8192 :element-type '(unsigned-byte 8))))

(defun rontolisp::%sock-buffer-bytes (e payload)
  ;; The payload's bytes onto the buffer, flushing whenever it is full. A string
  ;; is first rendered by %string-concat: a character vector (a make-string or
  ;; subseq result) would be rendered again by every %str-byte-ref.
  (let* ((str (stringp payload))
         (s (if str (%string-concat payload "") payload))
         (n (rontolisp::%sock-payload-length s))
         (i 0))
    (while (< i n)
      (let* ((out (rontolisp::%sock-e-out e))
             (fill (rontolisp::%sock-e-out-fill e))
             (stop (min n (+ i (- (length out) fill)))))
        (if (= i stop)
            (rontolisp::%sock-flush e)
            (progn
              (while (< i stop)
                (setf (aref out fill)
                      (if str (rontolisp::%str-byte-ref s i) (aref s i)))
                (setq fill (+ fill 1))
                (setq i (+ i 1)))
              (rontolisp::%sock-set-out-fill e fill)))))))

(rontolisp:async-defun rontolisp::%sock-flush-f (e)
  ;; Sends the buffered bytes, one piece at a time. A piece is copied out and the
  ;; buffer emptied BEFORE the wait, so writes made meanwhile by another task land
  ;; behind it; while one task has a piece in flight, any other flusher waits its
  ;; turn, which keeps the pieces in buffer order.
  (while (> (rontolisp::%sock-e-out-fill e) 0)
    (if (rontolisp::%sock-e-flushing e)
        (rontolisp:await (rontolisp:wait-for 1))
        (let* ((out (rontolisp::%sock-e-out e))
               (fill (rontolisp::%sock-e-out-fill e))
               (piece (make-array fill :element-type '(unsigned-byte 8)))
               (i 0))
          (while (< i fill)
            (setf (aref piece i) (aref out i))
            (setq i (+ i 1)))
          (rontolisp::%sock-set-out-fill e 0)
          (rontolisp::%sock-set-flushing e t)
          (rontolisp:await (rontolisp:wait-for 1))
          (%sock:sock-stream-write (rontolisp::%sock-e-tx e) piece)
          (rontolisp::%sock-set-flushing e nil))))
  nil)

(defun rontolisp::%sock-flush (e)
  (if (rontolisp::%sock-e-out e)
      (rontolisp::%future-force (rontolisp::%sock-flush-f e))
      nil))

;; What characters leave of the line (.kb/standard-output-redirect.md, "A stream's
;; line start"). An octet moves nothing, so %io-write-byte has no call of it.
(defun rontolisp::%sock-track-line (e s)
  (let ((n (rontolisp::%str-byte-length s)))
    (if (> n 0)
        (rontolisp::%sock-set-mid-line e
         (not (= (rontolisp::%str-byte-ref s (- n 1)) 10))))))

(defun rontolisp::%io-write-line (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          ;; %string-concat: s can hold raw wire bytes (a binary line read back
          ;; off a socket), which the wrapped concatenate's character conversion
          ;; would trap on or re-encode
          (rontolisp::%sock-write-string e
           (%string-concat s (%princ-piece #\Newline)))
          (rontolisp::%sock-set-mid-line e nil)
          s)
        (rontolisp::%write-line-raw s stream))))

(defun rontolisp::%io-write-string (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (rontolisp::%sock-write-string e s)
          (rontolisp::%sock-track-line e s)
          s)
        (rontolisp::%write-string-raw s stream))))

(defun rontolisp::%io-fresh-line (&optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (if (rontolisp::%sock-e-mid-line e)
            (progn
              (rontolisp::%sock-write-string e (%princ-piece #\Newline))
              (rontolisp::%sock-set-mid-line e nil)
              t)
            nil)
        (rontolisp::%fresh-line-raw stream))))

(defun rontolisp::%io-write-byte (b stream)
  ;; %str-from-byte, never code-char: the write path copies the string's raw
  ;; bytes onto the wire, and a code point >= 128 is a TWO-byte UTF-8 sequence.
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (rontolisp::%sock-write-string e (rontolisp::%str-from-byte b))
          b)
        (rontolisp::%write-byte-raw b stream))))

(rontolisp:async-defun rontolisp::%sock-probe-f (e)
  ;; Whether a read would answer at once, consuming nothing: bytes left in the
  ;; chunk, or a chunk the host hands over now. The probe starts a read and, when
  ;; the host has nothing yet, withdraws it at once -- the question the
  ;; interpreter's available() asks the kernel -- so nothing stays in flight. A
  ;; chunk that comes is the next read's; the peer's close marks the eof (nil).
  (if (rontolisp::%sock-buf-ready e)
      t
      (if (rontolisp::%sock-e-eof e)
          nil
          (let* ((recv (rontolisp::%sock-e-recv e))
                 (rd (%sock:sock-stream-read recv))
                 (status
                  (if (rontolisp::%future-settled-p rd)
                      nil
                      (%sock:sock-stream-cancel-read recv)))
                 (chunk (rontolisp:await rd)))
            (if (and chunk (> (length chunk) 0))
                (progn
                  (rontolisp::%sock-set-buf e chunk 0)
                  t)
                (progn
                  (unless (eql status 2) (rontolisp::%sock-set-eof e))
                  nil))))))

(defun rontolisp::%io-listen (&optional s)
  ;; A connected socket answers what the host could hand over now
  ;; (%sock-probe-f), exactly as the interpreter's and the JVM's kernel probe
  ;; does; a listener answers nil. A non-socket designator falls through to the
  ;; native listen under its %listen-raw alias -- the same shape %io-read-char's
  ;; raw twin uses: a string input stream answers from its record, and the alias
  ;; keeps the compile-time socket rewrite of the public name from recursing.
  ;; A TLS entry sends its buffered output first: the input asked about may wait
  ;; on it.
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (if (= (rontolisp::%sock-e-kind e) 1)
            (progn
              (rontolisp::%sock-flush e)
              (rontolisp::%future-force (rontolisp::%sock-probe-f e)))
            nil)
        (rontolisp::%listen-raw s))))

;;; force-output and finish-output send a TLS entry's buffered output and answer
;;; nil; on anything else they are the native built-in, which has nothing to
;;; flush on WASM (every other write goes out as it is made).
(defun rontolisp::%io-force-output (&optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e (rontolisp::%sock-flush e) (rontolisp::%force-output-raw stream))))

(defun rontolisp::%io-finish-output (&optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e (rontolisp::%sock-flush e) (rontolisp::%force-output-raw stream))))

(defun rontolisp::%io-open-stream-p (s)
  ;; A socket entry lives in *sock-table* until %io-close drops it, so table
  ;; membership answers "open" for a live socket. A handle in the socket fd
  ;; range with no entry is a socket that has BEEN CLOSED -- the fds are handed
  ;; out sequentially from 200 up to *sock-next-fd*, so the range test
  ;; distinguishes it from a file/stdin handle, which falls through to the
  ;; native built-in under its %open-stream-p-raw alias (the stream value's
  ;; closed mark).
  (if (rontolisp::%sock-entry s)
      t
      (let ((h (rontolisp::%sock-handle s)))
        (if (and (integerp h) (>= h 200) (< h rontolisp::*sock-next-fd*))
            nil
            (rontolisp::%open-stream-p-raw s)))))

(defun rontolisp::%io-close (stream)
  ;; Out of the table and at its end BEFORE anything is released: a read or an
  ;; accept another task still has in flight is withdrawn below (a drop of a busy
  ;; stream traps), which wakes that task at once with nothing -- and a reader that
  ;; found the socket still open would start another read on the stream being
  ;; dropped. The withdrawal answers nil, touching nothing, when none is in flight.
  ;; A TLS entry's buffered output goes out first; the flush may wait, so the
  ;; entry is looked up again after it (another task may have closed it).
  (let ((e (rontolisp::%sock-entry stream))) (if e (rontolisp::%sock-flush e)))
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (remhash (rontolisp::%sock-handle stream) rontolisp::*sock-table*)
          (rontolisp::%sock-set-eof e)
          (if (= (rontolisp::%sock-e-kind e) 2)
              (progn
                (%sock:accept-stream-cancel-read (rontolisp::%sock-e-recv e))
                (%sock:accept-stream-drop-readable (rontolisp::%sock-e-recv e))
                (%sock:tcp-socket-drop (rontolisp::%sock-e-sock e)))
              (progn
                ;; A never-written socket has no tx (the send half is deferred
                ;; to the first write); the socket drop below closes it and
                ;; sends the FIN.
                (let ((tx (rontolisp::%sock-e-tx e)))
                  (when tx (%sock:sock-stream-drop-writable tx)))
                (%sock:sock-stream-cancel-read (rontolisp::%sock-e-recv e))
                (%sock:sock-stream-drop-readable (rontolisp::%sock-e-recv e))
                (%sock:tcp-socket-drop (rontolisp::%sock-e-sock e))))
          t)
        (rontolisp::%close-raw stream))))
