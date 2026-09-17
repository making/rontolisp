;;;; Infinite streams in the SICP style: `cons-stream` delays its tail, so a
;;;; stream can describe an unbounded sequence and only the part that is looked
;;;; at is ever computed. Shows the integers, the Fibonacci numbers defined in
;;;; terms of themselves, the sieve of Eratosthenes, and a promise being forced
;;;; once.
;;;;
;;;; `cons-stream` and the stream procedures are SICP compatibility names, so
;;;; this file has no (import ...): an import list would hide them.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/streams.scm

(define (integers-from n)
  (cons-stream n (integers-from (+ n 1))))

(define integers (integers-from 1))

(define (add-streams s1 s2)
  (stream-map + s1 s2))

(define (scale-stream s factor)
  (stream-map (lambda (x) (* x factor)) s))

;; Defined in terms of itself: each element is the sum of the two before it.
(define fibs
  (cons-stream 0 (cons-stream 1 (add-streams (stream-cdr fibs) fibs))))

(define (divisible? x y) (= (remainder x y) 0))

(define (sieve s)
  (cons-stream (stream-car s)
               (sieve (stream-filter (lambda (x) (not (divisible? x (stream-car s))))
                                     (stream-cdr s)))))

(define primes (sieve (integers-from 2)))

;; The powers of two, a stream that doubles itself.
(define powers-of-two (cons-stream 1 (scale-stream powers-of-two 2)))

;; Partial sums: 1, 1+2, 1+2+3, ...
(define (partial-sums s)
  (define sums (cons-stream (stream-car s) (add-streams sums (stream-cdr s))))
  sums)

(define (show label s n)
  (display label)
  (display (stream-head s n))
  (newline))

(show "integers:     " integers 10)
(show "fibs:         " fibs 15)
(show "primes:       " primes 15)
(show "powers of 2:  " powers-of-two 11)
(show "partial sums: " (partial-sums integers) 10)

(display "fib(90) = ")
(display (stream-ref fibs 90))
(newline)
(display "the 500th prime = ")
(display (stream-ref primes 499))
(newline)

;; Only the elements that were demanded were computed.
(define computed 0)
(define (noisy-integers-from n)
  (set! computed (+ computed 1))
  (cons-stream n (noisy-integers-from (+ n 1))))
(define lazy (noisy-integers-from 0))
(stream-ref lazy 5)
(stream-ref lazy 5)
(display "cells computed after two (stream-ref s 5): ")
(display computed)
(newline)

(define p (delay (begin (display "[forcing] ") (* 6 7))))
(display (list (force p) (force p)))
(newline)
