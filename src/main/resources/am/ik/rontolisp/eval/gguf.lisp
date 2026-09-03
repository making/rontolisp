;; The gguf package: read a GGUF checkpoint -- the one file a downloaded small
;; language model most often IS, carrying the hyperparameters, the tokenizer and
;; the tensors together in the width the publisher chose. Written in rontolisp
;; itself so a single implementation runs on every backend: the interpreter loads
;; these definitions lazily on the first use of a gguf: function, and the compile
;; path splices them into the program when it references the package (see
;; GgufLibrary.java).
;;
;; THE FILE (v3), all little-endian:
;;
;;   "GGUF" | u32 version | u64 tensor count | u64 KV count
;;   KV pairs:      string key, u32 value type, value
;;   tensor infos:  string name, u32 n_dims, u64 dims[n_dims], u32 type, u64 offset
;;   padding to general.alignment (default 32)
;;   the tensor data, each tensor at its own offset from the data start
;;
;; ggml stores dims FASTEST-VARYING FIRST, so an info reading [cols, rows] is a
;; rows x cols matrix and the array this reader builds has the dims reversed --
;; which makes the bytes on disk exactly row-major, so read-sequence fills a
;; packed array in one transfer (.kb/binary-sequence-io.md).
;;
;; NO SEEKING, and that is a MEASUREMENT rather than a preference: file-position
;; does not reposition a file stream on any backend (it answers nil and the read
;; continues where it was -- measured 2026-09-03 on the interpreter, the JVM and
;; wasm preview 1; .kb/gguf.md). So the data is walked SEQUENTIALLY in ascending
;; offset order, skipping the alignment padding and any tensor not asked for. Two
;; consequences worth knowing:
;;
;;   - the tokenizer and the hyperparameters cost nothing to read, because the KV
;;     block is at the FRONT of the file: (gguf:read path :metadata-only t) stops
;;     before the data and never touches the gigabytes;
;;   - :only loads a subset but still READS past what it skips, so it saves memory
;;     and conversion, not I/O.
;;
;; A file whose tensor offsets are not ascending is refused by name rather than
;; read wrongly.
;;
;; The staging is the checkpoint package's, shared with the safetensors reader
;; rather than written twice: checkpoint:make-tensor allocates and CHECKS the
;; destination, stage-float32 / stage-float-bits fill it (chunked, because a
;; packed (unsigned-byte 16) vector costs eight bytes an element), and
;; skip-bytes is how a tensor this reader was told not to load is passed over.
;; What is left here is the FORMAT: the header, the values, the directory and
;; the walk (.kb/checkpoint-readers.md).
;;
;; Portability constraints honored here (like linalg.lisp, .kb/linalg.md): do
;; loops always declare at least one variable, and parameters are never assigned
;; with setq (let-rebound instead).

;;; --- the byte reader ------------------------------------------------------------
;;; One record holds the stream, the scratch buffers every primitive read reuses
;;; (a 250k-token vocabulary is a quarter of a million string reads, so a fresh
;;; one-element vector per read is not free) and OUR OWN byte position, which is
;;; the only position there is: file-position does not answer one.

(defstruct (gguf::%rd (:constructor gguf::%make-rd))
  stream
  (u16 nil)
  (u32 nil)
  (f32 nil)
  (f64 nil)
  (bytes nil)
  (pos 0))

