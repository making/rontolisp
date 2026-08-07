;; The URL / query-string library (rontolisp:url-decode / url-encode /
;; query-params / query-param / url-path / url-query), written in rontolisp
;; itself so a single implementation runs on every backend: the interpreter
;; loads these definitions lazily on first use, and the compile path splices
;; them into the program when it references the public functions (see
;; UrlLibrary.java).
;;
;; Portability constraints honored here (like json.lisp, see .kb/json.md):
;; - Only ASCII structural characters are examined with char-code; any unit
;;   >= 128 (a UTF-16 unit on the interpreter/JVM, a UTF-8 byte on WASM) is
;;   copied verbatim with subseq, so string indexing differences between the
;;   backends never surface in pass-through text.
;; - Percent escapes detect the string representation at runtime:
;;   (length "あ") is 3 on byte-indexed (UTF-8) backends and 1 on UTF-16
;;   backends, and the decoder emits UTF-8 bytes or UTF-16 units (surrogate
;;   pairs combined/split) accordingly; the encoder does the inverse.
;; - User lambda lists support required parameters only, so every helper is
;;   fixed-arity; do loops always declare at least one variable; parameters
;;   are never assigned with setq (let-rebound instead).

;; --- shared helpers ----------------------------------------------------------

(defun rontolisp::%url-utf8p ()
  ;; Retained as NIL for backwards compatibility with any caller that resolves
  ;; the internal helper: every backend now indexes strings BY CODE POINT
  ;; (todo 153), so the previous "UTF-8 bytes per (char s i)" WASM path is
  ;; gone. url-decode / url-encode always take the code-point path below.
  nil)

(defun rontolisp::%url-char-string (code)
  ;; A one-unit string holding the given char code (byte or UTF-16 unit).
  (princ-to-string (code-char code)))

