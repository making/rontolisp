;; The org.shirakumo.float-features package: thin wrappers over the IEEE 754
;; bit-reinterpretation primitives, satisfying the built-in ASDF system
;; "float-features". Bits travel as unsigned integers (bignums when the sign
;; bit is set), matching float-features' (unsigned-byte 64/32) contract.
;; Written in canonical shape; the package and its float-features nickname are
;; seeded in PackageRegistry. Every float here is one IEEE double, so the short, single
;; and long constants are the double's; the WASM numeric model carries no 64-bit bit
;; patterns, so there it computes them.

(defun org.shirakumo.float-features:bits-double-float (bits)
  (%ieee754-double-from-bits bits))

(defun org.shirakumo.float-features:double-float-bits (float)
  (%ieee754-double-bits float))

(defun org.shirakumo.float-features:single-float-bits (float)
  (%ieee754-single-bits float))

(defun org.shirakumo.float-features:bits-single-float (bits)
  (%ieee754-single-from-bits bits))

(defconstant org.shirakumo.float-features:double-float-positive-infinity
  #-rontolisp-wasm (%ieee754-double-from-bits #x7FF0000000000000)
  #+rontolisp-wasm (* most-positive-double-float 10.0))

(defconstant org.shirakumo.float-features:double-float-negative-infinity
  (- org.shirakumo.float-features:double-float-positive-infinity))

(defconstant org.shirakumo.float-features:double-float-nan
  #-rontolisp-wasm (%ieee754-double-from-bits #x7FF8000000000000)
  #+rontolisp-wasm
  (- org.shirakumo.float-features:double-float-positive-infinity
     org.shirakumo.float-features:double-float-positive-infinity))

(defconstant org.shirakumo.float-features:short-float-positive-infinity
  org.shirakumo.float-features:double-float-positive-infinity)
(defconstant org.shirakumo.float-features:short-float-negative-infinity
  org.shirakumo.float-features:double-float-negative-infinity)
(defconstant org.shirakumo.float-features:short-float-nan
  org.shirakumo.float-features:double-float-nan)
(defconstant org.shirakumo.float-features:single-float-positive-infinity
  org.shirakumo.float-features:double-float-positive-infinity)
(defconstant org.shirakumo.float-features:single-float-negative-infinity
  org.shirakumo.float-features:double-float-negative-infinity)
(defconstant org.shirakumo.float-features:single-float-nan
  org.shirakumo.float-features:double-float-nan)
(defconstant org.shirakumo.float-features:long-float-positive-infinity
  org.shirakumo.float-features:double-float-positive-infinity)
(defconstant org.shirakumo.float-features:long-float-negative-infinity
  org.shirakumo.float-features:double-float-negative-infinity)
(defconstant org.shirakumo.float-features:long-float-nan
  org.shirakumo.float-features:double-float-nan)

(defun org.shirakumo.float-features:float-infinity-p (float)
  (or (= float org.shirakumo.float-features:double-float-positive-infinity)
      (= float org.shirakumo.float-features:double-float-negative-infinity)))

(defun org.shirakumo.float-features:float-nan-p (float) (/= float float))

(defun org.shirakumo.float-features:long-float-bits (float)
  (%ieee754-double-bits float))

(defun org.shirakumo.float-features:bits-long-float (bits)
  (%ieee754-double-from-bits bits))

(defun org.shirakumo.float-features:short-float-bits (float)
  (error "short-float-bits: there is no 16-bit float here"))

(defun org.shirakumo.float-features:bits-short-float (bits)
  (error "bits-short-float: there is no 16-bit float here"))

;; No backend traps on a float operation or changes its rounding.
(defmacro org.shirakumo.float-features:with-float-traps-masked
    (traps &body body)
  `(progn ,@body))

(defmacro org.shirakumo.float-features:with-rounding-mode (mode &body body)
  `(progn ,@body))
