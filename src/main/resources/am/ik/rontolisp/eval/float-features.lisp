;; The org.shirakumo.float-features package: thin wrappers over the IEEE 754
;; bit-reinterpretation primitives, satisfying the built-in ASDF system
;; "float-features". Bits travel as unsigned integers (bignums when the sign
;; bit is set), matching float-features' (unsigned-byte 64/32) contract.
;; Written in canonical shape; the package and its float-features nickname are
;; seeded in PackageRegistry.

(defun org.shirakumo.float-features:bits-double-float (bits)
  (%ieee754-double-from-bits bits))

(defun org.shirakumo.float-features:double-float-bits (float)
  (%ieee754-double-bits float))

(defun org.shirakumo.float-features:single-float-bits (float)
  (%ieee754-single-bits float))

(defun org.shirakumo.float-features:bits-single-float (bits)
  (%ieee754-single-from-bits bits))
