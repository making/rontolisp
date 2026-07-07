;;;; Sieve of Eratosthenes in rontolisp
;;;; Uses a boolean array as the sieve, demonstrating make-array, aref,
;;;; (setf (aref ...)), and list accumulation.
;;;; Pure list/array/number code -> runs on all three backends.
;;;;
;;;; Run:
;;;;   rontolisp examples/console/sieve.lisp
;;;;   rontolisp examples/console/sieve.lisp -o Sieve.class && java Sieve
;;;;   rontolisp examples/console/sieve.lisp -o sieve.wasm && wasmtime run -W gc sieve.wasm

(defun sieve-of-eratosthenes (limit)
  "Return a list of all primes up to LIMIT using the Sieve of Eratosthenes."
  (let ((sieve (make-array (1+ limit) :initial-element t))
        (primes nil))
    (dotimes (i (1+ limit) (reverse primes))
      (when (and (>= i 2) (aref sieve i))
        (push i primes)
        ;; Mark all multiples of i starting from i*i
        (let ((j (* i i)))
          (while (<= j limit)
            (setf (aref sieve j) nil)
            (setq j (+ j i))))))))

(defun take (n lst)
  "Return the first N elements of LST."
  (if (or (<= n 0) (null lst))
      nil
      (cons (car lst) (take (1- n) (cdr lst)))))

(defun prime-factorize (n)
  "Return the prime factorization of N as a list of (prime . exponent) pairs."
  (let ((primes (sieve-of-eratosthenes (ceiling (sqrt n))))
        (factors nil)
        (remaining n))
    (dolist (p primes)
      (let ((count 0))
        (while (= (mod remaining p) 0)
          (setq remaining (/ remaining p))
          (setq count (1+ count)))
        (when (> count 0)
          (push (cons p count) factors))))
    (when (> remaining 1)
      (push (cons remaining 1) factors))
    (reverse factors)))

(format t "Primes up to 50:~%  ~a~%" (sieve-of-eratosthenes 50))
(format t "~%First 20 primes:~%  ~a~%" (take 20 (sieve-of-eratosthenes 80)))
(format t "~%Prime factorizations:~%")
(dolist (n '(60 100 315 256))
  (format t "  ~4d = ~a~%" n (prime-factorize n)))
