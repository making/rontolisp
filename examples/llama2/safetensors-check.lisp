;;;; Reads safetensors-check.safetensors (written by safetensors-fixture.py,
;;;; beside this file) through safetensors.lisp and prints what it found: the
;;;; header, every supported dtype widened into single and double floats, the
;;;; refusal of an I64 tensor by name, the :only predicate skipping it, and the
;;;; same tensors through the sharded index. The values are exact in every
;;;; width, so the text is the same on every backend.
;;;;
;;;;   rontolisp safetensors-check.lisp          # from this directory

;; safetensors: and checkpoint: are shipped libraries -- nothing to load

(defun print-tensor (name a)
  (format t "~a ~a ~a:" name (array-element-type a) (array-dimensions a))
  (dotimes (i (array-total-size a)) (format t " ~,6f" (row-major-aref a i)))
  (terpri))

(defun print-all (table)
  (let ((names '()))
    (maphash (lambda (k v) (push k names)) table)
    (dolist (name (sort names #'string<)) (print-tensor name (gethash name table)))))

;;; the header
(multiple-value-bind (header start) (safetensors:header "safetensors-check.safetensors")
  (format t "data starts at ~a~%" start)
  (dolist (entry (safetensors:entries header))
    (format t "~a ~a ~a ~a..~a~%" (first entry) (second entry) (third entry)
            (fourth entry) (fifth entry)))
  (format t "metadata note: ~a~%" (gethash "note" (gethash "__metadata__" header))))

;;; every dtype, the I64 refused
(handler-case (safetensors:read "safetensors-check.safetensors")
  (error (e) (format t "refused: ~a~%" e)))

;;; :only skips it (and the skipped bytes are passed over, not staged)
(format t "-- single-float, without ids~%")
(print-all (safetensors:read "safetensors-check.safetensors"
                             :only (lambda (name) (not (string= name "ids")))))

;;; the same into double floats
(format t "-- double-float~%")
(print-all (safetensors:read "safetensors-check.safetensors"
                             :only (lambda (name) (not (string= name "ids")))
                             :element-type 'double-float))

;;; the sharded pair through the index, and a prefix filter over it
(format t "-- sharded~%")
(print-all (safetensors:read "safetensors-check.index.json"))
(format t "-- sharded, only half.*~%")
(print-all (safetensors:read "safetensors-check.index.json"
                             :only (lambda (name) (string= name "half." :end1 5))))
