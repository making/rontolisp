;;;; The checkpoint package: staging a published model's tensors into packed
;;;; float arrays, shared by every checkpoint reader (safetensors, GGUF).
;;;;
;;;; A published checkpoint holds its matrices as IEEE f16 or bf16 bit patterns
;;;; (F32 in the norms and the small vectors). The bits are read with
;;;; read-sequence into a packed (unsigned-byte 16) vector -- raw little-endian
;;;; elements, one bulk transfer -- and widened into the destination with
;;;; rontolisp:widen-float-bits. The staging vector is a LispIntVector, which
;;;; the interpreter and the JVM store as a long[]: EIGHT bytes an element, so a
;;;; tensor staged whole would cost four times its file size in temporaries
;;;; (8.8 GB for a 1.1B-element tensor). The staging is therefore CHUNKED, one
;;;; buffer of %chunk elements (2 MB on disk, 8 MB in memory) reused for every
;;;; tensor of every file, and widen-float-bits' :start offset is what lets a
;;;; chunk land in the middle of the destination. The reader hands the STREAM
;;;; over, never a staged tensor, so a caller cannot get the chunking wrong.
;;;;
;;;; The destination is allocated here too, and CHECKED: make-array
;;;; :element-type answers a boxed general array for an element type it does
;;;; not recognise instead of signalling, and a checkpoint read into one would
;;;; only show as slow, wrong output much later. checkpoint:make-tensor refuses
;;;; that. (vec:zeros is not used for the same reason: its width dispatch is an
;;;; eq test with a double default.)
;;;;
;;;; file-position answers nil on every backend (streams do not reposition), so
;;;; a reader walks its file front to back in tensor order: checkpoint:skip-bytes
;;;; is how it passes over a tensor it was told not to load, in bounded reads
;;;; through a scratch buffer, never staging it.

(defparameter checkpoint::%chunk 1048576)

(defvar checkpoint::%staging-buffer nil)

(defvar checkpoint::%skip-buffer nil)

(defun checkpoint::%staging-buffer ()
  (or checkpoint::%staging-buffer
      (setq checkpoint::%staging-buffer
            (make-array checkpoint::%chunk :element-type '(unsigned-byte 16)))))

(defun checkpoint:make-tensor (shape element-type)
  ;; A packed float array of SHAPE (a dimension list, or one integer) and
  ;; ELEMENT-TYPE ('single-float or 'double-float), verified to be packed.
  (let ((a (make-array shape :element-type element-type :initial-element 0.0)))
    (unless (eq (array-element-type a) element-type)
      (error "checkpoint:make-tensor: ~a is not a packed float element type (got ~a)"
             element-type (array-element-type a)))
    a))

(defun checkpoint:stage-float-bits (stream count format dst &key (start 0))
  ;; Read COUNT little-endian 16-bit words from STREAM, at its current
  ;; position, as FORMAT (:float16 or :bfloat16) bit patterns, widened into
  ;; DST -- a packed float array of any rank -- row-major from flat index START.
  ;; Returns DST. Chunked through the one staging buffer; the last, shorter
  ;; chunk goes through a buffer of its own size, since widen-float-bits widens
  ;; the whole vector it is given.
  (let ((buf (checkpoint::%staging-buffer)) (done 0))
    (loop while (< done count)
          do (let ((n (min checkpoint::%chunk (- count done))))
               (if (= n checkpoint::%chunk)
                   (progn
                     (read-sequence buf stream)
                     (rontolisp:widen-float-bits buf format dst :start (+ start done)))
                   (let ((tail (make-array n :element-type '(unsigned-byte 16))))
                     (read-sequence tail stream)
                     (rontolisp:widen-float-bits tail format dst :start (+ start done))))
               (setq done (+ done n))))
    dst))

(defun checkpoint:stage-float32 (stream dst)
  ;; An F32 tensor is the destination's own bytes: one bulk transfer into DST,
  ;; a packed single-float array of any rank. Returns DST.
  (read-sequence dst stream)
  dst)

(defun checkpoint:skip-bytes (stream n)
  ;; Pass over N bytes of STREAM, in bounded reads through a scratch buffer.
  ;; Returns N.
  (let ((buf (or checkpoint::%skip-buffer
                 (setq checkpoint::%skip-buffer
                       (make-array 65536 :element-type '(unsigned-byte 8)))))
        (left n))
    (loop while (> left 0)
          do (let ((k (min 65536 left)))
               (read-sequence buf stream :end k)
               (setq left (- left k))))
    n))
