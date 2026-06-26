;; hello.lisp -- a self-contained program compiled to WASM (WASI Preview 1).
;; Its only "interface" with the host is what it prints to stdout, which the
;; JavaScript WASI shim captures and shows on the page.

(defun fib (n)
  (if (< n 2)
      n
      (+ (fib (- n 1)) (fib (- n 2)))))

(format t "Hello from rontolisp, compiled to WebAssembly!~%")
(format t "~%")
(format t "The first 10 Fibonacci numbers:~%")
(dotimes (i 10)
  (format t "  fib(~a) = ~a~%" i (fib i)))

(format t "~%")
(format t "Exact rational arithmetic: 1/3 + 1/6 = ~a~%" (+ (/ 1 3) (/ 1 6)))
