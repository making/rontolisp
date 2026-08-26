;;;; http-server.lisp -- the ONE server-side HTTP value model, shared by every
;;;; backend: the environment a rontolisp:http-handler handler receives and the
;;;; response it returns are EXACTLY Clack's, so a Clack application IS a
;;;; rontolisp handler and clack.handler.rontolisp converts nothing per request.
;;;;
;;;; Each transport hands over a positional RAW TUPLE of the facts only it can
;;;; know, and gets back the canonical triple its writer already understands:
;;;;
;;;;   (rontolisp::%http-serve-request app raw)
;;;;     raw = (method request-uri header-alist body server-protocol url-scheme
;;;;            local-name local-port remote-addr remote-port [script-name])
;;;;     =>    (status header-alist body-string)
;;;;
;;;; Everything between those two lines -- percent-decoding, the "?" split,
;;;; header lowercasing and comma-joining, the method keyword, the Host header
;;;; split, content-length parsing, the :raw-body stream, running the
;;;; application, and the whole Clack response normalizer -- lives HERE, once,
;;;; instead of once per backend. The transports keep only what is genuinely
;;;; theirs: reading the wire and writing the answer.
;;;;
;;;; :raw-body has two shapes, chosen by the directive (:raw-body :buffered):
;;;; - :stream (the default, rontolisp-native) -- the request body as it has
;;;;   always been: a rontolisp asynchronous stream, drained with
;;;;   (rontolisp:await (rontolisp:read-all ...)) from an async handler. The
;;;;   WASI component streams it lazily; nothing is buffered.
;;;; - :buffered (what Clack needs) -- the body read in full and wrapped in a
;;;;   BIVALENT in-memory Gray stream: read-line/read-char AND
;;;;   read-byte/read-sequence/file-position off ONE cursor. That is exactly
;;;;   what lack-request needs, because it wraps :raw-body in a
;;;;   circular-input-stream and hands it to http-body:parse, which reads
;;;;   BYTES. Clack's :raw-body is a synchronous stream and a synchronous read
;;;;   cannot block on a WASI future, so buffering is not a shortcut here: it
;;;;   is the only shape a Clack application can consume.
;;;;
;;;; Self-contained by rule: core built-ins and its own defuns only -- no
;;;; prelude, no url.lisp, no json.lisp. The harnesses that drive the compilers
;;;; directly splice this library with nothing else (the same rule http.lisp
;;;; states for itself), which is why the percent decoder and the drain loop
;;;; below are written here rather than borrowed.

;;; --- percent decoding --------------------------------------------------------

(defun rontolisp::%http-hex-digit (c)
  ;; The value of one hex digit given its char code, or -1 when it is not one.
  ;; Lenient by design: a request target is attacker input, so a malformed
  ;; escape is copied verbatim rather than signalled.
  (cond ((and (>= c 48) (<= c 57)) (- c 48))
        ((and (>= c 97) (<= c 102)) (- c 87))
        ((and (>= c 65) (<= c 70)) (- c 55))
        (t -1)))

