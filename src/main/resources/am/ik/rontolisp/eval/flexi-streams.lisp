;; The flexi-streams package: a lite shim satisfying the built-in ASDF system
;; "flexi-streams". rontolisp streams carry no element type (every stream is a
;; character stream), so a flexi stream wrapper is the underlying stream
;; itself. Written in canonical shape; the package is seeded in
;; PackageRegistry.

(defun flexi-streams:make-flexi-stream (stream &rest args)
  (declare (ignore args))
  stream)
