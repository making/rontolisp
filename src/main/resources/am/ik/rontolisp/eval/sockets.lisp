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
;;;;   - a connected socket is plumbed eagerly like the old adapter: receive()
;;;;     yields the recv stream (its future dropped immediately -- EOF is the
;;;;     stream status), a fresh stream.new pair's read end goes to send() (called
;;;;     at most once) and the write end stays for the write built-ins.
;;;;   - the socket HANDLE is an integer >= 200 in the file-stream handle space;
;;;;     the entry lives in the Lisp-side *sock-table*. The stream built-ins
;;;;     (read-line/read-byte/read-char/write-line/write-byte/close, plus
;;;;     read-sequence/write-sequence and the eof-tolerant 2-3-arg read-byte)
;;;;     reach it through the %io-* dispatch defuns the compiler rewrite
;;;;     targets; reads buffer one host chunk per socket (a documented
;;;;     divergence from the byte-at-a-time interpreter/JVM reads), and the
;;;;     chunk bookkeeping walks the chunk's BYTES -- the wire truth -- through
;;;;     the %str-byte-* intrinsics, never its UTF-8-decoded characters.

;; The WIT interface is lowered by SocketsLibrary (which calls
;; WitImportDirective.lower itself), so this directive never reaches
;; WitImportInliner -- but it is written here, in sockets.lisp's own source, so the
;; file reads as the program it is.
(rontolisp:wit-import "sockets.wit" :interface "wasi:sockets/types@0.3.0" :package %sock)

;;; --- the socket table: fd -> (socket kind recv tx buf cursor eof) ---
;;; kind 1 = connected, 2 = listener; recv = the recv byte stream (or, for a
;;; listener, the accept stream); tx = the write end of the send pair.

(defvar rontolisp::*sock-table* (make-hash-table))
(defvar rontolisp::*sock-next-fd* 200)

(defun rontolisp::%sock-entry (fd)
  (if (integerp fd) (gethash fd rontolisp::*sock-table*) nil))

(defun rontolisp::%sock-handle-p (fd)
  (if (rontolisp::%sock-entry fd) t nil))

(defun rontolisp::%sock-e-sock (e) (car e))
(defun rontolisp::%sock-e-kind (e) (car (cdr e)))
(defun rontolisp::%sock-e-recv (e) (car (cdr (cdr e))))
(defun rontolisp::%sock-e-tx (e) (car (cdr (cdr (cdr e)))))
(defun rontolisp::%sock-e-buf (e) (car (cdr (cdr (cdr (cdr e))))))
(defun rontolisp::%sock-e-cursor (e) (car (cdr (cdr (cdr (cdr (cdr e)))))))
(defun rontolisp::%sock-e-eof (e) (car (cdr (cdr (cdr (cdr (cdr (cdr e))))))))

(defun rontolisp::%sock-set-buf (e chunk cursor)
  (rplaca (cdr (cdr (cdr (cdr e)))) chunk)
  (rplaca (cdr (cdr (cdr (cdr (cdr e))))) cursor))

(defun rontolisp::%sock-set-eof (e)
  (rplaca (cdr (cdr (cdr (cdr (cdr (cdr e)))))) t))

(defun rontolisp::%sock-register (sock kind recv tx)
  (let ((fd rontolisp::*sock-next-fd*))
    (setq rontolisp::*sock-next-fd* (+ fd 1))
    (setf (gethash fd rontolisp::*sock-table*) (list sock kind recv tx nil 0 nil))
    fd))

;;; --- IPv4 literal parsing (hostname lookup is not wired; dotted quads only,
;;; matching the old adapter's $parse_ipv4) ---

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
                      (progn (setq bad t) (setq i len))
                      (progn (setq octets (cons n octets)) (setq n 0) (setq digits 0)))
                  (let ((d (- c 48)))
                    (if (or (< d 0) (> d 9) (> digits 2))
                        (progn (setq bad t) (setq i len))
                        (progn (setq n (+ (* n 10) d)) (setq digits (+ digits 1))))))
              (setq i (+ i 1))))
          (if (or bad (= digits 0) (> n 255))
              nil
              (let ((quad (reverse (cons n octets))))
                (if (= (length quad) 4) quad nil)))))))

