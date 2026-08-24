;; The kernel behind the packed float-array boundary measurement
;; (doc/en/guides/jvm-library.md, "The packed float array"). Compile it as a
;; library class -- see run.sh, which also builds and runs HandleBench.java:
;;
;;   rontolisp norm2-kernels.lisp -o com/example/Norm2Kernels.class --no-main --simd
;;
;; norm2 is exported twice on purpose. The typed wrapper is what a Java caller
;; uses; the untyped NORM2(Object) method every defun already has is the FLOOR
;; the wrapper is measured against, since it takes the packed array raw.

(defun norm2 (x) (sqrt (vec:dot x x)))

(rontolisp:jvm-export 'norm2 :params '(:float-vector) :returns :float)
