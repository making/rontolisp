;;;; chipz 0.8 (BSD): gzip / zlib / deflate decompression from unmodified
;;;; upstream sources.
;;;;
;;;; Run (all four backends), from the repository root:
;;;;   SYS=src/test/resources/chipz
;;;;   rontolisp examples/asdf/chipz-demo.lisp --system-path $SYS
;;;;   rontolisp examples/asdf/chipz-demo.lisp -o Prog.class --system-path $SYS && java Prog
;;;;   rontolisp examples/asdf/chipz-demo.lisp -o demo.wasm --system-path $SYS \
;;;;     && wasmtime run demo.wasm
;;;;   rontolisp examples/asdf/chipz-demo.lisp -o demo-c.wasm --component --system-path $SYS \
;;;;     && wasmtime run demo-c.wasm
;;;;
;;;; chipz uses catch/throw, so every compiled artifact is in EH mode.

(asdf:load-system "chipz")

;;; "Hello, chipz!" gzipped, as the 33 octets a gzip writer produces.
(defparameter *gzipped*
  (make-array 33
              :element-type '(unsigned-byte 8)
              :initial-contents '(31 139 8 0 0 0 0 0 2 255 243 72 205 201 201
                                  215 81 72 206 200 44 168 82 4 0 46 239 228 135
                                  13 0 0 0)))

(defun octets-to-string (octets)
  (let ((out (make-array (length octets) :element-type 'character)))
    (dotimes (i (length octets) out)
      (setf (aref out i) (code-char (aref octets i))))))

;;; Decompress into a fresh vector.
(let ((raw (chipz:decompress nil 'chipz:gzip *gzipped*)))
  (format t "~a octets -> ~s~%" (length *gzipped*) (octets-to-string raw)))

;;; Decompress into a buffer you supply, which answers how much of each side
;;; was used -- the shape a streaming caller wants.
(let ((buffer (make-array 64 :element-type '(unsigned-byte 8)))
      (state (chipz:make-dstate 'chipz:gzip)))
  (multiple-value-bind (consumed produced)
      (chipz:decompress buffer state *gzipped*)
    (format t "consumed ~a, produced ~a~%" consumed produced)))
