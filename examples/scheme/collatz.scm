;;;; Collatz chains: for every start below a limit, the number of steps down to 1,
;;;; memoised in a vector. Everything iterates through named `let` and `do`, which
;;;; run as loops, so a million iterations use no stack. Prints a right-aligned
;;;; table and a histogram, and uses call/cc to stop a search early.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/collatz.scm

(import (scheme base) (scheme write))

(define limit 100000)

;; steps[n] for n < limit, 0 meaning "not yet known" (steps[1] is 0 by definition).
(define steps (make-vector limit 0))

;; Walk forward until a known value, collecting the path, then fill the path in.
(define (chain-length start)
  (let walk ((n start) (path '()))
    (if (or (= n 1) (and (< n limit) (> (vector-ref steps n) 0)))
        (let fill ((path path) (count (if (= n 1) 0 (vector-ref steps n))))
          (if (null? path)
              count
              (let ((m (car path)) (count (+ count 1)))
                (if (< m limit) (vector-set! steps m count))
                (fill (cdr path) count))))
        (walk (if (even? n) (quotient n 2) (+ (* 3 n) 1)) (cons n path)))))

(define (pad-left text width)
  (if (< (string-length text) width)
      (string-append (make-string (- width (string-length text)) #\space) text)
      text))

(define (column value width)
  (write-string (pad-left (number->string value) width)))

;; The longest chain for each power of ten.
(write-string "     below   longest start   steps")
(newline)
(let loop ((bound 10) (n 1) (best-start 1) (best-steps 0))
  (cond ((> bound limit) 'done)
        ((= n bound)
         (column bound 10)
         (column best-start 16)
         (column best-steps 8)
         (newline)
         (loop (* bound 10) n best-start best-steps))
        (else
         (let ((s (chain-length n)))
           (if (> s best-steps)
               (loop bound (+ n 1) n s)
               (loop bound (+ n 1) best-start best-steps))))))

;; How many starts below 1000 need 0-19, 20-39, ... steps.
(newline)
(write-string "steps below 1000")
(newline)
(define buckets (make-vector 10 0))
(do ((n 1 (+ n 1))) ((= n 1000))
  (let ((b (min 9 (quotient (vector-ref steps n) 20))))
    (vector-set! buckets b (+ (vector-ref buckets b) 1))))
(do ((b 0 (+ b 1))) ((= b 10))
  (column (* b 20) 3)
  (write-string (if (= b 9) "+     " (string-append "-" (pad-left (number->string (+ (* b 20) 19)) 3) "  ")))
  (write-string (make-string (quotient (vector-ref buckets b) 5) #\#))
  (write-string " ")
  (display (vector-ref buckets b))
  (newline))

;; The first start whose chain is longer than a threshold, found by escaping out
;; of a for-each through its continuation.
(define (first-longer-than threshold starts)
  (call/cc
   (lambda (return)
     (for-each (lambda (n) (if (> (chain-length n) threshold) (return n))) starts)
     #f)))

(define (range from to)
  (let loop ((i (- to 1)) (acc '()))
    (if (< i from) acc (loop (- i 1) (cons i acc)))))

(newline)
(define starts (range 1 limit))
(for-each
 (lambda (threshold)
   (display "first start with more than ")
   (display threshold)
   (display " steps: ")
   (display (first-longer-than threshold starts))
   (newline))
 '(100 200 300 400))

;; A million iterations of a named let: a loop, so constant stack on every backend.
(define (sum-of-lengths iterations)
  (let loop ((i 0) (total 0))
    (if (= i iterations)
        total
        (loop (+ i 1) (+ total (vector-ref steps (+ 1 (remainder i (- limit 1)))))))))
(display "sum of the first 1,000,000 cyclic lookups: ")
(display (sum-of-lengths 1000000))
(newline)
