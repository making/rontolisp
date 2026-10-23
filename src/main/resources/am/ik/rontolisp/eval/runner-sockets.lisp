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

;; The runner's host: a socket is the number the host answered, -1 a failure
;; whose text lastError holds. read fills the caller's buffer (the :bytes
;; shape: the trailing argument is the (unsigned-byte 8) vector) and answers
;; the count, 0 at the peer's close; write takes an octet vector or a string's
;; raw bytes.
(rontolisp:wasm-import 'rontolisp::%rltcp-connect
                       :from "rltcp"
                       :as "connect"
                       :params '(:string :s32)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-listen
                       :from "rltcp"
                       :as "listen"
                       :params '(:string :s32)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-accept
                       :from "rltcp"
                       :as "accept"
                       :params '(:s32)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-read
                       :from "rltcp"
                       :as "read"
                       :params '(:s32)
                       :returns :bytes)
(rontolisp:wasm-import 'rontolisp::%rltcp-write
                       :from "rltcp"
                       :as "write"
                       :params '(:s32 :octets)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-close
                       :from "rltcp"
                       :as "close"
                       :params '(:s32)
                       :returns :void)
(rontolisp:wasm-import 'rontolisp::%rltcp-local-port
                       :from "rltcp"
                       :as "localPort"
                       :params '(:s32)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-local-address
                       :from "rltcp"
                       :as "localAddress"
                       :params '(:s32)
                       :returns :string)
(rontolisp:wasm-import 'rontolisp::%rltcp-peer-address
                       :from "rltcp"
                       :as "peerAddress"
                       :params '(:s32)
                       :returns :string)
(rontolisp:wasm-import 'rontolisp::%rltcp-peer-port
                       :from "rltcp"
                       :as "peerPort"
                       :params '(:s32)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-set-timeout
                       :from "rltcp"
                       :as "setTimeout"
                       :params '(:s32 :s32)
                       :returns :s32)
(rontolisp:wasm-import 'rontolisp::%rltcp-last-error
                       :from "rltcp"
                       :as "lastError"
                       :params '()
                       :returns :string)