(defun rontolisp::%url-pairs (parts)
  ;; One pass of pairwise concatenation over a list of strings.
  (let ((out nil) (todo parts))
    (do ((dummy nil))
        ((null todo) (nreverse out))
      (if (null (cdr todo))
          (progn
            (setq out (cons (car todo) out))
            (setq todo nil))
          (progn
            (setq out
                  (cons (concatenate 'string (car todo) (car (cdr todo))) out))
            (setq todo (cdr (cdr todo))))))))

(defun rontolisp::%url-concat (parts)
  ;; Concatenates a list of strings in O(total * log n) via pairwise passes.
  (cond ((null parts) "")
        ((null (cdr parts)) (car parts))
        (t (rontolisp::%url-concat (rontolisp::%url-pairs parts)))))

;; --- decoding ----------------------------------------------------------------

(defun rontolisp::%url-hex-digit (c)
  (cond ((and (>= c 48) (<= c 57)) (- c 48))
        ((and (>= c 97) (<= c 102)) (- c 87))
        ((and (>= c 65) (<= c 70)) (- c 55))
        (t (error "url-decode: invalid hex digit in percent escape"))))

(defun rontolisp::%url-escape-bytes (s j n)
  ;; j points at the first % of a run of consecutive %XX escapes; returns
  ;; (bytes . next-index) with the decoded bytes in order. The whole run is
  ;; collected before conversion so multi-byte UTF-8 sequences split across
  ;; several escapes reassemble into one code point.
  (let ((bytes nil))
    (do ((k j))
        ((or (>= k n) (not (= (char-code (char s k)) 37)))
         (cons (nreverse bytes) k))
      (when (> (+ k 3) n) (error "url-decode: unterminated percent escape"))
      (setq bytes
            (cons (+ (* 16
                      (rontolisp::%url-hex-digit (char-code (char s (+ k 1)))))
                     (rontolisp::%url-hex-digit (char-code (char s (+ k 2)))))
                  bytes))
      (setq k (+ k 3)))))

(defun rontolisp::%url-utf16-string (cp)
  ;; Emit one CHARACTER string for a code point. Prior to the code-point
  ;; string-indexing cutover the UTF-16-indexed backends split a supplementary
  ;; code point into a surrogate pair here (each half became one indexed
  ;; character); now (char s i) returns the code point directly on every
  ;; backend, so a single (code-char cp) suffices.
  (rontolisp::%url-char-string cp))

(defun rontolisp::%url-cont-byte (x)
  ;; The 6 payload bits of a UTF-8 continuation byte (10xxxxxx).
  (when (null x) (error "url-decode: truncated UTF-8 percent escape"))
  (let ((b (car x)))
    (if (and (>= b 128) (< b 192))
        (logand b 63)
        (error "url-decode: invalid UTF-8 in percent escape"))))

(defun rontolisp::%url-utf8-strings (bytes)
  ;; Decodes a list of UTF-8 bytes into a list of UTF-16-unit strings
  ;; (one per code point), for the UTF-16-indexed backends.
  (let ((parts nil) (x bytes))
    (do ((dummy nil))
        ((null x) (nreverse parts))
      (let ((b (car x)))
        (cond ((< b 128)
               (setq parts (cons (rontolisp::%url-char-string b) parts))
               (setq x (cdr x)))
              ((and (>= b 192) (< b 224))
               (setq parts
                     (cons (rontolisp::%url-utf16-string
                            (+ (* 64 (logand b 31))
                               (rontolisp::%url-cont-byte (cdr x)))) parts))
               (setq x (cdr (cdr x))))
              ((and (>= b 224) (< b 240))
               (setq parts
                     (cons (rontolisp::%url-utf16-string
                            (+ (* 4096 (logand b 15))
                               (* 64 (rontolisp::%url-cont-byte (cdr x)))
                               (rontolisp::%url-cont-byte (cdr (cdr x)))))
                           parts))
               (setq x (cdr (cdr (cdr x)))))
              ((and (>= b 240) (< b 248))
               (setq parts
                     (cons (rontolisp::%url-utf16-string
                            (+ (* 262144 (logand b 7))
                               (* 4096 (rontolisp::%url-cont-byte (cdr x)))
                               (* 64 (rontolisp::%url-cont-byte (cdr (cdr x))))
                               (rontolisp::%url-cont-byte (cdr (cdr (cdr x))))))
                           parts))
               (setq x (cdr (cdr (cdr (cdr x))))))
              (t (error "url-decode: invalid UTF-8 in percent escape")))))))

(defun rontolisp::%url-byte-strings (bytes)
  ;; One one-byte string per decoded byte, for the UTF-8-indexed backends.
  (let ((parts nil))
    (do ((x bytes (cdr x)))
        ((null x) (nreverse parts))
      (setq parts (cons (rontolisp::%url-char-string (car x)) parts)))))

(defun rontolisp::%url-bytes-string (bytes)
  ;; Converts a list of percent-decoded bytes into a string in the backend's
  ;; native representation.
  (if (rontolisp::%url-utf8p)
      (rontolisp::%url-concat (rontolisp::%url-byte-strings bytes))
      (rontolisp::%url-concat (rontolisp::%url-utf8-strings bytes))))

(defun rontolisp:url-decode (s)
  (when (not (stringp s)) (error "url-decode expects a string"))
  (let ((n (length s)) (parts nil) (start 0))
    (do ((j 0))
        ((>= j n))
      (let ((c (char-code (char s j))))
        (cond ((= c 43)
               (when (> j start) (setq parts (cons (subseq s start j) parts)))
               (setq parts (cons " " parts))
               (setq j (+ j 1))
               (setq start j))
              ((= c 37)
               (when (> j start) (setq parts (cons (subseq s start j) parts)))
               (let ((r (rontolisp::%url-escape-bytes s j n)))
                 (setq parts
                       (cons (rontolisp::%url-bytes-string (car r)) parts))
                 (setq j (cdr r))
                 (setq start j)))
              (t (setq j (+ j 1))))))
    (when (> n start) (setq parts (cons (subseq s start n) parts)))
    (rontolisp::%url-concat (nreverse parts))))

;; --- encoding ----------------------------------------------------------------

(defun rontolisp::%url-hex-byte (b)
  ;; The %XX escape of one byte, uppercase hex.
  (concatenate 'string "%"
               (subseq "0123456789ABCDEF" (ash b -4) (+ (ash b -4) 1))
               (subseq "0123456789ABCDEF" (logand b 15) (+ (logand b 15) 1))))

(defun rontolisp::%url-encode-cp (cp)
  ;; The percent-encoded UTF-8 bytes of one code point >= 128.
  (cond ((< cp 2048)
         (concatenate 'string (rontolisp::%url-hex-byte (+ 192 (ash cp -6)))
                      (rontolisp::%url-hex-byte (+ 128 (logand cp 63)))))
        ((< cp 65536)
         (concatenate 'string (rontolisp::%url-hex-byte (+ 224 (ash cp -12)))
                      (rontolisp::%url-hex-byte (+ 128 (logand (ash cp -6) 63)))
                      (rontolisp::%url-hex-byte (+ 128 (logand cp 63)))))
        (t
         (concatenate 'string (rontolisp::%url-hex-byte (+ 240 (ash cp -18)))
          (rontolisp::%url-hex-byte (+ 128 (logand (ash cp -12) 63)))
          (rontolisp::%url-hex-byte (+ 128 (logand (ash cp -6) 63)))
          (rontolisp::%url-hex-byte (+ 128 (logand cp 63)))))))

(defun rontolisp::%url-unreserved-p (c)
  ;; RFC 3986 unreserved: ALPHA / DIGIT / "-" / "." / "_" / "~".
  (or (and (>= c 65) (<= c 90)) (and (>= c 97) (<= c 122))
      (and (>= c 48) (<= c 57)) (= c 45) (= c 46) (= c 95) (= c 126)))

(defun rontolisp:url-encode (s)
  (when (not (stringp s)) (error "url-encode expects a string"))
  (let ((n (length s)) (parts nil) (start 0) (utf8 (rontolisp::%url-utf8p)))
    (do ((j 0))
        ((>= j n))
      (let ((c (char-code (char s j))))
        (if (rontolisp::%url-unreserved-p c)
            (setq j (+ j 1))
            (progn
              (when (> j start) (setq parts (cons (subseq s start j) parts)))
              (cond ((or utf8 (< c 128))
                     ;; An ASCII unit, or a raw UTF-8 byte on the byte-indexed
                     ;; backends: one %XX escape.
                     (setq parts (cons (rontolisp::%url-hex-byte c) parts))
                     (setq j (+ j 1)))
                    ((and (>= c 55296) (<= c 56319))
                     ;; A UTF-16 high surrogate: combine with the low half.
                     (when (>= (+ j 1) n) (error "url-encode: lone surrogate"))
                     (let ((lo (char-code (char s (+ j 1)))))
                       (when (not (and (>= lo 56320) (<= lo 57343)))
                         (error "url-encode: lone surrogate"))
                       (setq parts
                             (cons (rontolisp::%url-encode-cp
                                    (+ 65536 (* 1024 (- c 55296)) (- lo 56320)))
                                   parts)))
                     (setq j (+ j 2)))
                    ((and (>= c 56320) (<= c 57343))
                     (error "url-encode: lone surrogate"))
                    (t
                     (setq parts (cons (rontolisp::%url-encode-cp c) parts))
                     (setq j (+ j 1))))
              (setq start j)))))
    (when (> n start) (setq parts (cons (subseq s start n) parts)))
    (rontolisp::%url-concat (nreverse parts))))

;; --- query strings -----------------------------------------------------------

(defun rontolisp::%url-parse-pair (pair)
  ;; "k=v" -> (decoded-k . decoded-v); a bare "flag" -> ("flag" . "").
  (let ((eq-pos (position #\= pair)))
    (if eq-pos
        (cons (rontolisp:url-decode (subseq pair 0 eq-pos))
              (rontolisp:url-decode (subseq pair (+ eq-pos 1))))
        (cons (rontolisp:url-decode pair) ""))))

(defun rontolisp::%url-parse-query (q)
  ;; Splits on &, skipping empty segments; duplicates preserved in order.
  (let ((n (length q)) (acc nil) (start 0))
    (do ((j 0))
        ((> j n) (nreverse acc))
      (if (or (= j n) (= (char-code (char q j)) 38))
          (progn
            (when (> j start)
              (setq acc
                    (cons (rontolisp::%url-parse-pair (subseq q start j)) acc)))
            (setq j (+ j 1))
            (setq start j))
          (setq j (+ j 1))))))

(defun rontolisp:query-params (q)
  (cond ((null q) nil)
        ((not (stringp q)) (error "query-params expects a string or nil"))
        (t (rontolisp::%url-parse-query q))))

(defun rontolisp:query-param (q name)
  (when (not (stringp name)) (error "query-param expects a string name"))
  (cond ((null q) nil)
        ((not (stringp q)) (error "query-param expects a string or nil query"))
        (t
         (let ((params (rontolisp::%url-parse-query q)))
           (do ((x params (cdr x)))
               ((null x) nil)
             (when (string= (car (car x)) name) (return (cdr (car x)))))))))

;; --- splitting ---------------------------------------------------------------

(defun rontolisp:url-path (s)
  (when (not (stringp s)) (error "url-path expects a string"))
  (let ((q (position #\? s))) (if q (subseq s 0 q) s)))

(defun rontolisp:url-query (s)
  (when (not (stringp s)) (error "url-query expects a string"))
  (let ((q (position #\? s))) (if q (subseq s (+ q 1)) nil)))