(defun gguf::%open-reader (stream)
  (gguf::%make-rd :stream stream
   :u16 (make-array 1 :element-type '(unsigned-byte 16))
   :u32 (make-array 1 :element-type '(unsigned-byte 32))
   :f32 (make-array 1 :element-type 'single-float :initial-element 0.0)
   :f64 (make-array 1 :element-type 'double-float :initial-element 0.0d0)
   :bytes (make-array 4096 :element-type '(unsigned-byte 8))))

(defun gguf::%advance (rd n) (setf (gguf::%rd-pos rd) (+ (gguf::%rd-pos rd) n)))

(defun gguf::%u8 (rd)
  (let ((b (read-byte (gguf::%rd-stream rd))))
    (gguf::%advance rd 1)
    b))

(defun gguf::%u16 (rd)
  (let ((v (gguf::%rd-u16 rd)))
    (read-sequence v (gguf::%rd-stream rd))
    (gguf::%advance rd 2)
    (aref v 0)))

(defun gguf::%u32 (rd)
  (let ((v (gguf::%rd-u32 rd)))
    (read-sequence v (gguf::%rd-stream rd))
    (gguf::%advance rd 4)
    (aref v 0)))

(defun gguf::%u64 (rd)
  ;; There is no packed (unsigned-byte 64) vector, so a u64 is two u32s. The
  ;; result may exceed a fixnum -- a tensor offset in a 30 GB file does -- which
  ;; is why it is built with + and * rather than with shifts into a machine word.
  (let* ((lo (gguf::%u32 rd)) (hi (gguf::%u32 rd))) (+ lo (* hi 4294967296))))

(defun gguf::%i8 (rd) (let ((v (gguf::%u8 rd))) (if (>= v 128) (- v 256) v)))

(defun gguf::%i16 (rd)
  (let ((v (gguf::%u16 rd))) (if (>= v 32768) (- v 65536) v)))

(defun gguf::%i32 (rd)
  (let ((v (gguf::%u32 rd))) (if (>= v 2147483648) (- v 4294967296) v)))

(defun gguf::%i64 (rd)
  (let ((v (gguf::%u64 rd)))
    (if (>= v 9223372036854775808) (- v 18446744073709551616) v)))

(defun gguf::%f32 (rd)
  (let ((v (gguf::%rd-f32 rd)))
    (read-sequence v (gguf::%rd-stream rd))
    (gguf::%advance rd 4)
    (aref v 0)))

(defun gguf::%f64 (rd)
  (let ((v (gguf::%rd-f64 rd)))
    (read-sequence v (gguf::%rd-stream rd))
    (gguf::%advance rd 8)
    (aref v 0)))

(defun gguf::%buffer (rd n)
  ;; The scratch byte buffer, grown to at least N. Kept on the reader so a long
  ;; run of string reads allocates once.
  (let ((buf (gguf::%rd-bytes rd)))
    (if (>= (length buf) n)
        buf
        (let ((bigger
               (make-array (max n (* 2 (length buf)))
                           :element-type '(unsigned-byte 8))))
          (setf (gguf::%rd-bytes rd) bigger)
          bigger))))

(defun gguf::%utf8 (bytes n)
  ;; The first N bytes as a string; a character is a code point on every backend.
  (let ((chars '()) (i 0))
    (loop while (< i n)
          do
            (let* ((b0 (aref bytes i))
                   (len
                    (cond ((< b0 128) 1) ((< b0 224) 2) ((< b0 240) 3) (t 4)))
                   (cp
                    (cond ((= len 1) b0)
                          ((= len 2) (mod b0 32))
                          ((= len 3) (mod b0 16))
                          (t (mod b0 8)))))
              (if (> (+ i len) n)
                  (setq i n)
                  (progn
                    (dotimes (k (- len 1))
                      (setq cp (+ (* cp 64) (mod (aref bytes (+ i 1 k)) 64))))
                    (push (code-char cp) chars)
                    (setq i (+ i len))))))
    (coerce (nreverse chars) 'string)))

(defun gguf::%string (rd)
  (let ((n (gguf::%u64 rd)))
    (if (= n 0)
        ""
        (let ((buf (gguf::%buffer rd n)))
          (read-sequence buf (gguf::%rd-stream rd) :end n)
          (gguf::%advance rd n)
          (gguf::%utf8 buf n)))))

(defun gguf::%skip (rd n)
  ;; Pass over N bytes: the only way past a region on a stream that cannot seek.
  ;; checkpoint:skip-bytes does it in bounded reads and never stages what it
  ;; discards; this wrapper is here only to keep our own byte position honest.
  (when (> n 0)
    (checkpoint:skip-bytes (gguf::%rd-stream rd) n)
    (gguf::%advance rd n))
  nil)

;;; --- the metadata values ---------------------------------------------------------
;;; The thirteen GGUF value types. An array comes back as a simple vector, whatever
;;; its element type, so a 250k-token vocabulary is one vector of strings.

(defun gguf::%value (rd type)
  (cond ((= type 0) (gguf::%u8 rd))
        ((= type 1) (gguf::%i8 rd))
        ((= type 2) (gguf::%u16 rd))
        ((= type 3) (gguf::%i16 rd))
        ((= type 4) (gguf::%u32 rd))
        ((= type 5) (gguf::%i32 rd))
        ((= type 6) (gguf::%f32 rd))
        ((= type 7) (if (= (gguf::%u8 rd) 0) nil t))
        ((= type 8) (gguf::%string rd))
        ((= type 9) (gguf::%array rd))
        ((= type 10) (gguf::%u64 rd))
        ((= type 11) (gguf::%i64 rd))
        ((= type 12) (gguf::%f64 rd))
        (t (error "gguf: unknown metadata value type ~a" type))))

(defun gguf::%array (rd)
  (let* ((elem (gguf::%u32 rd)) (n (gguf::%u64 rd)) (v (make-array n)))
    (dotimes (i n v) (setf (aref v i) (gguf::%value rd elem)))))

;;; --- the ggml tensor types --------------------------------------------------------
;;; The name is for the error message a refusal has to carry; the block shape is
;;; what turns an element count into a byte count. A type this reader does not
;;; know the shape of still SKIPS correctly, because the walk moves to the next
;;; tensor's declared offset rather than past this one's size.

(defun gguf::%type-name (type)
  (cond ((= type 0) "F32")
        ((= type 1) "F16")
        ((= type 2) "Q4_0")
        ((= type 3) "Q4_1")
        ((= type 6) "Q5_0")
        ((= type 7) "Q5_1")
        ((= type 8) "Q8_0")
        ((= type 9) "Q8_1")
        ((= type 10) "Q2_K")
        ((= type 11) "Q3_K")
        ((= type 12) "Q4_K")
        ((= type 13) "Q5_K")
        ((= type 14) "Q6_K")
        ((= type 15) "Q8_K")
        ((= type 16) "IQ2_XXS")
        ((= type 17) "IQ2_XS")
        ((= type 18) "IQ3_XXS")
        ((= type 19) "IQ1_S")
        ((= type 20) "IQ4_NL")
        ((= type 21) "IQ3_S")
        ((= type 22) "IQ2_S")
        ((= type 23) "IQ4_XS")
        ((= type 24) "I8")
        ((= type 25) "I16")
        ((= type 26) "I32")
        ((= type 27) "I64")
        ((= type 28) "F64")
        ((= type 29) "IQ1_M")
        ((= type 30) "BF16")
        ((= type 34) "TQ1_0")
        ((= type 35) "TQ2_0")
        ((= type 39) "MXFP4")
        ((= type 40) "NVFP4")
        ((= type 41) "Q1_0")
        (t (format nil "type ~a" type))))

(defun gguf::%type-block (type)
  ;; (elements-per-block bytes-per-block), or nil when this reader does not know.
  (cond ((= type 0) '(1 4))
        ((= type 1) '(1 2))
        ((= type 30) '(1 2))
        ((= type 24) '(1 1))
        ((= type 25) '(1 2))
        ((= type 26) '(1 4))
        ((= type 27) '(1 8))
        ((= type 28) '(1 8))
        ((= type 2) '(32 18))
        ((= type 3) '(32 20))
        ((= type 6) '(32 22))
        ((= type 7) '(32 24))
        ((= type 8) '(32 34))
        ((= type 9) '(32 36))
        ((= type 10) '(256 84))
        ((= type 11) '(256 110))
        ((= type 12) '(256 144))
        ((= type 13) '(256 176))
        ((= type 14) '(256 210))
        ((= type 15) '(256 292))
        (t nil)))

(defun gguf::%type-bytes (type elements)
  (let ((block (gguf::%type-block type)))
    (if (null block) nil (* (floor elements (first block)) (second block)))))

;;; --- the header, the KV block and the tensor directory -------------------------------

(defstruct (gguf::%file (:constructor gguf::%make-file))
  ;; path        what was read
  ;; version     the GGUF version (3 for everything current)
  ;; alignment   general.alignment, 32 when the file does not say
  ;; metadata    the KV pairs, a string -> value hash table
  ;; names       the tensor names in FILE order
  ;; infos       name -> the directory plist (:name :dims :type :type-name :offset :bytes)
  ;; tensors     name -> the loaded array (empty when :metadata-only)
  path
  version
  alignment
  metadata
  names
  infos
  tensors)

(defun gguf::%read-magic (rd)
  (let ((buf (gguf::%buffer rd 4)))
    (read-sequence buf (gguf::%rd-stream rd) :end 4)
    (gguf::%advance rd 4)
    (unless (and (= (aref buf 0) 71) (= (aref buf 1) 71) (= (aref buf 2) 85)
                 (= (aref buf 3) 70))
      (error "gguf: not a GGUF file (the magic is ~a ~a ~a ~a, not \"GGUF\")"
             (aref buf 0) (aref buf 1) (aref buf 2) (aref buf 3)))
    nil))

(defun gguf::%read-metadata (rd count)
  (let ((h (make-hash-table :test 'equal)))
    (dotimes (i count h)
      (let* ((key (gguf::%string rd)) (type (gguf::%u32 rd)))
        (setf (gethash key h) (gguf::%value rd type))))))

(defun gguf::%read-infos (rd count)
  ;; The directory, in file order. dims are reversed here once and for all, so
  ;; every consumer sees ROW-MAJOR dimensions: an info reading [cols, rows] on
  ;; disk becomes (rows cols).
  (let ((names '()) (infos (make-hash-table :test 'equal)))
    (dotimes (i count)
      (let* ((name (gguf::%string rd)) (rank (gguf::%u32 rd)) (raw '()))
        (dotimes (k rank) (push (gguf::%u64 rd) raw))
        ;; raw is now already reversed, which is exactly row-major order
        (let* ((type (gguf::%u32 rd))
               (offset (gguf::%u64 rd))
               (elements (let ((n 1)) (dolist (d raw n) (setq n (* n d))))))
          (push name names)
          (setf (gethash name infos)
                (list :name name
                      :dims raw
                      :type type
                      :type-name (gguf::%type-name type)
                      :offset offset
                      :elements elements
                      :bytes (gguf::%type-bytes type elements))))))
    (list (nreverse names) infos)))

(defun gguf::%align-up (position alignment)
  (let ((r (mod position alignment)))
    (if (= r 0) position (+ position (- alignment r)))))

;;; --- loading a tensor ---------------------------------------------------------------

(defun gguf::%widen-into (rd count format dims element-type)
  ;; F16 / BF16 bit patterns into a fresh packed float array. The staging is the
  ;; checkpoint package's, shared with the safetensors reader: chunked through
  ;; one reused buffer, because a packed (unsigned-byte 16) vector costs EIGHT
  ;; bytes an element and a tensor staged whole would cost four times its size on
  ;; disk (.kb/checkpoint-readers.md).
  (let ((dst (checkpoint:make-tensor dims element-type)))
    (checkpoint:stage-float-bits (gguf::%rd-stream rd) count format dst)
    (gguf::%advance rd (* 2 count))
    dst))

(defun gguf::%read-tensor (rd info element-type)
  (let* ((type (getf info :type))
         (dims (getf info :dims))
         (count (getf info :elements))
         (name (getf info :name)))
    (cond ((= type 0)
           ;; F32 is a single-float array's own bytes: one transfer, no
           ;; conversion, no copy. A double-float destination is the one case
           ;; that needs a pass afterwards.
           (if (eq element-type 'double-float)
               (let ((staged (checkpoint:make-tensor dims 'single-float))
                     (dst (checkpoint:make-tensor dims 'double-float)))
                 (checkpoint:stage-float32 (gguf::%rd-stream rd) staged)
                 (gguf::%advance rd (* 4 count))
                 (dotimes (i count dst)
                   (setf (row-major-aref dst i) (row-major-aref staged i))))
               (let ((dst (checkpoint:make-tensor dims 'single-float)))
                 (checkpoint:stage-float32 (gguf::%rd-stream rd) dst)
                 (gguf::%advance rd (* 4 count))
                 dst)))
          ((= type 1) (gguf::%widen-into rd count :float16 dims element-type))
          ((= type 30) (gguf::%widen-into rd count :bfloat16 dims element-type))
          ((= type 8)
           (error (concatenate 'string
                   "gguf: the tensor ~a is Q8_0, which needs a quantized "
                   "weight matrix this reader does not have yet. Its "
                   "directory entry and the file's metadata read fine: pass "
                   ":metadata-only t, or :only naming the tensors you want.")
                  name))
          (t (error (concatenate 'string
                     "gguf: the tensor ~a is ~a; this reader loads F32, F16 "
                     "and BF16. Take the publisher's BF16 or F16 file, or "
                     "read this one with :metadata-only t or with :only "
                     "naming the tensors you want.") name
                    (gguf::%type-name type))))))

(defun gguf::%wanted-p (name only)
  (if (null only) t (if (member name only :test 'equal) t nil)))

(defun gguf::%by-offset (names infos)
  ;; The names sorted by their tensor's offset -- the order the sequential walk
  ;; has to visit them in. A GGUF writer emits them in this order anyway, so the
  ;; sort is a guard rather than a rearrangement.
  (let ((v (coerce names 'vector)))
    (sort v
          (lambda (a b)
            (< (getf (gethash a infos) :offset)
               (getf (gethash b infos) :offset))))))

(defun gguf::%load-tensors (rd file only element-type)
  (let* ((infos (gguf::%file-infos file))
         (names (gguf::%by-offset (gguf::%file-names file) infos))
         (data-start
          (gguf::%align-up (gguf::%rd-pos rd) (gguf::%file-alignment file)))
         (tensors (gguf::%file-tensors file)))
    (gguf::%skip rd (- data-start (gguf::%rd-pos rd)))
    (dotimes (i (length names) tensors)
      (let* ((name (aref names i))
             (info (gethash name infos))
             (target (+ data-start (getf info :offset))))
        (when (< target (gguf::%rd-pos rd))
          (error (concatenate 'string
                  "gguf: ~a starts at ~a, behind the read position ~a -- "
                  "this file's tensors are not in ascending offset order, "
                  "and a rontolisp stream cannot seek backwards") name target
                 (gguf::%rd-pos rd)))
        (gguf::%skip rd (- target (gguf::%rd-pos rd)))
        (when (gguf::%wanted-p name only)
          (setf (gethash name tensors)
                (gguf::%read-tensor rd info element-type)))))))

;;; --- the entry points -----------------------------------------------------------------

(defun gguf:read (path &key only metadata-only (element-type 'single-float))
  "Reads the GGUF checkpoint at PATH and returns a value the other gguf:
functions take. :METADATA-ONLY stops after the tensor directory, which is where
the hyperparameters and the whole tokenizer already are, so it never touches the
gigabytes. :ONLY is a list of tensor names to load; the rest are skipped (the
data is still read past, since a rontolisp stream cannot seek). :ELEMENT-TYPE
picks the packed float width every tensor lands in -- 'single-float (the default)
or 'double-float."
  (unless (or (eq element-type 'single-float) (eq element-type 'double-float))
    (error
     "gguf:read: :element-type must be 'single-float or 'double-float, not ~s"
     element-type))
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let ((rd (gguf::%open-reader s)))
      (gguf::%read-magic rd)
      (let* ((version (gguf::%u32 rd))
             (tensor-count (gguf::%u64 rd))
             (kv-count (gguf::%u64 rd))
             (metadata (gguf::%read-metadata rd kv-count))
             (directory (gguf::%read-infos rd tensor-count))
             (alignment
              (let ((a (gethash "general.alignment" metadata)))
                (if (and a (> a 0)) a 32)))
             (file
              (gguf::%make-file :path path
                                :version version
                                :alignment alignment
                                :metadata metadata
                                :names (first directory)
                                :infos (second directory)
                                :tensors (make-hash-table :test 'equal))))
        (unless metadata-only (gguf::%load-tensors rd file only element-type))
        file))))

(defun gguf:version (file)
  "The GGUF version FILE declares (3 for everything current)."
  (gguf::%file-version file))

(defun gguf:metadata (file)
  "FILE's key/value block as a hash table: the string key a GGUF writes
(\"general.architecture\", \"llama.block_count\", \"tokenizer.ggml.tokens\", ...)
to its value, with every array a simple vector."
  (gguf::%file-metadata file))

(defun gguf:metadata-value (file key &optional default)
  "The value FILE gives the metadata key KEY, or DEFAULT when it has no such key."
  ;; A GGUF bool false is a stored nil, so absence is told from falsity by
  ;; gethash's present-p rather than by the value.
  (multiple-value-bind (value present) (gethash key (gguf::%file-metadata file))
    (if present value default)))

(defun gguf:tensor-names (file)
  "FILE's tensor names, in the order the directory lists them."
  (gguf::%file-names file))

(defun gguf:tensor-info (file name)
  "The directory entry for the tensor NAME: a plist of :name, :dims (ROW-MAJOR --
the ggml order is reversed on the way in), :type (the ggml type id), :type-name
(\"F32\", \"BF16\", \"Q4_K\", ...), :elements, :bytes (nil for a type this reader
does not know the block shape of) and :offset (from the start of the data)."
  (gethash name (gguf::%file-infos file)))

(defun gguf:tensor (file name)
  "The packed float array loaded for the tensor NAME, or nil when it was not
loaded (:metadata-only, or an :only list without it)."
  (gethash name (gguf::%file-tensors file)))

(defun gguf:tokenizer-fields (file)
  "FILE's tokenizer fields as a plist, in the shape tokenizer:make-bpe and
tokenizer:make-sentencepiece take: :model (\"gpt2\" = byte-level BPE with merges,
\"llama\" = SentencePiece-style with scores), :pre (the pre-tokenizer name),
:tokens, :scores, :merges, :token-type, :bos and :eos. A field the file does not
carry is nil."
  (let ((m (gguf::%file-metadata file)))
    (list :model (gethash "tokenizer.ggml.model" m)
          :pre (gethash "tokenizer.ggml.pre" m)
          :tokens (gethash "tokenizer.ggml.tokens" m)
          :scores (gethash "tokenizer.ggml.scores" m)
          :merges (gethash "tokenizer.ggml.merges" m)
          :token-type (gethash "tokenizer.ggml.token_type" m)
          :bos (gethash "tokenizer.ggml.bos_token_id" m)
          :eos (gethash "tokenizer.ggml.eos_token_id" m))))