(defun rontolisp::%sock-addr (host port)
  ;; localhost is resolved as a literal, like the old adapter's callers did.
  (let ((quad (rontolisp::%sock-parse-ipv4
               (if (string= host "localhost") "127.0.0.1" host))))
    (if quad
        (cons :ipv4 (list :port port :address quad))
        (error 'rontolisp:wit-error :payload :invalid-argument
               :message (concatenate 'string "tcp: not an IPv4 literal: " host)))))

;;; --- plumbing (the old adapter's $plumb): recv stream + send pair, eagerly ---

(defun rontolisp::%sock-plumb (sock kind)
  (let* ((rpair (%sock:tcp-socket-receive sock))
         (recv (car rpair)))
    (%sock:sock-future-drop-readable (car (cdr rpair)))
    (let ((spair (%sock:sock-stream-new)))
      (%sock:sock-future-drop-readable (%sock:tcp-socket-send sock (car spair)))
      (rontolisp::%sock-register sock kind recv (cdr spair)))))

;;; --- the tcp-* surface (sync wrappers force; async bodies are rewritten onto
;;; the %...-future internals by the compiler's promotion pass) ---

(rontolisp:async-defun rontolisp::%tcp-connect-f (host port)
  ;; Failure (unreachable host, refused connection, non-literal hostname) yields
  ;; nil, the WASM error convention -- matching fetch. The nil convention lives
  ;; HERE so the sync surface (a %future-force of this) and an async body's
  ;; promoted (await (%tcp-connect-f ...)) behave identically.
  (handler-case
      (let ((sock (%sock:tcp-socket-create :ipv4)))
        (rontolisp:await (%sock:tcp-socket-connect sock (rontolisp::%sock-addr host port)))
        (rontolisp::%sock-plumb sock 1))
    (rontolisp:wit-error () nil)))

(defun rontolisp:tcp-connect (host port)
  (rontolisp::%future-force (rontolisp::%tcp-connect-f host port)))

(defun rontolisp:tcp-listen (port &optional host)
  (handler-case
      (let ((sock (%sock:tcp-socket-create :ipv4)))
        (%sock:tcp-socket-bind sock (rontolisp::%sock-addr (if host host "0.0.0.0") port))
        (rontolisp::%sock-register sock 2 (%sock:tcp-socket-listen sock) nil))
    (rontolisp:wit-error () nil)))

(rontolisp:async-defun rontolisp::%tcp-accept-f (listener)
  ;; A non-listener handle (or a host error) yields nil, like the old adapter's
  ;; errno convention.
  (handler-case
      (let ((e (rontolisp::%sock-entry listener)))
        (if (and e (= (rontolisp::%sock-e-kind e) 2))
            (let ((sock (rontolisp:await (%sock:accept-stream-read (rontolisp::%sock-e-recv e)))))
              (if sock (rontolisp::%sock-plumb sock 1) nil))
            nil))
    (rontolisp:wit-error () nil)))

(defun rontolisp:tcp-accept (listener)
  (rontolisp::%future-force (rontolisp::%tcp-accept-f listener)))

(defun rontolisp::%sock-local-address (fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if e
        (handler-case
            (%sock:tcp-socket-get-local-address (rontolisp::%sock-e-sock e))
          (rontolisp:wit-error () nil))
        nil)))

(defun rontolisp::%sock-remote-address (fd)
  (let ((e (rontolisp::%sock-entry fd)))
    (if e
        (handler-case
            (%sock:tcp-socket-get-remote-address (rontolisp::%sock-e-sock e))
          (rontolisp:wit-error () nil))
        nil)))

(defun rontolisp::%sock-format-quad (quad)
  (concatenate 'string
               (rontolisp::%sock-octet-string (car quad)) "."
               (rontolisp::%sock-octet-string (car (cdr quad))) "."
               (rontolisp::%sock-octet-string (car (cdr (cdr quad)))) "."
               (rontolisp::%sock-octet-string (car (cdr (cdr (cdr quad)))))))

(defun rontolisp::%sock-octet-string (n)
  (let ((s ""))
    (if (= n 0)
        "0"
        (progn
          (while (> n 0)
            (setq s (concatenate 'string
                                 (princ-to-string (code-char (+ 48 (mod n 10)))) s))
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

;;; --- buffered reads (chunked; the %...-future internals are async-defuns so an
;;; async body's promoted read suspends instead of blocking the task). The chunk
;;; string's internal BYTES are exactly the bytes the host delivered, and the
;;; cursor walks those bytes (%str-byte-ref) -- never characters: char access
;;; UTF-8-decodes, so binary bytes that happen to form a valid multi-byte
;;; sequence (a PostgreSQL BackendKeyData secret, say) would collapse into one
;;; char and shift everything after them. ---

(rontolisp:async-defun rontolisp::%sock-fill (e)
  ;; Refill the chunk buffer from the recv stream; nil (and the eof mark) at FIN.
  (let ((chunk (rontolisp:await (%sock:sock-stream-read (rontolisp::%sock-e-recv e)))))
    (if (and chunk (> (rontolisp::%str-byte-length chunk) 0))
        (progn (rontolisp::%sock-set-buf e chunk 0) t)
        (progn (rontolisp::%sock-set-eof e) nil))))

(defun rontolisp::%sock-buf-ready (e)
  (let ((buf (rontolisp::%sock-e-buf e)))
    (and buf (< (rontolisp::%sock-e-cursor e) (rontolisp::%str-byte-length buf)))))

(defun rontolisp::%sock-pop-byte (e)
  (let* ((buf (rontolisp::%sock-e-buf e))
         (cursor (rontolisp::%sock-e-cursor e))
         (b (rontolisp::%str-byte-ref buf cursor)))
    (rontolisp::%sock-set-buf e buf (+ cursor 1))
    b))

(rontolisp:async-defun rontolisp::%sock-read-byte-f (e)
  (if (rontolisp::%sock-buf-ready e)
      (rontolisp::%sock-pop-byte e)
      (if (rontolisp::%sock-e-eof e)
          nil
          (if (rontolisp:await (rontolisp::%sock-fill e))
              (rontolisp::%sock-pop-byte e)
              nil))))

(rontolisp:async-defun rontolisp::%sock-read-char-f (e)
  ;; One UTF-8 sequence assembled byte-wise (a refill may intervene between the
  ;; bytes, so a sequence split across host chunks still decodes whole). An
  ;; invalid lead byte stands alone and decodes to whatever the string layer's
  ;; decoder yields for it -- the same bytes the interpreter's UTF-8 decode sees.
  (let ((b0 (rontolisp:await (rontolisp::%sock-read-byte-f e))))
    (if (null b0)
        nil
        (if (< b0 128)
            (code-char b0)
            (let ((n (if (< b0 192) 0 (if (< b0 224) 1 (if (< b0 240) 2 3))))
                  (acc (rontolisp::%str-from-byte b0)))
              (while (> n 0)
                (let ((bn (rontolisp:await (rontolisp::%sock-read-byte-f e))))
                  (if (null bn)
                      (setq n 0)
                      (progn
                        (setq acc (concatenate 'string acc (rontolisp::%str-from-byte bn)))
                        (setq n (- n 1))))))
              (char acc 0))))))

(rontolisp:async-defun rontolisp::%sock-read-line-f (e)
  ;; Bytes up to \n (one trailing \r stripped, dropped at EOF too), assembled
  ;; into a string whose bytes ARE the wire bytes -- so a UTF-8 line decodes
  ;; exactly like the interpreter's byte-collecting readLine, chunk boundaries
  ;; included. The pending-cr flag holds a \r back until the next byte shows it
  ;; is not the one before the terminating \n.
  (let ((acc "") (got nil) (done nil) (cr nil))
    (while (not done)
      (let ((b (rontolisp:await (rontolisp::%sock-read-byte-f e))))
        (if (null b)
            (setq done t)
            (progn
              (setq got t)
              (if (= b 10)
                  (setq done t)
                  (progn
                    (if cr (setq acc (concatenate 'string acc (rontolisp::%str-from-byte 13))))
                    (if (= b 13)
                        (setq cr t)
                        (progn
                          (setq cr nil)
                          (setq acc (concatenate 'string acc (rontolisp::%str-from-byte b)))))))))))
    (if got acc nil)))

;;; --- the %io-* dispatch defuns (the compiler rewrite's targets; the %...-raw
;;; names compile to the NATIVE built-ins, so there is no rewrite recursion).
;;; A non-socket handle falls through to the %stdin-*-or-raw-f helpers -- the
;;; real stdin.lisp machinery, or its serve-mode raw-passthrough stub; both are
;;; spliced by StdinLibrary whenever this file is. ---

(rontolisp:async-defun rontolisp::%read-line-future (&optional s)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (rontolisp:await (rontolisp::%sock-read-line-f e))
        (rontolisp:await (rontolisp::%stdin-read-line-or-raw-f s)))))

(rontolisp:async-defun rontolisp::%read-char-future (&optional s)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (rontolisp:await (rontolisp::%sock-read-char-f e))
        (rontolisp:await (rontolisp::%stdin-read-char-or-raw-f s)))))

(rontolisp:async-defun rontolisp::%read-byte-future (&optional s)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (rontolisp:await (rontolisp::%sock-read-byte-f e))
        (rontolisp:await (rontolisp::%stdin-read-byte-or-raw-f s)))))

;;; The stream DESIGNATOR is resolved HERE, before the nil test: an omitted
;;; argument and an explicit nil both mean the current *standard-input*, so a
;;; (with-input-from-string (*standard-input* ...) ...) around a read reaches
;;; the string stream instead of the host stdin cache. A program that never
;;; binds the variable compiles the bare read to the constant t, so the
;;; not-a-handle test below picks the same stdin path it always did.
(defun rontolisp::%io-read-line (&optional s)
  (let ((in (or s *standard-input*)))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force (rontolisp::%read-line-future in))
        (rontolisp::%read-line-raw in))))

(defun rontolisp::%io-read-char (&optional s)
  (let ((in (or s *standard-input*)))
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
      (let ((b (rontolisp:await (rontolisp::%sock-read-byte-f e))))
        (if (null b)
            (setq stop t)
            (progn
              (setf (aref seq i) b)
              (setq i (+ i 1))))))
    i))

(defun rontolisp::%sock-seq-string (seq start end)
  ;; One write payload from seq[start,end). A STRING sequence goes out as its own
  ;; characters (what the native write-sequence expansion does for a string), a
  ;; byte vector one raw wire byte per element (socket write-sequence elements are
  ;; bytes, like the interpreter's socket branch).
  (if (stringp seq)
      (subseq seq start end)
      (let ((acc "") (i start))
        (while (< i end)
          (setq acc (concatenate 'string acc (rontolisp::%str-from-byte (aref seq i))))
          (setq i (+ i 1)))
        acc)))

(rontolisp:async-defun rontolisp::%read-byte-eof-future (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((b (rontolisp:await (rontolisp::%sock-read-byte-f e))))
          (if b
              b
              (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp::%read-byte-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-byte-eof (s eof-error-p &optional eof-value)
  (if (rontolisp::%sock-entry s)
      (rontolisp::%future-force (rontolisp::%read-byte-eof-future s eof-error-p eof-value))
      (rontolisp::%read-byte-raw s eof-error-p eof-value)))

;;; read-char / read-line with the eof parameters, the %io-read-byte-eof shape: the
;;; socket reads answer nil at EOF, so the eof contract is one test on top of them.
;;; The signalled condition is the SAME end-of-file class the native lowering uses
;;; (LispMacroExpander.endOfFileSignal), so handler-case behaves identically whether
;;; or not the designator turned out to be a socket.
(rontolisp:async-defun rontolisp::%read-char-eof-future (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((c (rontolisp:await (rontolisp::%sock-read-char-f e))))
          (if c
              c
              (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp::%read-char-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-char-eof (s eof-error-p &optional eof-value)
  (let ((in (or s *standard-input*)))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force (rontolisp::%read-char-eof-future in eof-error-p eof-value))
        (rontolisp::%read-char-raw in eof-error-p eof-value))))

(rontolisp:async-defun rontolisp::%read-line-eof-future (s eof-error-p &optional eof-value)
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (let ((l (rontolisp:await (rontolisp::%sock-read-line-f e))))
          (if l
              l
              (if eof-error-p (error 'end-of-file) eof-value)))
        (rontolisp::%read-line-raw s eof-error-p eof-value))))

(defun rontolisp::%io-read-line-eof (s eof-error-p &optional eof-value)
  (let ((in (or s *standard-input*)))
    (if (or (rontolisp::%sock-entry in) (not (integerp in)))
        (rontolisp::%future-force (rontolisp::%read-line-eof-future in eof-error-p eof-value))
        (rontolisp::%read-line-raw in eof-error-p eof-value))))

;;; The sequence ops carry :start / :end all the way down: the bounds are NOT a
;;; caller convenience here, they are the shape the Gray-streams fall-through arm
;;; emits, and an unbounded-only dispatch left that arm on the native built-in.
(rontolisp:async-defun rontolisp::%read-sequence-future (seq s &optional start end)
  (let ((a (if start start 0)) (b (if end end (length seq))))
    (let ((e (rontolisp::%sock-entry s)))
      (if e
          (rontolisp:await (rontolisp::%sock-read-seq-f e seq a b))
          (rontolisp::%read-sequence-raw seq s :start a :end b)))))

(defun rontolisp::%io-read-sequence (seq s &optional start end)
  (let ((a (if start start 0)) (b (if end end (length seq))))
    (if (rontolisp::%sock-entry s)
        (rontolisp::%future-force (rontolisp::%read-sequence-future seq s a b))
        (rontolisp::%read-sequence-raw seq s :start a :end b))))

(defun rontolisp::%io-write-sequence (seq s &optional start end)
  (let ((a (if start start 0)) (b (if end end (length seq))))
    (let ((e (rontolisp::%sock-entry s)))
      (if e
          (progn
            (rontolisp::%sock-write-string e (rontolisp::%sock-seq-string seq a b))
            seq)
          (rontolisp::%write-sequence-raw seq s :start a :end b)))))

;;; --- writes (the synchronous stream.write built-in blocks until the peer has
;;; taken the bytes; FIN = dropping the write end at close) ---

(defun rontolisp::%sock-write-string (e s)
  ;; The byte-length guard, not the char count: a %str-from-byte payload whose
  ;; byte is a UTF-8 continuation value has char count 0 but must still go out.
  (when (> (rontolisp::%str-byte-length s) 0)
    (%sock:sock-stream-write (rontolisp::%sock-e-tx e) s))
  s)

(defun rontolisp::%io-write-line (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn (rontolisp::%sock-write-string e (concatenate 'string s (princ-to-string #\Newline)))
               s)
        (rontolisp::%write-line-raw s stream))))

(defun rontolisp::%io-write-string (s &optional stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn (rontolisp::%sock-write-string e s) s)
        (rontolisp::%write-string-raw s stream))))

(defun rontolisp::%io-write-byte (b stream)
  ;; %str-from-byte, never code-char: the write path copies the string's raw
  ;; bytes onto the wire, and a code point >= 128 is a TWO-byte UTF-8 sequence.
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn (rontolisp::%sock-write-string e (rontolisp::%str-from-byte b))
               b)
        (rontolisp::%write-byte-raw b stream))))

(defun rontolisp::%io-listen (&optional s)
  ;; Immediately-available probe: unconsumed bytes in the socket entry's chunk
  ;; buffer. The host kernel's receive buffer is not observable without
  ;; blocking on this backend, so a drained chunk answers nil even when bytes
  ;; wait host-side (documented divergence -- the chunk IS the readahead
  ;; buffer here). A non-socket designator answers nil.
  (let ((e (rontolisp::%sock-entry s)))
    (if e
        (if (rontolisp::%sock-buf-ready e) t nil)
        nil)))

(defun rontolisp::%io-open-stream-p (s)
  ;; A socket entry lives in *sock-table* until %io-close drops it, so table
  ;; membership answers "open" for a live socket. A handle in the socket fd
  ;; range with no entry is a socket that has BEEN CLOSED -- the fds are handed
  ;; out sequentially from 200 up to *sock-next-fd*, so the range test
  ;; distinguishes it from a file/stdin handle with no bookkeeping here (those
  ;; answer t for any non-nil designator, the documented lite edge).
  (if (rontolisp::%sock-entry s)
      t
      (if (and (integerp s) (>= s 200) (< s rontolisp::*sock-next-fd*))
          nil
          (if s t nil))))

(defun rontolisp::%io-close (stream)
  (let ((e (rontolisp::%sock-entry stream)))
    (if e
        (progn
          (if (= (rontolisp::%sock-e-kind e) 2)
              (progn
                (%sock:accept-stream-drop-readable (rontolisp::%sock-e-recv e))
                (%sock:tcp-socket-drop (rontolisp::%sock-e-sock e)))
              (progn
                (%sock:sock-stream-drop-writable (rontolisp::%sock-e-tx e))
                (%sock:sock-stream-drop-readable (rontolisp::%sock-e-recv e))
                (%sock:tcp-socket-drop (rontolisp::%sock-e-sock e))))
          (remhash stream rontolisp::*sock-table*)
          t)
        (rontolisp::%close-raw stream))))
