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
  ;; CODE POINT (.kb/characters-code-points.md), so %json-encode-char takes
  ;; the direct (code-char cp) path below on every backend.
  nil)

(defun rontolisp::%json-char-string (code)
  ;; A one-unit string holding the given char code (byte or UTF-16 unit).
  (%princ-piece (code-char code)))

(defun rontolisp::%json-pairs (parts)
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
                  ;; %string-concat, not concatenate: this merge runs once per
                  ;; fragment PAIR per pass, and the wrapped concatenate would
                  ;; convert every intermediate into a mutable character vector
                  ;; and render it right back on the next pass -- only the
                  ;; whole document's final identity matters (%json-stringify)
                  (cons (%string-concat (car todo) (car (cdr todo))) out))
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
      ((or (>= k n) (let ((c (char-code (char s k)))) (or (< c 48) (> c 57))))
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
      (setq v
            (+ (* v 16)
               (rontolisp::%json-hex-digit (char-code (char s (+ i k)))))))))

(defun rontolisp::%json-encode-char (cp)
  ;; One CHARACTER string for the given code point. Prior to the code-point
  ;; string-indexing cutover the WASM backends emitted the code point's UTF-8
  ;; bytes as one-byte characters and the UTF-16-indexed backends split a
  ;; supplementary code point into a surrogate pair here; now every backend
  ;; indexes strings by code point, so (code-char cp) suffices.
  (rontolisp::%json-char-string cp))

(defun rontolisp::%json-unicode-escape (s j n)
  ;; j points at the backslash of \uXXXX; combines surrogate pairs.
  (let ((cp (rontolisp::%json-hex4 s (+ j 2) n)) (next (+ j 6)))
    (if (and (>= cp 55296) (<= cp 56319) (<= (+ next 6) n)
             (= (char-code (char s next)) 92)
             (= (char-code (char s (+ next 1))) 117))
        (let ((lo (rontolisp::%json-hex4 s (+ next 2) n)))
          (if (and (>= lo 56320) (<= lo 57343))
              (cons (rontolisp::%json-encode-char
                     (+ 65536 (* 1024 (- cp 55296)) (- lo 56320))) (+ next 6))
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
               (return
                (cons (rontolisp::%json-concat (nreverse parts)) (+ j 1))))
              ((= c 92)
               (when (> j start) (setq parts (cons (subseq s start j) parts)))
               (let ((esc (rontolisp::%json-escape s j n)))
                 (setq parts (cons (car esc) parts))
                 (setq j (cdr esc))
                 (setq start j)))
              (t (setq j (+ j 1))))))))

(defun rontolisp::%json-float
    (s tok-start int-start int-end frac-start frac-end tok-end)
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
            (cons (rontolisp::%json-float s i int-start int-end frac-start
                                          frac-end k) k))))))

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
                      ((= c 93)
                       (return
                        (cons (rontolisp::%json-list->vector items count)
                              (+ j 1))))
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
                (when (> i start)
                  (setq parts (cons (subseq name start i) parts)))
                (setq parts
                      (cons (rontolisp::%json-char-string (+ c 32)) parts))
                (setq start (+ i 1)))))
          (when (> n start) (setq parts (cons (subseq name start n) parts)))
          (rontolisp::%json-concat (nreverse parts))))))

(defun rontolisp::%json-decimal (n)
  ;; An integer's decimal digits whatever *print-base* and *print-radix* say, as
  ;; jzon's ~D writes them: #xFF is no JSON.
  (%decimal-piece n))

(defun rontolisp::%json-key-name (k)
  ;; Coerce a hash-table key to a string the way jzon's coerce-key does.
  (cond ((stringp k) k)
        ((symbolp k) (rontolisp::%json-downcase-key (string k)))
        ((integerp k) (rontolisp::%json-decimal k))
        ((floatp k) (%princ-piece k))
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
                     (rontolisp::%json-out-string (rontolisp::%json-key-name k)
                                                  %json-hash-acc))
               (setq %json-hash-acc (cons ":" %json-hash-acc))
               (setq %json-hash-acc (rontolisp::%json-out v %json-hash-acc))) h)
    (cons "}" %json-hash-acc)))

