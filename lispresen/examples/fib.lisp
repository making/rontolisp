;;;; fib.lisp -- read by the runnable-code slide of intro.lisp, which shows lines 4-6.

;; The naive recursion: fib(n) → fib(n - 1) + fib(n - 2).
(defun fib (n)
  (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(fib 20)