;;; --- the socket table: fd -> (host kind buf cursor limit eof) ---
;;; host = the runner's number; kind 1 = connected, 2 = listener; buf = the
;;; chunk buffer (a connected socket's), cursor/limit the unread bytes in it.

(defvar rontolisp::*sock-table* (make-hash-table))
(defvar rontolisp::*sock-next-fd* 200)

;; The raw handle a stream designator names: an OPEN stream is a VALUE carrying
;; its handle in slot 0, and a synonym stream forwards to whatever its variable
;; holds now (sockets.lisp's %sock-handle, for the same reason).
(defun rontolisp::%sock-handle (s)
  (if (%obj-is s '%SYNONYM-STREAM)
      (rontolisp::%sock-handle (funcall (%obj-ref s 1)))
      (if (%obj-is s '%STREAM) (%obj-ref s 0) s)))

(defun rontolisp::%sock-entry (fd)
  (let ((h (rontolisp::%sock-handle fd)))
    (if (integerp h) (gethash h rontolisp::*sock-table*) nil)))

(defun rontolisp::%sock-e-host (e) (car e))
(defun rontolisp::%sock-e-kind (e) (car (cdr e)))
(defun rontolisp::%sock-e-buf (e) (car (cdr (cdr e))))
(defun rontolisp::%sock-e-cursor (e) (car (cdr (cdr (cdr e)))))
(defun rontolisp::%sock-e-limit (e) (car (cdr (cdr (cdr (cdr e))))))
(defun rontolisp::%sock-e-eof (e) (car (cdr (cdr (cdr (cdr (cdr e)))))))

(defun rontolisp::%sock-set-buf (e cursor limit)
  (rplaca (cdr (cdr (cdr e))) cursor)
  (rplaca (cdr (cdr (cdr (cdr e)))) limit))

(defun rontolisp::%sock-set-eof (e) (rplaca (cdr (cdr (cdr (cdr (cdr e))))) t))

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
                    nil) 0 0 nil))
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

(defun rontolisp:tcp-connect (host port)
  (rontolisp::%sock-check-host "tcp-connect" host)
  (rontolisp::%sock-check-port "tcp-connect" port)
  (let ((h (rontolisp::%rltcp-connect host port)))
    (if (< h 0)
        (rontolisp::%sock-host-fail
         (concatenate 'string "tcp-connect: cannot connect to " host ":"
                      (rontolisp::%sock-decimal port) ": "))
        (rontolisp::%sock-register h 1))))

(defun rontolisp::%tcp-connect-f (host port) (rontolisp:tcp-connect host port))

(defun rontolisp:tcp-listen (port &optional host)
  (when host (rontolisp::%sock-check-host "tcp-listen" host))
  (rontolisp::%sock-check-port "tcp-listen" port)
  (let ((h (rontolisp::%rltcp-listen (if host host "") port)))
    (if (< h 0)
        (rontolisp::%sock-host-fail
         (concatenate 'string "tcp-listen: cannot listen on " (if host host "*")
                      ":" (rontolisp::%sock-decimal port) ": "))
        (rontolisp::%sock-register h 2))))

(defun rontolisp:tcp-accept (listener)
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
  ;; ordinary stream built-in, as on the interpreter and the JVM.
  (let ((e (rontolisp::%sock-connected "TCP-SET-TIMEOUT" fd)))
    (unless (or (null ms) (and (integerp ms) (>= ms 0)))
      (rontolisp::%sock-fail
       "tcp-set-timeout: the deadline must be nil or a non-negative integer"))
    (when (< (rontolisp::%rltcp-set-timeout (rontolisp::%sock-e-host e)
                                            (if ms (min ms 2147483647) 0)) 0)
      (rontolisp::%sock-host-fail "tcp-set-timeout: "))
    ms))

;;; --- buffered reads: one host chunk per socket, walked by BYTE. OP names the
;;; public operator a host failure is reported under. ---

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

(defun rontolisp::%sock-read-byte-f (e op)
  (if (rontolisp::%sock-buf-ready e)
      (rontolisp::%sock-pop-byte e)
      (if (rontolisp::%sock-e-eof e)
          nil
          (if (rontolisp::%sock-fill e op) (rontolisp::%sock-pop-byte e) nil))))

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

(defun rontolisp::%sock-read-line-f (e op)
  ;; Bytes up to \n (one trailing \r stripped, dropped at EOF too), assembled
  ;; into a string whose bytes ARE the wire bytes. The second value is
  ;; read-line's missing-newline-p: t when the peer's close ended the line.
  (let ((acc "") (got nil) (done nil) (cr nil) (newline nil))
    (while (not done)
      (let ((b (rontolisp::%sock-read-byte-f e op)))
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

(defun rontolisp::%sock-read-seq-f (e seq start end)
  ;; Fill seq[start,end) with wire bytes; answers the index of the first element
  ;; NOT updated (CL read-sequence's value), so a short read at EOF stays visible.
  (let ((i start) (stop nil))
    (while (and (< i end) (not stop))
      (let ((b (rontolisp::%sock-read-byte-f e "read-sequence")))
        (if (null b)
            (setq stop t)
            (progn
              (setf (aref seq i) b)
              (setq i (+ i 1))))))
    i))

;;; --- writes: the host takes the bytes whole and blocks until the kernel has
;;; them. A string crosses as its raw bytes, an octet vector as it is. ---

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
          (progn
            (rontolisp::%sock-write-octets e
             (rontolisp::%sock-seq-octets seq a (if end end (length seq)))
             "write-sequence")
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
          s)
        (rontolisp::%write-line-raw s stream))))

(defun rontolisp::%io-write-string (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (rontolisp::%sock-write-string e s "write-string")
        (rontolisp::%write-string-raw s stream))))

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

(defun rontolisp::%io-listen (&optional s)
  ;; Immediately-available probe: unconsumed bytes in the socket's chunk buffer
  ;; (the kernel's receive buffer is not asked, the component's documented
  ;; divergence). A non-socket designator falls through to the native listen
  ;; under its %listen-raw alias.
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (if (rontolisp::%sock-buf-ready e) t nil)
        (rontolisp::%listen-raw s))))

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
          t)
        (rontolisp::%close-raw stream))))
