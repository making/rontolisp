;;;; The safetensors package: reading the file a Hugging Face model page holds.
;;;;
;;;; The format: a little-endian u64 header length N, N bytes of JSON --
;;;; { "<name>": { "dtype": "BF16", "shape": [rows, cols], "data_offsets":
;;;; [begin, end] }, ..., "__metadata__": { ... } } -- then the tensor bytes,
;;;; each at its offsets from the end of the header, row-major. A big model is
;;;; sharded: model.safetensors.index.json maps { "weight_map": { "<name>":
;;;; "<shard file>" } }, and Qwen3.5-0.8B ships even its one shard that way.
;;;;
;;;; The tensors come back as packed float arrays of the file's shape, in the
;;;; width asked for (single-float by default): F32 by one read-sequence, F16
;;;; and BF16 staged through the checkpoint package's chunked widen. Since
;;;; file-position answers nil on every backend, a file is read front to back
;;;; in offset order, and a tensor the :only predicate excludes is skipped in
;;;; bounded reads rather than staged -- the vision tower and the speculative
;;;; head of a multimodal checkpoint cost their bytes of I/O and nothing else.
;;;;
;;;;   (safetensors:read path &key only element-type)
;;;;     -> a hash table, tensor name -> array. PATH is a .safetensors file, a
;;;;     .index.json, or a directory holding model.safetensors[.index.json].
;;;;     ONLY is nil (everything) or a predicate over the name; ELEMENT-TYPE
;;;;     'single-float (default) or 'double-float.
;;;;   (safetensors:header path) -> (values header data-start)
;;;;     the parsed JSON (name -> {"dtype" "shape" "data_offsets"}, plus
;;;;     "__metadata__") and the file offset the data starts at.
;;;;   (safetensors:entries header) -> ((name dtype shape begin end) ...)
;;;;     the tensor infos in file order, offsets relative to the data start.

(defun safetensors::%read-u64 (s)
  (let ((v 0) (mult 1))
    (dotimes (i 8 v)
      (setq v (+ v (* (read-byte s) mult)))
      (setq mult (* mult 256)))))

(defun safetensors::%read-header (s)
  ;; -> (values header data-start), S left at the first tensor byte.
  (let* ((n (safetensors::%read-u64 s))
         (bytes (make-array n :element-type '(unsigned-byte 8))))
    (read-sequence bytes s)
    (values (rontolisp:json-parse (rontolisp::%octets-to-string bytes))
            (+ 8 n))))

(defun safetensors:header (path)
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (safetensors::%read-header s)))

(defun safetensors:entries (header)
  (let ((entries '()))
    (maphash (lambda (name info)
               (unless (string= name "__metadata__")
                 (let ((offsets (gethash "data_offsets" info)))
                   (push (list name (gethash "dtype" info)
                               (coerce (gethash "shape" info) 'list)
                               (aref offsets 0) (aref offsets 1))
                         entries))))
             header)
    (sort entries (lambda (a b) (< (fourth a) (fourth b))))))

(defun safetensors::%format (dtype name)
  ;; :f32, :float16 or :bfloat16, or an error naming the tensor.
  (cond ((string= dtype "F32") :f32)
        ((string= dtype "F16") :float16)
        ((string= dtype "BF16") :bfloat16)
        (t (error "safetensors: ~a is ~a; supported dtypes: F32, F16, BF16" name dtype))))

(defun safetensors::%read-tensor (s dtype shape name element-type)
  ;; The next tensor of S, whose bytes begin at the stream's position.
  (let* ((format (safetensors::%format dtype name))
         (dims (if (= (length shape) 1) (first shape) shape))
         (count (reduce #'* shape :initial-value 1))
         (dst (checkpoint:make-tensor dims element-type)))
    (cond ((not (eq format :f32)) (checkpoint:stage-float-bits s count format dst))
          ((eq element-type 'single-float) (checkpoint:stage-float32 s dst))
          (t
           ;; an F32 tensor into a double array: through a single-float one
           (let ((f32 (checkpoint:make-tensor dims 'single-float)))
             (checkpoint:stage-float32 s f32)
             (dotimes (i count dst)
               (setf (row-major-aref dst i) (row-major-aref f32 i))))))))

(defun safetensors::%read-shard (path only element-type into)
  ;; Every tensor of one .safetensors file that ONLY admits, into the hash
  ;; table INTO, walking the file front to back.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (multiple-value-bind (header data-start) (safetensors::%read-header s)
      (let ((pos data-start))
        (dolist (entry (safetensors:entries header) into)
          (let ((name (first entry))
                (begin (+ data-start (fourth entry)))
                (end (+ data-start (fifth entry))))
            (when (< pos begin) (checkpoint:skip-bytes s (- begin pos)))
            (if (or (null only) (funcall only name))
                (setf (gethash name into)
                      (safetensors::%read-tensor s (second entry) (third entry) name
                                                 element-type))
                (checkpoint:skip-bytes s (- end begin)))
            (setq pos end)))))))

(defun safetensors::%ends-with (string suffix)
  (let ((n (length string)) (m (length suffix)))
    (and (>= n m) (string= string suffix :start1 (- n m)))))

(defun safetensors::%directory-of (path)
  ;; The directory part of a file path, with its trailing slash.
  (let ((i (position #\/ path :from-end t)))
    (if i (subseq path 0 (+ i 1)) "")))

(defun safetensors::%read-text (path)
  ;; The whole text of a file, line by line (file-length is nil on WASM).
  (with-open-file (s path)
    (let ((lines '()))
      (do ((line (read-line s nil) (read-line s nil)))
          ((null line))
        (push line lines))
      (apply #'concatenate 'string (nreverse lines)))))

(defun safetensors::%read-index (path only element-type into)
  ;; A model.safetensors.index.json: its shards, each read once and walked once,
  ;; whatever ONLY keeps of them.
  (let* ((index (rontolisp:json-parse (safetensors::%read-text path)))
         (weight-map (gethash "weight_map" index))
         (dir (safetensors::%directory-of path))
         (shards '()))
    (maphash (lambda (name file)
               (when (and (or (null only) (funcall only name))
                          (not (member file shards :test #'string=)))
                 (push file shards)))
             weight-map)
    (dolist (file (nreverse shards) into)
      (safetensors::%read-shard (concatenate 'string dir file) only element-type into))))

(defun safetensors:read (path &key only (element-type 'single-float))
  (let ((into (make-hash-table :test 'equal)))
    (cond ((safetensors::%ends-with path ".index.json")
           (safetensors::%read-index path only element-type into))
          ((safetensors::%ends-with path ".safetensors")
           (safetensors::%read-shard path only element-type into))
          (t
           (let* ((dir (if (safetensors::%ends-with path "/")
                           path
                           (concatenate 'string path "/")))
                  (index (concatenate 'string dir "model.safetensors.index.json"))
                  (single (concatenate 'string dir "model.safetensors")))
             (cond ((probe-file index)
                    (safetensors::%read-index index only element-type into))
                   ((probe-file single)
                    (safetensors::%read-shard single only element-type into))
                   (t (error "safetensors: no model.safetensors[.index.json] in ~a" dir))))))))
