;;;; The kernels behind the --gpu residency measurement (.kb/jvm-export.md,
;;;; "--gpu residency, and why the handle does not materialize"). Compile them as
;;;; a library class -- see run.sh, which also builds and runs GpuResidencyBench:
;;;;
;;;;   rontolisp gpu-kernels.lisp -o com/example/GpuKernels.class --no-main --gpu
;;;;   rontolisp gpu-kernels.lisp -o com/example/CpuKernels.class --no-main
;;;;
;;;; The same source twice: the second build is the oracle the first is checked
;;;; against, which is how every other --gpu member is checked.
;;;;
;;;; One GEMV is the whole kernel on purpose. It is what a decode loop is made of,
;;;; it is the member that keeps its matrix resident (.kb/gpu.md, "The GEMV, and
;;;; the matrix that stays"), and it reads nothing back -- a normalization would
;;;; fold to a scalar and drag the vector home every iteration, which is the very
;;;; traffic this measurement is about.

(defun step-once (w x) (vec:matvec w x))

;;; The same chain WITHOUT a boundary crossing per iteration: the floor the Java
;;; chain is measured against. If the handle defeated residency, the two would
;;; not be the same number.
(defun run-steps (w x n) (dotimes (i n x) (setq x (vec:matvec w x))))

(rontolisp:jvm-export 'step-once
                      :params '(:float-matrix :float-vector)
                      :returns :float-vector
                      :as "step")
(rontolisp:jvm-export 'run-steps
                      :params '(:float-matrix :float-vector :s32)
                      :returns :float-vector
                      :as "steps")