(defun rontolisp::%http-utf8-length (b)
  ;; How many bytes the sequence led by B occupies. A byte that leads no valid
  ;; sequence (a stray continuation byte, #xF8..#xFF) answers 1, which is what
  ;; makes the decoder below hand it back as its own character instead of
  ;; signalling.
  (cond ((< b #x80) 1)
        ((< b #xC0) 1)
        ((< b #xE0) 2)
        ((< b #xF0) 3)
        ((< b #xF8) 4)
        (t 1)))

(defun rontolisp::%http-utf8-complete-end (v start end)
  ;; The index in [START, END] just past the last COMPLETE sequence -- i.e.
  ;; where a chunk boundary may fall without splitting a code point. Only ever
  ;; less than END when the tail bytes lead a sequence the range does not
  ;; finish, which is exactly the state a CHUNKED body source has to carry over
  ;; to its next chunk.
  ;; SPLIT, not `open': a variable named OPEN is rewritten as a call to cl:open
  ;; by the --no-wasi filesystem stub pass, which cannot tell a binding from a
  ;; call (compiler/NoWasiFilesystemStubs).
  (let ((i start) (split nil))
    (while (and (< i end) (not split))
      (let ((len (rontolisp::%http-utf8-length (aref v i))))
        (if (<= (+ i len) end) (setq i (+ i len)) (setq split t))))
    i))

(defun rontolisp::%http-utf8-decode-octets (v start end)
  ;; The range [START, END) of an octet VECTOR -> the string it encodes. A byte
  ;; that starts no valid sequence, and a sequence the range truncates, become
  ;; their own characters, so malformed input never signals.
  (with-output-to-string (s)
    (let ((i start))
      (while (< i end)
        (let ((b (aref v i))
              (b1 (if (< (+ i 1) end) (aref v (+ i 1)) nil))
              (b2 (if (< (+ i 2) end) (aref v (+ i 2)) nil))
              (b3 (if (< (+ i 3) end) (aref v (+ i 3)) nil)))
          (cond ((< b #x80)
                 (write-char (code-char b) s)
                 (setq i (+ i 1)))
                ((and (>= b #xC0) (< b #xE0) b1)
                 (write-char
                  (code-char (logior (ash (logand b #x1F) 6) (logand b1 #x3F)))
                  s)
                 (setq i (+ i 2)))
                ((and (>= b #xE0) (< b #xF0) b1 b2)
                 (write-char (code-char
                              (logior (ash (logand b #x0F) 12)
                                      (ash (logand b1 #x3F) 6)
                                      (logand b2 #x3F))) s)
                 (setq i (+ i 3)))
                ((and (>= b #xF0) (< b #xF8) b1 b2 b3)
                 (write-char (code-char
                              (logior (ash (logand b #x07) 18)
                                      (ash (logand b1 #x3F) 12)
                                      (ash (logand b2 #x3F) 6)
                                      (logand b3 #x3F))) s)
                 (setq i (+ i 4)))
                (t
                 (write-char (code-char b) s)
                 (setq i (+ i 1)))))))))

(defun rontolisp::%http-utf8-decode (bytes)
  ;; A LIST of UTF-8 bytes -> the string it encodes: the percent-decoder's
  ;; spelling, and the one a chunk boundary's carried-over bytes take. The
  ;; range decoder above owns the rules; a percent escape run and a carry are
  ;; both a handful of bytes, so the coerce is not worth avoiding.
  (let ((v (coerce bytes 'vector)))
    (rontolisp::%http-utf8-decode-octets v 0 (length v))))

(defun rontolisp::%http-escape-run (s i n)
  ;; (bytes . next-index) for the run of consecutive %XX escapes starting at i.
  ;; The whole run is collected before decoding so a multi-byte UTF-8 code
  ;; point split across several escapes reassembles into one character. An
  ;; empty byte list means i does not start a well-formed escape.
  (let ((bytes nil) (k i) (more t))
    (while more
      (if (and (< (+ k 2) n) (= (char-code (char s k)) 37))
          (let ((hi (rontolisp::%http-hex-digit (char-code (char s (+ k 1)))))
                (lo (rontolisp::%http-hex-digit (char-code (char s (+ k 2))))))
            (if (or (< hi 0) (< lo 0))
                (setq more nil)
                (progn
                  (setq bytes (cons (+ (* 16 hi) lo) bytes))
                  (setq k (+ k 3)))))
          (setq more nil)))
    (cons (nreverse bytes) k)))

(defun rontolisp::%http-percent-decode (s)
  ;; Percent-decode a request path. "+" is NOT decoded to a space: that rule
  ;; belongs to a query string, and :path-info is a path.
  (if (null (position #\% s))
      s
      (with-output-to-string (out)
        (let ((i 0) (n (length s)))
          (while (< i n)
            (let ((run (rontolisp::%http-escape-run s i n)))
              (if (car run)
                  (progn
                    (write-string (rontolisp::%http-utf8-decode (car run)) out)
                    (setq i (cdr run)))
                  (progn
                    (write-char (char s i) out)
                    (setq i (+ i 1))))))))))

;;; --- request headers ---------------------------------------------------------

(defun rontolisp::%http-headers-table (alist)
  ;; The wire headers -> the Clack :headers value: an EQUAL hash table keyed by
  ;; the LOWERCASED name. Repeated headers of one name join with ", " in wire
  ;; order, which is what a Clack handler backend is required to do (and what
  ;; makes a repeated Cookie or X-Forwarded-For readable at all).
  (let ((table (make-hash-table :test 'equal)) (rest alist))
    (while rest
      (let ((pair (car rest)))
        (when (consp pair)
          (let* ((name (string-downcase (car pair)))
                 (seen (gethash name table)))
            (setf (gethash name table)
             (if seen (concatenate 'string seen ", " (cdr pair)) (cdr pair))))))
      (setq rest (cdr rest)))
    table))

;;; --- the Host header ---------------------------------------------------------

(defun rontolisp::%http-last-colon (s)
  (let ((idx -1) (i 0) (n (length s)))
    (while (< i n)
      (when (= (char-code (char s i)) 58) (setq idx i))
      (setq i (+ i 1)))
    idx))

(defun rontolisp::%http-digits-p (s)
  (let ((ok (> (length s) 0)) (i 0) (n (length s)))
    (while (< i n)
      (let ((c (char-code (char s i))))
        (when (or (< c 48) (> c 57)) (setq ok nil)))
      (setq i (+ i 1)))
    ok))

(defun rontolisp::%http-host-split (host)
  ;; (name . port-or-nil). The port suffix is the LAST colon followed only by
  ;; digits, so a bare "example.com" and an IPv6 literal "[::1]" both answer a
  ;; nil port while "[::1]:8080" answers 8080.
  (let ((c (rontolisp::%http-last-colon host)))
    (if (< c 0)
        (cons host nil)
        (let ((tail (subseq host (+ c 1))))
          (if (rontolisp::%http-digits-p tail)
              (cons (subseq host 0 c) (parse-integer tail :junk-allowed t))
              (cons host nil))))))

;;; --- the buffered (Clack) :raw-body ------------------------------------------

(defun rontolisp::%http-utf8-encode (string)
  ;; A string -> its UTF-8 octets, as a packed (unsigned-byte 8) array. Written
  ;; here rather than borrowed from flexi-streams: that is an ASDF shim loaded
  ;; on demand, and this library must stand alone.
  (let ((bytes nil) (i 0) (n (length string)))
    (while (< i n)
      (let ((c (char-code (char string i))))
        (cond ((< c #x80) (setq bytes (cons c bytes)))
              ((< c #x800)
               (setq bytes (cons (logior #xC0 (ash c -6)) bytes))
               (setq bytes (cons (logior #x80 (logand c #x3F)) bytes)))
              ((< c #x10000)
               (setq bytes (cons (logior #xE0 (ash c -12)) bytes))
               (setq bytes (cons (logior #x80 (logand (ash c -6) #x3F)) bytes))
               (setq bytes (cons (logior #x80 (logand c #x3F)) bytes)))
              (t
               (setq bytes (cons (logior #xF0 (ash c -18)) bytes))
               (setq bytes (cons (logior #x80 (logand (ash c -12) #x3F)) bytes))
               (setq bytes (cons (logior #x80 (logand (ash c -6) #x3F)) bytes))
               (setq bytes (cons (logior #x80 (logand c #x3F)) bytes)))))
      (setq i (+ i 1)))
    (let* ((ordered (nreverse bytes))
           (out (make-array (length ordered) :element-type '(unsigned-byte 8)))
           (k 0))
      (dolist (b ordered)
        (setf (aref out k) b)
        (setq k (+ k 1)))
      out)))

;; The Clack :raw-body: a BIVALENT in-memory input stream over rontolisp's own
;; Gray protocol (gray.lisp). One byte cursor serves both read families, so a
;; read-char and a read-byte can never disagree about where the body is, and
;; file-position is real -- which is what lets circular-streams rewind a body
;; lack-request has already parsed. Nothing here is a stream HANDLE, so a
;; request costs no entry in any backend's stream table and the object is
;; simply collected when the request ends.
;;
;; It subclasses BOTH input base classes, and that is what makes the bivalence
;; declared rather than merely implemented: the character base is what has
;; stream-element-type answer `character` (the bivalent rule in gray.lisp), the
;; answer upstream's flexi-stream :raw-body gives and the one a portable
;; middleware can size a buffer with -- tiny-routes' read-stream-to-string
;; allocates (stream-element-type stream) and writes the result to a STRING
;; stream. The binary base stays first because the octets are what the stream
;; IS; nothing about the byte reads or the byte-exact relay changes.
(defclass rontolisp::http-request-body-stream (rontolisp:fundamental-binary-input-stream
                                               rontolisp:fundamental-character-input-stream)
  ((rontolisp::octets :initarg :octets
                      :initform nil
                      :accessor rontolisp::%http-body-octets)
   (rontolisp::index :initarg :index
                     :initform 0
                     :accessor rontolisp::%http-body-index)
   (rontolisp::end :initarg :end
                   :initform 0
                   :accessor rontolisp::%http-body-end)))

(defmethod rontolisp:stream-read-byte
    ((stream rontolisp::http-request-body-stream))
  (let ((i (rontolisp::%http-body-index stream)))
    (if (>= i (rontolisp::%http-body-end stream))
        :eof (progn
               (setf (rontolisp::%http-body-index stream) (+ i 1))
               (aref (rontolisp::%http-body-octets stream) i)))))

(defmethod rontolisp:stream-read-char
    ((stream rontolisp::http-request-body-stream))
  ;; Decodes one UTF-8 sequence at the cursor. A byte that starts no valid
  ;; sequence answers its own character and advances by one, so a binary body
  ;; read as characters degrades instead of signalling.
  (let ((v (rontolisp::%http-body-octets stream))
        (i (rontolisp::%http-body-index stream))
        (e (rontolisp::%http-body-end stream)))
    (if (>= i e)
        :eof (let ((b (aref v i)))
               (cond ((< b #x80)
                      (setf (rontolisp::%http-body-index stream) (+ i 1))
                      (code-char b))
                     ((and (>= b #xC0) (< b #xE0) (< (+ i 1) e))
                      (setf (rontolisp::%http-body-index stream) (+ i 2))
                      (code-char
                       (logior (ash (logand b #x1F) 6)
                               (logand (aref v (+ i 1)) #x3F))))
                     ((and (>= b #xE0) (< b #xF0) (< (+ i 2) e))
                      (setf (rontolisp::%http-body-index stream) (+ i 3))
                      (code-char
                       (logior (ash (logand b #x0F) 12)
                               (ash (logand (aref v (+ i 1)) #x3F) 6)
                               (logand (aref v (+ i 2)) #x3F))))
                     ((and (>= b #xF0) (< (+ i 3) e))
                      (setf (rontolisp::%http-body-index stream) (+ i 4))
                      (code-char
                       (logior (ash (logand b #x07) 18)
                               (ash (logand (aref v (+ i 1)) #x3F) 12)
                               (ash (logand (aref v (+ i 2)) #x3F) 6)
                               (logand (aref v (+ i 3)) #x3F))))
                     (t
                      (setf (rontolisp::%http-body-index stream) (+ i 1))
                      (code-char b)))))))

(defmethod rontolisp:stream-listen
    ((stream rontolisp::http-request-body-stream))
  (< (rontolisp::%http-body-index stream) (rontolisp::%http-body-end stream)))

(defmethod rontolisp:stream-file-position
    ((stream rontolisp::http-request-body-stream))
  (rontolisp::%http-body-index stream))

(defmethod (setf rontolisp:stream-file-position)
    (position (stream rontolisp::http-request-body-stream))
  (setf (rontolisp::%http-body-index stream) position))

(defmethod rontolisp:stream-read-line
    ((stream rontolisp::http-request-body-stream))
  ;; One pass over the octets to the terminator. The inherited default dispatches
  ;; stream-read-char PER CHARACTER, which on the interpreter cost a measured 36%
  ;; of the POST throughput before the Java-backed body stream replaced this class
  ;; there; this method keeps the compiled (WASM component) construction from
  ;; paying the same shape of tax. Terminators are LF, CR and CRLF -- the
  ;; BufferedReader contract the Java-backed stream follows -- and a partial last
  ;; line answers as that line (:eof only when nothing was read).
  (let ((v (rontolisp::%http-body-octets stream))
        (i (rontolisp::%http-body-index stream))
        (e (rontolisp::%http-body-end stream)))
    (if (>= i e)
        :eof (let ((line
                    (with-output-to-string (out)
                      (let ((done nil))
                        (while (and (not done) (< i e))
                          (let ((b (aref v i)))
                            (cond ((= b 10)
                                   (setq i (+ i 1))
                                   (setq done t))
                                  ((= b 13)
                                   (setq i (+ i 1))
                                   (when (and (< i e) (= (aref v i) 10))
                                     (setq i (+ i 1)))
                                   (setq done t))
                                  ((< b #x80)
                                   (write-char (code-char b) out)
                                   (setq i (+ i 1)))
                                  ((and (>= b #xC0) (< b #xE0) (< (+ i 1) e))
                                   (write-char (code-char
                                                (logior (ash (logand b #x1F) 6)
                                                        (logand (aref v (+ i 1))
                                                                #x3F))) out)
                                   (setq i (+ i 2)))
                                  ((and (>= b #xE0) (< b #xF0) (< (+ i 2) e))
                                   (write-char (code-char
                                                (logior (ash (logand b #x0F) 12)
                                                        (ash (logand
                                                              (aref v (+ i 1))
                                                              #x3F) 6)
                                                        (logand (aref v (+ i 2))
                                                                #x3F))) out)
                                   (setq i (+ i 3)))
                                  ((and (>= b #xF0) (< (+ i 3) e))
                                   (write-char (code-char
                                                (logior (ash (logand b #x07) 18)
                                                        (ash (logand
                                                              (aref v (+ i 1))
                                                              #x3F) 12)
                                                        (ash (logand
                                                              (aref v (+ i 2))
                                                              #x3F) 6)
                                                        (logand (aref v (+ i 3))
                                                                #x3F))) out)
                                   (setq i (+ i 4)))
                                  (t
                                   (write-char (code-char b) out)
                                   (setq i (+ i 1))))))))))
               (setf (rontolisp::%http-body-index stream) i)
               line))))

(defun rontolisp::%http-body-stream (body)
  ;; nil for an absent or empty body -- upstream guards :raw-body with
  ;; (when raw-body ...), and most requests are bodiless, so this is also the
  ;; allocation a GET does not pay.
  ;;
  ;; BODY is the TEXT a transport read, or the OCTETS a byte-shaped one did.
  ;; Taking both is not a convenience: the class below is bivalent over octets,
  ;; so encoding is what a text body needs and re-encoding is what a byte body
  ;; must never get. The decoder above is lenient by construction -- a byte that
  ;; starts no sequence answers its own character -- so a binary body decoded to
  ;; text and encoded again comes back doubled (ff fe 41 -> c3 bf c3 be 41),
  ;; which is the same loss %http-body-string stopped inflicting on the way out.
  (if (or (null body) (= (length body) 0))
      nil
      (let ((v (if (stringp body) (rontolisp::%http-utf8-encode body) body)))
        (make-instance 'rontolisp::http-request-body-stream
                       :octets v
                       :index 0
                       :end (length v)))))

;;; --- the environment ---------------------------------------------------------

(defun rontolisp::%http-method-keyword (m)
  ;; The transport hands a string ("GET", the JDK server) or, on the WASI
  ;; component -- whose lifted `method` variant case already IS one -- the
  ;; keyword itself. Clack wants an upcased keyword either way, and it must be
  ;; interned so (eq method :POST) works in a router.
  (if (stringp m) (intern (string-upcase m) :keyword) m))

(defun rontolisp::%http-protocol-keyword (p)
  (if (stringp p) (intern (string-upcase p) :keyword) :HTTP/1.1))

(defun rontolisp::%http-make-env (raw)
  ;; THE shape declaration: the Clack environment, in cons order. The list is
  ;; freshly consed and proper on every request because upstream MUTATES it --
  ;; lack-request rplacds its last cons to append :cookies / :query-parameters
  ;; / :body-parameters, and the mount / session middleware setf getf into it.
  (let* ((target (nth 1 raw))
         (q (position #\? target))
         (path (if q (subseq target 0 q) target))
         (query (if q (subseq target (+ q 1)) nil))
         (headers (rontolisp::%http-headers-table (nth 2 raw)))
         (host (gethash "host" headers))
         (hostpair (if host (rontolisp::%http-host-split host) nil))
         (clen (gethash "content-length" headers))
         (body (nth 3 raw))
         ;; The mounted split (the Servlet war under a context path): (nth 10
         ;; raw) is the mount point as a RAW prefix of the target -- still
         ;; percent-encoded, which is what makes it strippable BEFORE decoding
         ;; -- and both halves come out decoded, the same shape lack's mount
         ;; middleware produces when it moves a matched prefix across. Absent
         ;; (the ten-member tuple every root-mounted transport sends) or not a
         ;; prefix of the target, the split degrades to the root-mounted one
         ;; rather than signalling.
         (script (let ((s (nth 10 raw))) (if (stringp s) s "")))
         (mounted
          (and (> (length script) 0) (<= (length script) (length path))
               (string= script (subseq path 0 (length script))))))
    (list :REQUEST-METHOD (rontolisp::%http-method-keyword (nth 0 raw))
     :SCRIPT-NAME (if mounted (rontolisp::%http-percent-decode script) "")
     :PATH-INFO (rontolisp::%http-percent-decode
                 (if mounted (subseq path (length script)) path))
     :QUERY-STRING query
     :SERVER-NAME (or (if hostpair (car hostpair) nil) (nth 6 raw) "localhost")
     :SERVER-PORT (or (if hostpair (cdr hostpair) nil) (nth 7 raw) 80)
     :SERVER-PROTOCOL (rontolisp::%http-protocol-keyword (nth 4 raw))
     :REQUEST-URI target
     :URL-SCHEME (or (nth 5 raw) "http")
     :REMOTE-ADDR (nth 8 raw)
     :REMOTE-PORT (nth 9 raw)
     :HEADERS headers
     :CONTENT-TYPE (gethash "content-type" headers)
     :CONTENT-LENGTH (if clen (parse-integer clen :junk-allowed t) nil)
     ;; The transport hands the FINAL :raw-body value (its stream, the
     ;; buffered Gray stream via %http-body-stream, or nil): wrapping here
     ;; would tie every environment build to the buffered-body machinery,
     ;; which a default-mode component deliberately does not carry.
     :RAW-BODY body)))

;;; --- the response ------------------------------------------------------------

(defun rontolisp::%http-header-name (k)
  (if (stringp k) (string-downcase k) (string-downcase (symbol-name k))))

(defun rontolisp::%http-header-value (v) (if (stringp v) v (princ-to-string v)))

(defun rontolisp::%http-drop-header-p (name)
  ;; The transport computes the framing headers from the body it is about to
  ;; write; letting the application's copy through as well is how a duplicate
  ;; Content-Length gets on the wire.
  (or (string= name "content-length") (string= name "transfer-encoding")))

(defun rontolisp::%http-response-headers-alist (headers)
  ;; The Clack response headers -- a keyword PLIST, (:content-type "text/plain")
  ;; -- lowered to the dotted (name . value) alist every transport writes. A
  ;; dotted ALIST is accepted too, so a rontolisp:fetch result's :headers can be
  ;; handed straight back: that keeps a proxy handler conversion-free.
  ;;
  ;; Every pair becomes its own header line; nothing is merged. That is
  ;; RFC-correct, free on both transports, and makes repeated :set-cookie
  ;; correct by construction -- the merging implementations all have to special
  ;; case it.
  (cond ((null headers) nil)
        ((consp (car headers))
         (let ((out nil) (rest headers))
           (while rest
             (let ((pair (car rest)))
               (when (consp pair)
                 (let ((name (rontolisp::%http-header-name (car pair))))
                   (unless (rontolisp::%http-drop-header-p name)
                     (setq out
                           (cons (cons name
                                  (rontolisp::%http-header-value (cdr pair)))
                                 out))))))
             (setq rest (cdr rest)))
           (nreverse out)))
        (t (let ((out nil) (rest headers))
             (while (and rest (cdr rest))
               (let ((name (rontolisp::%http-header-name (car rest))))
                 (unless (rontolisp::%http-drop-header-p name)
                   (setq out
                         (cons (cons name
                                     (rontolisp::%http-header-value
                                      (car (cdr rest)))) out))))
               (setq rest (cdr (cdr rest))))
             (nreverse out)))))

(defun rontolisp::%http-octets-join (chunks total)
  ;; A list of (unsigned-byte 8) vectors and their total length -> ONE packed
  ;; vector holding them in order; a single chunk is answered as it is. The
  ;; blit both drains share (the async one below, the reactor's synchronous
  ;; twin): chunks collected and copied once, so a body pulled in n chunks
  ;; costs one copy. `replace` is native on the interpreter and, the destination
  ;; being provably an array, the narrow runtime arm on the compile paths. The
  ;; prelude's %octets-join is this same defun -- this library is prelude-free
  ;; by rule, so it carries its own.
  (if (and chunks (null (cdr chunks)))
      (car chunks)
      (let ((out (make-array total :element-type '(unsigned-byte 8))) (k 0))
        (dolist (v chunks)
          (replace out v :start1 k)
          (setq k (+ k (length v))))
        out)))

(rontolisp:async-defun rontolisp::%http-drain (s)
  ;; Drain a rontolisp asynchronous stream into ONE body value the transport
  ;; writes as it is. Every HTTP body stream -- a fetched reply's :body, the
  ;; default :raw-body -- answers OCTET chunks, so the ordinary proxy shape
  ;; (list status headers (getf res :body)) drains to an (unsigned-byte 8)
  ;; vector holding the upstream's exact bytes: NOTHING here decodes, which is
  ;; what makes a relayed binary body byte-exact (a JPEG's ff d8 ff used to
  ;; come out c3 bf d8, a stray ff decoded to U+00FF and re-encoded). String
  ;; chunks (a guest make-stream) drain to one string through a string output
  ;; stream, not (concatenate 'string acc chunk) -- quadratic in the body size.
  ;; The octet join is %http-octets-join above: chunks collected and blitted
  ;; once, so a body pulled in n chunks costs one copy. A stream mixing the two
  ;; kinds is refused rather than guessed at.
  (let ((chunks nil)
        (octets nil)
        (text nil)
        (total 0)
        (chunk (rontolisp:await (rontolisp:stream-read s))))
    (while chunk
      (if (stringp chunk) (setq text t) (setq octets t))
      (setq total (+ total (length chunk)))
      (setq chunks (cons chunk chunks))
      (setq chunk (rontolisp:await (rontolisp:stream-read s))))
    (setq chunks (nreverse chunks))
    (cond
     ((and octets text)
      (error
       "http-handler: a stream response body mixes string and octet chunks"))
     (octets (rontolisp::%http-octets-join chunks total))
     (t (let ((out (make-string-output-stream)))
          (dolist (c chunks) (write-string c out))
          (get-output-stream-string out))))))

(defun rontolisp::%http-join-strings (parts)
  ;; A NIL element contributes the empty string, as upstream renders it:
  ;; clack-handler-hunchentoot writes every chunk through flex:string-to-octets,
  ;; and that answers #() for NIL. It is not an exotic body either -- a
  ;; controller that returns nil (ningle's not-found does) reaches lack's
  ;; finalize-response, which answers the body list (NIL).
  (let ((out (make-string-output-stream)) (rest parts))
    (while rest
      (let ((part (car rest)))
        (cond ((stringp part) (write-string part out))
         ((null part) nil)
         (t (error "http-handler: a list response body must hold strings"))))
      (setq rest (cdr rest)))
    (get-output-stream-string out)))

(defun rontolisp::%http-body-string (body)
  ;; A Clack response body -> what the transport writes: a STRING, an
  ;; (unsigned-byte 8) vector, or a rontolisp STREAM. The last two are returned
  ;; AS IS -- draining a stream needs an await, and keeping this a plain defun
  ;; keeps the whole normalizer synchronous (%http-serve-request, the one async
  ;; frame on the request path, drains it); on the interpreter every
  ;; async-defun call costs a future and a virtual thread, so "one async frame
  ;; per request" is a measured throughput property, not a style choice.
  ;;
  ;; OCTETS stay octets for the same reason a stream does: only the transport
  ;; knows what it can carry. Every transport that writes the wire itself takes
  ;; them as they are; only the reactor envelope, whose head is a JSON string,
  ;; renders them as the text they spell (http-reactor.lisp's
  ;; %http-reactor-body-envelope-text). Rendering HERE would make binary
  ;; unrecoverable, because a rendered octet is indistinguishable from a
  ;; character the application meant.
  ;;
  ;; A BARE STRING is deliberately rejected, as Clack itself rejects it (lack's
  ;; finalize-response wraps a string controller result in a list, so a bare
  ;; string reaching the transport is a malformed response). A PATHNAME body
  ;; -- lack/app/file's file-serving form, a distinct value here -- falls to
  ;; the unsupported-type arm below until the transport can serve a file.
  (cond ((null body) "")
        ((consp body) (rontolisp::%http-join-strings body))
        ((stringp body)
         (error
          "http-handler: a response body must be a list of strings, not a bare string -- wrap it, e.g. (list body)"))
        ((typep body '(vector (unsigned-byte 8))) body)
        ((rontolisp:streamp body) body)
        (t (error "http-handler: unsupported response body type"))))

(defun rontolisp::%http-writer-refused ()
  (error
   "http-handler: the streaming writer response protocol is not supported"))

(defun rontolisp::%http-normalize-response (res)
  ;; (status headers) or (status headers body) -> (status header-alist
  ;; body-string). The two-element form is not exotic: it is what lack's
  ;; finalize-response answers for a bodyless response, so every ningle 404 has
  ;; that shape.
  ;;
  ;; A FUNCTION is Clack's DELAYED response: call it with a responder that
  ;; captures the real response. That is what lack's accesslog and session
  ;; middleware and ningle propagate whenever an inner application delayed. The
  ;; streaming WRITER form -- where the responder must answer a writer closure
  ;; the application then pushes chunks into -- is refused, loudly, by the
  ;; closure the responder returns.
  (if (functionp res)
      (let ((captured nil))
        (funcall res
                 (lambda (r)
                   (setq captured r)
                   (lambda (&rest ignored)
                     (declare (ignore ignored))
                     (rontolisp::%http-writer-refused))))
        (if (null captured)
            (error "http-handler: a delayed response delivered no response")
            (rontolisp::%http-normalize-response captured)))
      (progn
        (unless (and (consp res) (integerp (car res)))
          (error
           "http-handler: a handler must return (status headers) or (status headers body)"))
        (list (car res)
              (rontolisp::%http-response-headers-alist (car (cdr res)))
              (rontolisp::%http-body-string (car (cdr (cdr res))))))))

(rontolisp:async-defun rontolisp::%http-serve-request (app raw)
  ;; The one server-side request path -- and the ONE asynchronous frame on it.
  ;; The application's result is awaited, so a plain defun and a
  ;; rontolisp:async-defun both work as handlers (await passes a non-future
  ;; straight through); the rest of the normalization is synchronous, and only
  ;; a STREAM response body (a proxied fetch) costs a second await.
  ;; Every await is the direct init of a let binding: the --component backend
  ;; rejects an await in any other position.
  (let* ((env (rontolisp::%http-make-env raw))
         (result (rontolisp:await (funcall app env)))
         (triple (rontolisp::%http-normalize-response result))
         (body (car (cdr (cdr triple)))))
    (if (rontolisp:streamp body)
        ;; A stream (a proxied fetch) is the ONE body this frame has to resolve:
        ;; draining it needs an await, and only an async frame has one.
        (let ((drained (rontolisp:await (rontolisp::%http-drain body))))
          (list (car triple) (car (cdr triple)) drained))
        ;; A string or an (unsigned-byte 8) vector reaches the transport as it
        ;; is. Flattening octets here would hand every transport characters it
        ;; then UTF-8 encodes, doubling each octet >= #x80; a transport that
        ;; genuinely cannot write bytes renders them for itself (the reactor
        ;; envelope's %http-reactor-body-envelope-text).
        triple)))
