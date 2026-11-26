;; A leaf-module shim replacing com.inuoe.jzon's eisel-lemire.lisp: the same
;; package and exported contract (make-double: decimal mantissa, power-of-ten
;; exponent and sign flag -> double-float), implemented over rontolisp's native
;; float arithmetic instead of the original's u64/u128 bit algorithm and its
;; #.-generated power-of-ten tables. Only IEEE multiply/divide by exactly
;; representable powers of ten is used (10^e is exact for e <= 22 and each
;; step of the building loop is exact), so the result is identical on every
;; backend; the common |exp10| <= 22 case rounds exactly once, larger
;; exponents scale in 10^22 chunks and can be a few ulps off eisel-lemire's
;; exact rounding. The exponent is clamped to +/-700: past that a finite
;; double mantissa saturates to infinity/zero anyway, and the clamp bounds the
;; chunk loop for absurd exponents. Written in canonical shape (no
;; in-package); the defpackage form registers the package for the following
;; jzon.lisp, exactly like the replaced file's own header did.

(defpackage #:com.inuoe.jzon/eisel-lemire (:use #:cl) (:export #:make-double))

(defun com.inuoe.jzon/eisel-lemire::%pow10-exact (e)
  (do ((p 1.0d0) (i 0))
      ((>= i e) p)
    (setq p (* p 10.0d0))
    (setq i (+ i 1))))

(defun com.inuoe.jzon/eisel-lemire::%scale-by-pow10 (m exp10)
  (let ((exp10 (max -700 (min 700 exp10))))
    (cond ((zerop exp10) m)
          ((plusp exp10)
           (do ((v m) (e exp10))
               ((<= e 22) (* v (com.inuoe.jzon/eisel-lemire::%pow10-exact e)))
             (setq v (* v 1.0d22))
             (setq e (- e 22))))
          (t (do ((v m) (e (- exp10)))
                 ((<= e 22) (/ v (com.inuoe.jzon/eisel-lemire::%pow10-exact e)))
               (setq v (/ v 1.0d22))
               (setq e (- e 22)))))))

(defun com.inuoe.jzon/eisel-lemire:make-double (mantissa exp10 neg)
  (let ((d
         (com.inuoe.jzon/eisel-lemire::%scale-by-pow10
          (coerce mantissa 'double-float) exp10)))
    (if neg (- d) d)))
