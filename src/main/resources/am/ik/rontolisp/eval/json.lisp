;; Hand-written minimal JSON parser/serializer for rontolisp:json-parse and
;; rontolisp:json-stringify, written in rontolisp itself so a single
;; implementation runs on every backend: the interpreter loads these
;; definitions lazily on first use, and the compile path splices them into the
;; program when it references the public functions (see JsonLibrary.java).
;;
;; The value mapping mirrors com.inuoe.jzon's defaults, so json-parse /
;; json-stringify are a lightweight, forward-compatible subset of jzon: a
;; program can start with rontolisp:json-* and later switch to jzon without a
;; shape change. A JSON object parses to a hash table with string keys
;; (test 'equal), an array to a simple vector, true/false/null to t/nil and the
;; symbol null; on the way out nil becomes false, the symbol null becomes null,
;; a vector or list becomes an array, and a hash table becomes an object.
;;
;; Portability constraints honored here (see .kb/json.md):
;; - Only ASCII structural characters are examined with char-code; any unit
;;   >= 128 (a UTF-16 unit on the interpreter/JVM, a UTF-8 byte on WASM) is
;;   copied verbatim with subseq, so string indexing differences between the
;;   backends never surface.
;; - \uXXXX escapes detect the string representation at runtime:
;;   (length "あ") is 3 on byte-indexed (UTF-8) backends and 1 on
;;   UTF-16 backends, and the decoder emits UTF-8 bytes or UTF-16 units
;;   accordingly.
;; - User lambda lists support required parameters only, so every helper is
;;   fixed-arity; do loops always declare at least one variable; parameters
;;   are never assigned with setq (let-rebound instead).

;; --- shared helpers ---------------------------------------------------------

(defun rontolisp::%json-utf8p ()
  ;; Retained as NIL for backwards compatibility with any caller that
  ;; resolves the internal helper: every backend now indexes strings BY
  ;; CODE POINT (todo 153), so %json-encode-char takes the direct
  ;; (code-char cp) path below on every backend.
  nil)

(defun rontolisp::%json-char-string (code)
  ;; A one-unit string holding the given char code (byte or UTF-16 unit).
  (princ-to-string (code-char code)))

(defun rontolisp::%json-pairs (parts)
  ;; One pass of pairwise concatenation over a list of strings.
  (let ((out nil) (todo parts))
    (do ((dummy nil))
        ((null todo) (nreverse out))
      (if (null (cdr todo))
          (progn (setq out (cons (car todo) out)) (setq todo nil))
          (progn
            (setq out (cons (concatenate 'string (car todo) (car (cdr todo))) out))
            (setq todo (cdr (cdr todo))))))))

(defun rontolisp::%json-concat (parts)
  ;; Concatenates a list of strings in O(total * log n) via pairwise passes.
  (cond ((null parts) "")
        ((null (cdr parts)) (car parts))
        (t (rontolisp::%json-concat (rontolisp::%json-pairs parts)))))

;; --- parser -----------------------------------------------------------------

(defun rontolisp::%json-skip-ws (s i n)
  (do ((j i (+ j 1)))
      ((or (>= j n)
           (let ((c (char-code (char s j))))
             (not (or (= c 32) (= c 9) (= c 10) (= c 13)))))
       j)))

(defun rontolisp::%json-scan-digits (s j n)
  (do ((k j (+ k 1)))
      ((or (>= k n)
           (let ((c (char-code (char s k)))) (or (< c 48) (> c 57))))
       k)))

(defun rontolisp::%json-hex-digit (c)
  (cond ((and (>= c 48) (<= c 57)) (- c 48))
        ((and (>= c 97) (<= c 102)) (- c 87))
        ((and (>= c 65) (<= c 70)) (- c 55))
        (t (error "json-parse: invalid hex digit in unicode escape"))))

(defun rontolisp::%json-hex4 (s i n)
  (when (> (+ i 4) n) (error "json-parse: unterminated unicode escape"))
  (let ((v 0))
    (do ((k 0 (+ k 1)))
        ((>= k 4) v)
      (setq v (+ (* v 16) (rontolisp::%json-hex-digit (char-code (char s (+ i k)))))))))

(defun rontolisp::%json-encode-char (cp)
  ;; One CHARACTER string for the given code point. Prior to the code-point
  ;; string-indexing cutover the WASM backends emitted the code point's UTF-8
  ;; bytes as one-byte characters and the UTF-16-indexed backends split a
  ;; supplementary code point into a surrogate pair here; now every backend
  ;; indexes strings by code point (todo 153), so (code-char cp) suffices.
  (rontolisp::%json-char-string cp))

(defun rontolisp::%json-unicode-escape (s j n)
  ;; j points at the backslash of \uXXXX; combines surrogate pairs.
  (let ((cp (rontolisp::%json-hex4 s (+ j 2) n)) (next (+ j 6)))
    (if (and (>= cp 55296) (<= cp 56319) (<= (+ next 6) n)
             (= (char-code (char s next)) 92)
             (= (char-code (char s (+ next 1))) 117))
        (let ((lo (rontolisp::%json-hex4 s (+ next 2) n)))
          (if (and (>= lo 56320) (<= lo 57343))
              (cons (rontolisp::%json-encode-char (+ 65536 (* 1024 (- cp 55296)) (- lo 56320)))
                    (+ next 6))
              (cons (concatenate 'string (rontolisp::%json-encode-char cp)
                                 (rontolisp::%json-encode-char lo))
                    (+ next 6))))
        (cons (rontolisp::%json-encode-char cp) next))))

(defun rontolisp::%json-escape (s j n)
  ;; j points at a backslash inside a string; returns (text . next-index).
  (when (>= (+ j 1) n) (error "json-parse: unterminated escape"))
  (let ((c (char-code (char s (+ j 1)))))
    (cond ((= c 34) (cons "\"" (+ j 2)))
          ((= c 92) (cons "\\" (+ j 2)))
          ((= c 47) (cons "/" (+ j 2)))
          ((= c 98) (cons (rontolisp::%json-char-string 8) (+ j 2)))
          ((= c 102) (cons (rontolisp::%json-char-string 12) (+ j 2)))
          ((= c 110) (cons (rontolisp::%json-char-string 10) (+ j 2)))
          ((= c 114) (cons (rontolisp::%json-char-string 13) (+ j 2)))
          ((= c 116) (cons (rontolisp::%json-char-string 9) (+ j 2)))
          ((= c 117) (rontolisp::%json-unicode-escape s j n))
          (t (error "json-parse: invalid escape in string")))))

(defun rontolisp::%json-string (s i n)
  ;; i points at the opening quote; returns (string . next-index).
  (let ((parts nil) (start (+ i 1)))
    (do ((j (+ i 1)))
        (nil)
      (when (>= j n) (error "json-parse: unterminated string"))
      (let ((c (char-code (char s j))))
        (cond ((= c 34)
               (when (> j start) (setq parts (cons (subseq s start j) parts)))
               (return (cons (rontolisp::%json-concat (nreverse parts)) (+ j 1))))
              ((= c 92)
               (when (> j start) (setq parts (cons (subseq s start j) parts)))
               (let ((esc (rontolisp::%json-escape s j n)))
                 (setq parts (cons (car esc) parts))
                 (setq j (cdr esc))
                 (setq start j)))
              (t (setq j (+ j 1))))))))

(defun rontolisp::%json-float (s tok-start int-start int-end frac-start frac-end tok-end)
  (let ((m 0.0) (fd 0) (e 0))
    (do ((k int-start (+ k 1)))
        ((>= k int-end))
      (setq m (+ (* m 10.0) (- (char-code (char s k)) 48))))
    (when frac-start
      (do ((k frac-start (+ k 1)))
          ((>= k frac-end))
        (setq m (+ (* m 10.0) (- (char-code (char s k)) 48)))
        (setq fd (+ fd 1))))
    (let ((p (if frac-end frac-end int-end)))
      (when (< p tok-end)
        ;; exponent part: e|E [+|-] digits
        (let ((q (+ p 1)) (neg nil))
          (let ((c (char-code (char s q))))
            (when (or (= c 43) (= c 45))
              (when (= c 45) (setq neg t))
              (setq q (+ q 1))))
          (do ((k q (+ k 1)))
              ((>= k tok-end))
            (setq e (+ (* e 10) (- (char-code (char s k)) 48))))
          (when neg (setq e (- e))))))
    (let ((eff (- e fd)) (v m))
      (cond ((< eff 0) (setq v (/ v (expt 10.0 (- eff)))))
            ((> eff 0) (setq v (* v (expt 10.0 eff)))))
      (if (= (char-code (char s tok-start)) 45) (- v) v))))

(defun rontolisp::%json-number (s i n)
  ;; Returns (number . next-index). Integers of at most 18 digits stay
  ;; integers (safely inside the signed 64-bit range the WASM GC backend's
  ;; boxed exact integers carry); anything with a fraction, an exponent or
  ;; more digits becomes a float on every backend.
  (let ((j (if (and (< i n) (= (char-code (char s i)) 45)) (+ i 1) i)))
    (let ((int-start j) (int-end (rontolisp::%json-scan-digits s j n)))
      (when (= int-end int-start) (error "json-parse: invalid number"))
      (let ((k int-end) (frac-start nil) (frac-end nil) (exp-p nil))
        (when (and (< k n) (= (char-code (char s k)) 46))
          (setq frac-start (+ k 1))
          (setq frac-end (rontolisp::%json-scan-digits s frac-start n))
          (when (= frac-end frac-start) (error "json-parse: invalid number"))
          (setq k frac-end))
        (when (and (< k n)
                   (let ((c (char-code (char s k)))) (or (= c 101) (= c 69))))
          (setq exp-p t)
          (setq k (+ k 1))
          (when (and (< k n)
                     (let ((c (char-code (char s k)))) (or (= c 43) (= c 45))))
            (setq k (+ k 1)))
          (let ((es k))
            (setq k (rontolisp::%json-scan-digits s es n))
            (when (= k es) (error "json-parse: invalid number"))))
        (if (and (null frac-start) (not exp-p) (<= (- int-end int-start) 18))
            (cons (parse-integer (subseq s i int-end)) k)
            (cons (rontolisp::%json-float s i int-start int-end frac-start frac-end k) k))))))

(defun rontolisp::%json-list->vector (rev-items count)
  ;; rev-items holds the elements in REVERSE order; build a forward simple
  ;; vector, matching jzon's array representation.
  (let ((v (make-array count)))
    (do ((k (- count 1) (- k 1)) (x rev-items (cdr x)))
        ((< k 0) v)
      (setf (aref v k) (car x)))))

(defun rontolisp::%json-literal (s i n word value)
  (let ((e (+ i (length word))))
    (if (and (<= e n) (string= (subseq s i e) word))
        (cons value e)
        (error "json-parse: invalid literal"))))

(defun rontolisp::%json-array (s i n)
  ;; i points at [; returns (vector . next-index).
  (let ((first-i (rontolisp::%json-skip-ws s (+ i 1) n)))
    (when (>= first-i n) (error "json-parse: unterminated array"))
    (if (= (char-code (char s first-i)) 93)
        (cons (make-array 0) (+ first-i 1))
        (let ((items nil) (count 0))
          (do ((j first-i))
              (nil)
            (let ((r (rontolisp::%json-value s j n)))
              (setq items (cons (car r) items))
              (setq count (+ count 1))
              (setq j (rontolisp::%json-skip-ws s (cdr r) n))
              (when (>= j n) (error "json-parse: unterminated array"))
              (let ((c (char-code (char s j))))
                (cond ((= c 44) (setq j (+ j 1)))
                      ((= c 93) (return (cons (rontolisp::%json-list->vector items count) (+ j 1))))
                      (t (error "json-parse: expected , or ] in array"))))))))))

(defun rontolisp::%json-object (s i n)
  ;; i points at {; returns (hash-table . next-index). Keys stay strings, like
  ;; jzon's (make-hash-table :test 'equal) objects.
  (let ((first-i (rontolisp::%json-skip-ws s (+ i 1) n)))
    (when (>= first-i n) (error "json-parse: unterminated object"))
    (if (= (char-code (char s first-i)) 125)
        (cons (make-hash-table :test 'equal) (+ first-i 1))
        (let ((h (make-hash-table :test 'equal)))
          (do ((j first-i))
              (nil)
            (setq j (rontolisp::%json-skip-ws s j n))
            (when (or (>= j n) (not (= (char-code (char s j)) 34)))
              (error "json-parse: expected an object key string"))
            (let ((kr (rontolisp::%json-string s j n)))
              (setq j (rontolisp::%json-skip-ws s (cdr kr) n))
              (when (or (>= j n) (not (= (char-code (char s j)) 58)))
                (error "json-parse: expected : after object key"))
              (let ((vr (rontolisp::%json-value s (+ j 1) n)))
                (setf (gethash (car kr) h) (car vr))
                (setq j (rontolisp::%json-skip-ws s (cdr vr) n))
                (when (>= j n) (error "json-parse: unterminated object"))
                (let ((c (char-code (char s j))))
                  (cond ((= c 44) (setq j (+ j 1)))
                        ((= c 125) (return (cons h (+ j 1))))
                        (t (error "json-parse: expected , or } in object")))))))))))

(defun rontolisp::%json-value (s i n)
  ;; Returns (value . next-index).
  (let ((j (rontolisp::%json-skip-ws s i n)))
    (when (>= j n) (error "json-parse: unexpected end of input"))
    (let ((c (char-code (char s j))))
      (cond ((= c 34) (rontolisp::%json-string s j n))
            ((= c 123) (rontolisp::%json-object s j n))
            ((= c 91) (rontolisp::%json-array s j n))
            ((= c 116) (rontolisp::%json-literal s j n "true" t))
            ((= c 102) (rontolisp::%json-literal s j n "false" nil))
            ((= c 110) (rontolisp::%json-literal s j n "null" 'null))
            ((or (= c 45) (and (>= c 48) (<= c 57))) (rontolisp::%json-number s j n))
            (t (error "json-parse: unexpected character"))))))

(defun rontolisp::%json-parse (s)
  (when (not (stringp s)) (error "json-parse expects a string"))
  (let ((n (length s)))
    (let ((r (rontolisp::%json-value s 0 n)))
      (when (< (rontolisp::%json-skip-ws s (cdr r) n) n)
        (error "json-parse: unexpected trailing characters"))
      (car r))))

;; --- serializer -------------------------------------------------------------

(defun rontolisp::%json-escape-char (c)
  (cond ((= c 34) "\\\"")
        ((= c 92) "\\\\")
        ((= c 10) "\\n")
        ((= c 13) "\\r")
        ((= c 9) "\\t")
        ((= c 8) "\\b")
        ((= c 12) "\\f")
        (t (concatenate 'string "\\u00"
                        (subseq "0123456789abcdef" (ash c -4) (+ (ash c -4) 1))
                        (subseq "0123456789abcdef" (logand c 15) (+ (logand c 15) 1))))))

(defun rontolisp::%json-out-string (s acc)
  ;; Conses the JSON representation of string s onto acc (fragments in
  ;; reverse order). Only ASCII quote/backslash/control characters are
  ;; escaped; everything else is copied verbatim.
  (let ((a (cons "\"" acc)) (n (length s)) (start 0))
    (do ((j 0))
        ((>= j n))
      (let ((c (char-code (char s j))))
        (if (or (= c 34) (= c 92) (< c 32))
            (progn
              (when (> j start) (setq a (cons (subseq s start j) a)))
              (setq a (cons (rontolisp::%json-escape-char c) a))
              (setq j (+ j 1))
              (setq start j))
            (setq j (+ j 1)))))
    (when (> n start) (setq a (cons (subseq s start n) a)))
    (cons "\"" a)))

(defun rontolisp::%json-downcase-key (name)
  ;; jzon coerces a symbol key with (string-downcase name) unless the name
  ;; already contains a lower-case letter (matching coerce-key). ASCII only,
  ;; via char-code, so it runs identically on the byte-indexed WASM backends.
  (let ((n (length name)) (has-lower nil))
    (do ((i 0 (+ i 1)))
        ((or (>= i n) has-lower))
      (let ((c (char-code (char name i))))
        (when (and (>= c 97) (<= c 122)) (setq has-lower t))))
    (if has-lower
        name
        (let ((parts nil) (start 0))
          (do ((i 0 (+ i 1)))
              ((>= i n))
            (let ((c (char-code (char name i))))
              (when (and (>= c 65) (<= c 90))
                (when (> i start) (setq parts (cons (subseq name start i) parts)))
                (setq parts (cons (rontolisp::%json-char-string (+ c 32)) parts))
                (setq start (+ i 1)))))
          (when (> n start) (setq parts (cons (subseq name start n) parts)))
          (rontolisp::%json-concat (nreverse parts))))))

(defun rontolisp::%json-key-name (k)
  ;; Coerce a hash-table key to a string the way jzon's coerce-key does.
  (cond ((stringp k) k)
        ((symbolp k) (rontolisp::%json-downcase-key (string k)))
        ((integerp k) (princ-to-string k))
        ((floatp k) (princ-to-string k))
        ((characterp k) (string k))
        (t (error "json-stringify: unsupported hash-table key"))))

(defun rontolisp::%json-out-array (v acc)
  (let ((a (cons "[" acc)) (firstp t))
    (do ((x v (cdr x)))
        ((null x))
      (when (not (consp x)) (error "json-stringify: improper list"))
      (if firstp (setq firstp nil) (setq a (cons "," a)))
      (setq a (rontolisp::%json-out (car x) a)))
    (cons "]" a)))

(defun rontolisp::%json-out-vec (v acc)
  (let ((a (cons "[" acc)) (nn (length v)))
    (do ((i 0 (+ i 1)))
        ((>= i nn))
      (when (> i 0) (setq a (cons "," a)))
      (setq a (rontolisp::%json-out (aref v i) a)))
    (cons "]" a)))

(defun rontolisp::%json-out-hash (h acc)
  ;; The variables captured by the maphash closure use %-prefixed names:
  ;; compiled closures resolve a captured name against a same-named top-level
  ;; global when one exists, so ordinary names like "a" would
  ;; break inside programs that (setq a ...) at the top level.
  (let ((%json-hash-acc (cons "{" acc)) (%json-hash-first t))
    (maphash (lambda (k v)
               (if %json-hash-first
                   (setq %json-hash-first nil)
                   (setq %json-hash-acc (cons "," %json-hash-acc)))
               (setq %json-hash-acc
                     (rontolisp::%json-out-string (rontolisp::%json-key-name k) %json-hash-acc))
               (setq %json-hash-acc (cons ":" %json-hash-acc))
               (setq %json-hash-acc (rontolisp::%json-out v %json-hash-acc)))
             h)
    (cons "}" %json-hash-acc)))

(defun rontolisp::%json-out-instance (v acc)
  ;; A CLOS instance or a defstruct instance serializes as a JSON object: each
  ;; slot's name (coerced like a symbol object key) maps to its slot-value, in
  ;; definition order -- matching jzon's standard-object serialization. A slot
  ;; may itself hold a hash table (a nested object), a list/vector (an array), or
  ;; any serializable value. Both shapes answer %class-slot-defs and slot-value,
  ;; so one walk covers them.
  (let ((a (cons "{" acc)) (firstp t))
    (do ((defs (%class-slot-defs (class-of v)) (cdr defs)))
        ((null defs))
      (let ((name (car (car defs))))
        (if firstp (setq firstp nil) (setq a (cons "," a)))
        (setq a (rontolisp::%json-out-string (rontolisp::%json-key-name name) a))
        (setq a (cons ":" a))
        (setq a (rontolisp::%json-out (slot-value v name) a))))
    (cons "}" a)))

(defun rontolisp::%json-out (v acc)
  ;; Value -> JSON, mirroring jzon's write-value defaults: nil is false, the
  ;; symbol null is null, a vector or list is an array, a hash table or a
  ;; struct/CLOS instance is an object, any other symbol/character prints its
  ;; name as a string.
  (cond ((eq v t) (cons "true" acc))
        ((eq v 'null) (cons "null" acc))
        ((null v) (cons "false" acc))
        ((integerp v) (cons (princ-to-string v) acc))
        ((floatp v) (cons (princ-to-string v) acc))
        ((rationalp v) (cons (princ-to-string (float v)) acc))
        ((stringp v) (rontolisp::%json-out-string v acc))
        ((hash-table-p v) (rontolisp::%json-out-hash v acc))
        ((vectorp v) (rontolisp::%json-out-vec v acc))
        ((symbolp v) (rontolisp::%json-out-string (string v) acc))
        ((characterp v) (rontolisp::%json-out-string (string v) acc))
        ((typep v 'standard-object) (rontolisp::%json-out-instance v acc))
        ((typep v 'structure-object) (rontolisp::%json-out-instance v acc))
        ((consp v) (rontolisp::%json-out-array v acc))
        (t (error "json-stringify: unsupported value"))))

(defun rontolisp::%json-stringify (v)
  (rontolisp::%json-concat (nreverse (rontolisp::%json-out v nil))))