(defun rontolisp::%json-out-instance (v acc out)
  ;; A CLOS instance or a defstruct instance serializes as a JSON object: each
  ;; slot's name (coerced like a symbol object key) maps to its slot-value, in
  ;; definition order -- matching jzon's standard-object serialization. A slot
  ;; may itself hold a hash table (a nested object), a list/vector (an array), or
  ;; any serializable value. Both shapes answer %class-slot-defs and slot-value,
  ;; so one walk covers them. An UNBOUND slot is skipped, not written as null --
  ;; jzon's coerced-fields does the same, and reading it would signal. OUT conses
  ;; a value's fragments onto an accumulator: %json-out, or on wasm-GC the native
  ;; serializer's text (%json-native-out).
  (let ((a (cons "{" acc)) (firstp t))
    (do ((defs (%class-slot-defs (%class-designator v)) (cdr defs)))
        ((null defs))
      (let ((name (car (car defs))))
        (when (slot-boundp v name)
          (if firstp (setq firstp nil) (setq a (cons "," a)))
          (setq a (funcall out (rontolisp::%json-key-name name) a))
          (setq a (cons ":" a))
          (setq a (funcall out (slot-value v name) a)))))
    (cons "}" a)))

(defun rontolisp::%json-out (v acc)
  ;; Value -> JSON, mirroring jzon's write-value defaults: nil is false, the
  ;; symbol null is null, a vector or list is an array, a hash table or a
  ;; struct/CLOS instance is an object, any other symbol/character prints its
  ;; name as a string.
  (cond ((eq v t) (cons "true" acc))
        ((eq v 'null) (cons "null" acc))
        ((null v) (cons "false" acc))
        ((integerp v) (cons (rontolisp::%json-decimal v) acc))
        ((floatp v) (cons (%princ-piece v) acc))
        ((rationalp v) (cons (%princ-piece (float v)) acc))
        ((stringp v) (rontolisp::%json-out-string v acc))
        ((hash-table-p v) (rontolisp::%json-out-hash v acc))
        ((vectorp v) (rontolisp::%json-out-vec v acc))
        ((symbolp v) (rontolisp::%json-out-string (string v) acc))
        ((characterp v) (rontolisp::%json-out-string (string v) acc))
        ((typep v 'standard-object)
         (rontolisp::%json-out-instance v acc #'rontolisp::%json-out))
        ((typep v 'structure-object)
         (rontolisp::%json-out-instance v acc #'rontolisp::%json-out))
        ((consp v) (rontolisp::%json-out-array v acc))
        (t (error "json-stringify: unsupported value"))))

(defun rontolisp::%json-stringify (v)
  ;; copy-seq: the merge above builds through the unwrapped %string-concat, and
  ;; the one string the CALLER gets back should carry the same writable
  ;; identity every other string producer answers
  (copy-seq (rontolisp::%json-concat (nreverse (rontolisp::%json-out v nil)))))

;; --- json-span and json-value -------------------------------------------------
;; One scan over a document's UTF-8 bytes: it refuses what json-parse refuses, with
;; json-parse's error under json-span's name, and finds the bytes of the value a path
;; of keys and indexes leads to (.kb/json.md, "json-span"). The interpreter and the
;; JVM walk the document's octets here; wasm-GC runs the same scan natively
;; (WasmJsonSpanRuntimeBuilder) over a string's bytes, an octet vector, or the bytes
;; at an address of linear memory. Offsets count bytes on every backend.

(defun rontolisp::%json-span-fail (code)
  ;; The error for the scan's code: 1-16 are json-parse's (%json-native-fail).
  (cond
   ((eql code 0)
    (error
     "json-span expects a string, an octet vector, or an address and a size"))
   ((eql code 1) (error "json-span: unexpected end of input"))
   ((eql code 2) (error "json-span: unexpected trailing characters"))
   ((eql code 3) (error "json-span: unterminated string"))
   ((eql code 4) (error "json-span: unterminated escape"))
   ((eql code 5) (error "json-span: invalid escape in string"))
   ((eql code 6) (error "json-span: unterminated unicode escape"))
   ((eql code 7) (error "json-span: invalid hex digit in unicode escape"))
   ((eql code 8) (error "json-span: invalid number"))
   ((eql code 9) (error "json-span: invalid literal"))
   ((eql code 10) (error "json-span: unexpected character"))
   ((eql code 11) (error "json-span: unterminated array"))
   ((eql code 12) (error "json-span: expected , or ] in array"))
   ((eql code 13) (error "json-span: unterminated object"))
   ((eql code 14) (error "json-span: expected an object key string"))
   ((eql code 15) (error "json-span: expected : after object key"))
   ((eql code 16) (error "json-span: expected , or } in object"))
   ((eql code 17)
    (error "json-span: a path segment is a string or a non-negative integer"))
   ((eql code 18)
    (error "json-span: the address and the size name bytes outside the memory"))
   (t (error
       "json-span: an address is a wasm module's, and this program is none"))))

(defun rontolisp::%json-span-byte (o p n)
  ;; The byte at p, or past the end a quote: what the native scan reads there,
  ;; the frame of a string's bytes or the one it writes past an address's.
  (if (< p n) (aref o p) 34))

(defun rontolisp::%json-span-blanks (o p n)
  (do ((q p (+ q 1)))
      ((let ((b (rontolisp::%json-span-byte o q n)))
         (not (or (= b 32) (= b 9) (= b 10) (= b 13))))
       q)))

(defun rontolisp::%json-span-digits (o p n)
  (do ((q p (+ q 1)))
      ((let ((b (rontolisp::%json-span-byte o q n))) (or (< b 48) (> b 57)))
       q)))

(defun rontolisp::%json-span-hex-digit (b)
  (cond ((and (>= b 48) (<= b 57)) (- b 48))
        ((and (>= b 97) (<= b 102)) (- b 87))
        ((and (>= b 65) (<= b 70)) (- b 55))
        (t -1)))

(defun rontolisp::%json-span-hex4 (o q n)
  ;; The four hex digits at q, or json-parse's error for them: fewer than four
  ;; CHARACTERS left is unterminated, which a multi-byte one among the four bytes
  ;; can make it.
  (when (> (+ q 4) n) (rontolisp::%json-span-fail 6))
  (let ((v 0))
    (do ((k 0 (+ k 1)))
        ((>= k 4) v)
      (let ((d (rontolisp::%json-span-hex-digit (aref o (+ q k)))))
        (when (< d 0)
          (let ((count 0))
            (do ((at q (+ at 1)))
                ((or (>= at n) (>= count 4)))
              (unless (= (logand (aref o at) 192) 128)
                (setq count (+ count 1))))
            (rontolisp::%json-span-fail (if (< count 4) 6 7))))
        (setq v (+ (* v 16) d))))))

(defun rontolisp::%json-span-surrogate-pair-p (o next n cp)
  ;; Whether a high surrogate's escape is followed by a second \u escape.
  (and (>= cp 55296) (<= cp 56319) (<= (+ next 6) n) (= (aref o next) 92)
       (= (aref o (+ next 1)) 117)))

(defun rontolisp::%json-span-escape (o p n)
  ;; p at a backslash inside a string: the position past its escape.
  (when (>= (+ p 1) n) (rontolisp::%json-span-fail 4))
  (let ((c (aref o (+ p 1))))
    (cond ((or (= c 34) (= c 92) (= c 47) (= c 98) (= c 102) (= c 110) (= c 114)
               (= c 116))
           (+ p 2))
          ((= c 117)
           (let ((cp (rontolisp::%json-span-hex4 o (+ p 2) n)) (next (+ p 6)))
             (if (rontolisp::%json-span-surrogate-pair-p o next n cp)
                 (progn
                   (rontolisp::%json-span-hex4 o (+ next 2) n)
                   (+ next 6))
                 next)))
          (t (rontolisp::%json-span-fail 5)))))

(defun rontolisp::%json-span-string (o p n)
  ;; p at a string's opening quote: the position past its closing quote.
  (let ((q (+ p 1)) (done nil))
    (do ((dummy nil))
        (done q)
      (let ((b (rontolisp::%json-span-byte o q n)))
        (cond ((= b 34)
               (when (>= q n) (rontolisp::%json-span-fail 3))
               (setq q (+ q 1))
               (setq done t))
              ((= b 92) (setq q (rontolisp::%json-span-escape o q n)))
              (t (setq q (+ q 1))))))))

(defun rontolisp::%json-span-number (o p n)
  ;; p at - or a digit: the position past the number.
  (let* ((int-start (if (= (aref o p) 45) (+ p 1) p))
         (q (rontolisp::%json-span-digits o int-start n)))
    (when (= q int-start) (rontolisp::%json-span-fail 8))
    (when (= (rontolisp::%json-span-byte o q n) 46)
      (let ((frac (+ q 1)))
        (setq q (rontolisp::%json-span-digits o frac n))
        (when (= q frac) (rontolisp::%json-span-fail 8))))
    (when (= (logior (rontolisp::%json-span-byte o q n) 32) 101)
      (setq q (+ q 1))
      (let ((b (rontolisp::%json-span-byte o q n)))
        (when (or (= b 43) (= b 45)) (setq q (+ q 1))))
      (let ((exponent q))
        (setq q (rontolisp::%json-span-digits o exponent n))
        (when (= q exponent) (rontolisp::%json-span-fail 8))))
    q))

(defun rontolisp::%json-span-literal (o p n word)
  ;; p at the first byte of true, false or null: the position past it.
  (let ((e (+ p (length word))))
    (when (> e n) (rontolisp::%json-span-fail 9))
    (do ((k 1 (+ k 1)))
        ((>= k (length word)) e)
      (unless (= (aref o (+ p k)) (char-code (char word k)))
        (rontolisp::%json-span-fail 9)))))

(defun rontolisp::%json-span-utf8 (cp out)
  ;; OUT with the UTF-8 bytes of the code point consed on, the last first: what a
  ;; string of (code-char cp) holds, a lone surrogate's three bytes too.
  (cond ((< cp 128) (cons cp out))
        ((< cp 2048)
         (cons (logior 128 (logand cp 63)) (cons (logior 192 (ash cp -6)) out)))
        ((< cp 65536)
         (cons (logior 128 (logand cp 63))
               (cons (logior 128 (logand (ash cp -6) 63))
                     (cons (logior 224 (ash cp -12)) out))))
        (t (cons (logior 128 (logand cp 63))
                 (cons (logior 128 (logand (ash cp -6) 63))
                       (cons (logior 128 (logand (ash cp -12) 63))
                             (cons (logior 240 (ash cp -18)) out)))))))

(defun rontolisp::%json-span-hex-value (o q)
  ;; The value of the four hex digits at q, which the scan has checked.
  (let ((v 0))
    (do ((k 0 (+ k 1)))
        ((>= k 4) v)
      (setq v
            (+ (* v 16) (rontolisp::%json-span-hex-digit (aref o (+ q k))))))))

(defun rontolisp::%json-span-key-bytes (o a z n)
  ;; The bytes the key whose text is the octets from a to z spells, as json-parse
  ;; decodes its escapes into a string: a list.
  (let ((out nil) (q a))
    (do ((dummy nil))
        ((>= q z) (nreverse out))
      (let ((b (aref o q)))
        (if (not (= b 92))
            (progn
              (setq out (cons b out))
              (setq q (+ q 1)))
            (let ((c (aref o (+ q 1))))
              (if (= c 117)
                  (let ((cp (rontolisp::%json-span-hex-value o (+ q 2))))
                    (setq q (+ q 6))
                    (when (rontolisp::%json-span-surrogate-pair-p o q n cp)
                      (let ((lo (rontolisp::%json-span-hex-value o (+ q 2))))
                        (setq q (+ q 6))
                        (if (and (>= lo 56320) (<= lo 57343))
                            (setq cp
                                  (+ 65536 (* 1024 (- cp 55296)) (- lo 56320)))
                            (progn
                              (setq out (rontolisp::%json-span-utf8 cp out))
                              (setq cp lo)))))
                    (setq out (rontolisp::%json-span-utf8 cp out)))
                  (progn
                    (setq out
                          (cons (cond ((= c 98) 8)
                                      ((= c 102) 12)
                                      ((= c 110) 10)
                                      ((= c 114) 13)
                                      ((= c 116) 9)
                                      (t c)) out))
                    (setq q (+ q 2))))))))))

(defun rontolisp::%json-locate (o path)
  ;; (start end count cut-start cut-end) for the value PATH leads to in the octets
  ;; O, or NIL. A key of PATH is the list of its UTF-8 bytes. A container on the way
  ;; to the value is at a depth from 1 to ON, the deepest of them where FOCUS is; the
  ;; value's own container, once it is entered, at TDEPTH, which FOCUS is then. MATCH
  ;; says what the next value is: 1 the next container on the way, 2 the value.
  (let ((n (length o))
        (plen (length path))
        (p 0)
        (b 0)
        (state 0)
        (depth 0)
        (kinds nil)
        (object nil)
        (on 0)
        (focus -1)
        (tdepth -1)
        (index 0)
        (match (if (null path) 2 1))
        (found nil)
        (tstart 0)
        (tend 0)
        (tcount nil)
        (mstart 0)
        (mcomma -1)
        (copen 0)
        (last-comma -1)
        (kstart 0)
        (vstart 0)
        (result nil)
        (done nil))
    (do ((dummy nil))
        (done result)
      (cond ((= state 0)
             ;; a value
             (setq p (rontolisp::%json-span-blanks o p n))
             (setq b (rontolisp::%json-span-byte o p n))
             (when (and (= depth focus) (not object))
               (if (= depth tdepth)
                   (setq tcount (+ tcount 1))
                   (progn
                     (when (eql index (nth (- on 1) path))
                       (setq match (if (= on plen) 2 1))
                       (setq found nil)
                       (when (= on plen)
                         (setq mstart p)
                         (setq mcomma last-comma)))
                     (setq index (+ index 1)))))
             (when (= match 2)
               (setq found t)
               (setq tstart p)
               (setq tcount nil))
             (setq state 1)
             (cond
              ((= b 34)
               (when (>= p n) (rontolisp::%json-span-fail 1))
               (setq p (rontolisp::%json-span-string o p n)))
              ((or (= b 123) (= b 91))
               (let ((opens-object (= b 123)))
                 (setq vstart p)
                 (setq p (rontolisp::%json-span-blanks o (+ p 1) n))
                 (when (>= p n)
                   (rontolisp::%json-span-fail (if opens-object 13 11)))
                 (if (= (rontolisp::%json-span-byte o p n)
                        (if opens-object 125 93))
                     (progn
                       (setq p (+ p 1))
                       (when (= match 2) (setq tcount 0)))
                     (progn
                       (setq kinds (cons object kinds))
                       (setq object opens-object)
                       (setq depth (+ depth 1))
                       (cond ((= match 2)
                              (setq tdepth depth)
                              (setq focus depth)
                              (setq tcount 0))
                             ((= match 1)
                              (setq on depth)
                              (setq focus depth)
                              (setq index 0)
                              (when (= on plen)
                                (setq copen vstart)
                                (setq last-comma -1))))
                       (setq match 0)
                       (setq state (if opens-object 2 0))))))
              ((= b 116) (setq p (rontolisp::%json-span-literal o p n "true")))
              ((= b 102) (setq p (rontolisp::%json-span-literal o p n "false")))
              ((= b 110) (setq p (rontolisp::%json-span-literal o p n "null")))
              ((or (= b 45) (and (>= b 48) (<= b 57)))
               (setq p (rontolisp::%json-span-number o p n)))
              (t (rontolisp::%json-span-fail 10))))
            ((= state 1)
             ;; after a value: its separator, or the closes past it
             (when (= match 2) (setq tend p))
             (setq match 0)
             (setq p (rontolisp::%json-span-blanks o p n))
             (setq b (rontolisp::%json-span-byte o p n))
             (cond ((= depth 0)
                    (when (< p n) (rontolisp::%json-span-fail 2))
                    (setq done t)
                    (when found
                      (setq result
                            (cond
                             ((null path) (list tstart tend tcount nil nil))
                             ((>= mcomma 0)
                              (list tstart tend tcount mcomma tend))
                             (t
                              (let ((q (rontolisp::%json-span-blanks o tend n)))
                                (if (= (rontolisp::%json-span-byte o q n) 44)
                                    (list tstart tend tcount mstart
                                     (rontolisp::%json-span-blanks o (+ q 1) n))
                                    (list tstart tend tcount (+ copen 1)
                                          q))))))))
                   ((= b 44)
                    (when (and (= depth on) (= on plen)) (setq last-comma p))
                    (setq p (+ p 1))
                    (setq state (if object 2 0)))
                   (t
                    (if object
                        (unless (= b 125)
                          (rontolisp::%json-span-fail (if (>= p n) 13 16)))
                        (unless (= b 93)
                          (rontolisp::%json-span-fail (if (>= p n) 11 12))))
                    (setq p (+ p 1))
                    (when (= depth focus)
                      (if (= depth tdepth)
                          (progn
                            (setq tend p)
                            (setq tdepth -1)
                            (setq focus on))
                          (progn
                            (setq on (- on 1))
                            (setq focus on)
                            ;; an array on the way matched its index: none after it can
                            (when (and (> on 0) (not (car kinds)))
                              (setq index (+ (nth (- on 1) path) 1))))))
                    (setq object (car kinds))
                    (setq kinds (cdr kinds))
                    (setq depth (- depth 1)))))
            (t
             ;; a key, its colon
             (setq p (rontolisp::%json-span-blanks o p n))
             (when (or (not (= (rontolisp::%json-span-byte o p n) 34)) (>= p n))
               (rontolisp::%json-span-fail 14))
             (setq kstart p)
             (setq p (rontolisp::%json-span-string o p n))
             (when (= depth focus)
               (if (= depth tdepth)
                   (setq tcount (+ tcount 1))
                   (let ((segment (nth (- on 1) path)))
                     (when (and (listp segment)
                                (equal segment
                                       (rontolisp::%json-span-key-bytes o
                                        (+ kstart 1) (- p 1) n)))
                       (setq match (if (= on plen) 2 1))
                       (setq found nil)
                       (when (= on plen)
                         (setq mstart kstart)
                         (setq mcomma last-comma))))))
             (setq p (rontolisp::%json-span-blanks o p n))
             (unless (= (rontolisp::%json-span-byte o p n) 58)
               (rontolisp::%json-span-fail 15))
             (setq p (+ p 1))
             (setq state 0))))))

(defun rontolisp::%json-span-path (path)
  ;; PATH checked, a key as the list of its UTF-8 bytes.
  (let ((out nil))
    (dolist (segment path (nreverse out))
      (setq out
            (cons (cond ((stringp segment)
                         (coerce (rontolisp:string-to-octets segment) 'list))
                        ((and (integerp segment) (>= segment 0)) segment)
                        (t (rontolisp::%json-span-fail 17))) out)))))

(defun rontolisp::%json-span-octets (document)
  (cond ((stringp document) (rontolisp:string-to-octets document))
        ((typep document '(simple-array (unsigned-byte 8) (*))) document)
        ((integerp document) (rontolisp::%json-span-fail 19))
        (t (rontolisp::%json-span-fail 0))))

(defun rontolisp::%json-span (document path)
  (let* ((segments (rontolisp::%json-span-path path))
         (r
          (rontolisp::%json-locate (rontolisp::%json-span-octets document)
                                   segments)))
    (if r (values (car r) (cadr r) (caddr r) (cadddr r) (car (cddddr r))) nil)))

(defun rontolisp::%json-span-value (document path)
  (let* ((segments (rontolisp::%json-span-path path))
         (o (rontolisp::%json-span-octets document))
         (r (rontolisp::%json-locate o segments)))
    (if r
        (values (rontolisp::%json-parse
                 (rontolisp:octets-to-string (subseq o (car r) (cadr r)))) t)
        (values nil nil))))

;; --- the wasm-GC entry points -----------------------------------------------
;; On a wasm-GC target JsonLibrary routes json-parse, json-stringify, json-span and
;; json-value here, and to nothing above but what these call: %json-decode and
;; %json-encode are the backend's native parser and serializer
;; (WasmJsonRuntimeBuilder, .kb/json.md), %json-scan its native %json-locate
;; (WasmJsonSpanRuntimeBuilder), answering what the walks above answer. Every
;; other backend leaves these out.

(defun rontolisp::%json-native-parse (s)
  (let ((r (rontolisp::%json-decode s 'null)))
    (if (consp r) (car r) (rontolisp::%json-native-fail r))))

(defun rontolisp::%json-native-fail (code)
  ;; The error the walk above signals, by the parser's code for it.
  (cond ((eql code 0) (error "json-parse expects a string"))
        ((eql code 1) (error "json-parse: unexpected end of input"))
        ((eql code 2) (error "json-parse: unexpected trailing characters"))
        ((eql code 3) (error "json-parse: unterminated string"))
        ((eql code 4) (error "json-parse: unterminated escape"))
        ((eql code 5) (error "json-parse: invalid escape in string"))
        ((eql code 6) (error "json-parse: unterminated unicode escape"))
        ((eql code 7) (error "json-parse: invalid hex digit in unicode escape"))
        ((eql code 8) (error "json-parse: invalid number"))
        ((eql code 9) (error "json-parse: invalid literal"))
        ((eql code 10) (error "json-parse: unexpected character"))
        ((eql code 11) (error "json-parse: unterminated array"))
        ((eql code 12) (error "json-parse: expected , or ] in array"))
        ((eql code 13) (error "json-parse: unterminated object"))
        ((eql code 14) (error "json-parse: expected an object key string"))
        ((eql code 15) (error "json-parse: expected : after object key"))
        (t (error "json-parse: expected , or } in object"))))

(defun rontolisp::%json-native-stringify (v)
  (let ((r
         (rontolisp::%json-encode v 'null #'rontolisp::%json-native-other
                                  #'rontolisp::%json-key-name)))
    (if (stringp r) r (error "json-stringify: improper list"))))

(defun rontolisp::%json-native-other (v)
  ;; The JSON text of a value the serializer has no arm for, as %json-out writes
  ;; it: an integer past 64 bits, a float, a ratio, a vector it cannot walk, a
  ;; symbol, a character, an instance.
  (cond ((integerp v) (rontolisp::%json-decimal v))
        ((floatp v) (%princ-piece v))
        ((rationalp v) (%princ-piece (float v)))
        ((vectorp v)
         (if (= (length v) 0)
             "[]"
             (rontolisp::%json-native-stringify (coerce v 'list))))
        ((symbolp v) (rontolisp::%json-native-stringify (string v)))
        ((characterp v) (rontolisp::%json-native-stringify (string v)))
        ((or (typep v 'standard-object) (typep v 'structure-object))
         (rontolisp::%json-concat
          (nreverse
           (rontolisp::%json-out-instance v nil
                                          #'rontolisp::%json-native-out))))
        (t (error "json-stringify: unsupported value"))))

(defun rontolisp::%json-native-out (v acc)
  (cons (rontolisp::%json-native-stringify v) acc))

(defun rontolisp::%json-native-scan (document path mode)
  ;; What %json-scan answers for PATH in DOCUMENT -- a string, octets, or an address
  ;; whose size is PATH's first element -- under MODE: 0 the list %json-locate answers,
  ;; 1 a string of the value's bytes. NIL when PATH leads nowhere.
  (let ((r
         (if (integerp document)
             (let ((size (car path)))
               (unless (and (consp path) (integerp size) (>= document 0)
                            (>= size 0) (< document 4294967296)
                            (< size 4294967296))
                 (rontolisp::%json-span-fail 18))
               (rontolisp::%json-scan document size
                                      (rontolisp::%json-native-path (cdr path))
                                      mode))
             (rontolisp::%json-scan document 0
                                    (rontolisp::%json-native-path path) mode))))
    (if (integerp r) (rontolisp::%json-span-fail r) r)))

(defun rontolisp::%json-native-path (path)
  (dolist (segment path path)
    (unless (or (stringp segment) (and (integerp segment) (>= segment 0)))
      (rontolisp::%json-span-fail 17))))

(defun rontolisp::%json-native-span (document path)
  (let ((r (rontolisp::%json-native-scan document path 0)))
    (if r (values (car r) (cadr r) (caddr r) (cadddr r) (car (cddddr r))) nil)))

(defun rontolisp::%json-native-value (document path)
  (let ((r (rontolisp::%json-native-scan document path 1)))
    (if r (values (rontolisp::%json-native-parse r) t) (values nil nil))))
