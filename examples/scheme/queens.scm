;;;; The eight queens puzzle, written with list operations: every board is built
;;;; by extending the boards of one row fewer (flatmap / filter), the style of
;;;; the classic textbook solution. Counts the solutions for n = 1..8 and draws
;;;; the first solution for n = 8.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/queens.scm

(import (scheme base) (scheme write))

(define (enumerate-interval low high)
  (let loop ((i high) (acc '()))
    (if (< i low) acc (loop (- i 1) (cons i acc)))))

(define (flatmap proc seq)
  (fold-right append '() (map proc seq)))

(define (fold-right op initial seq)
  (if (null? seq)
      initial
      (op (car seq) (fold-right op initial (cdr seq)))))

(define (keep pred seq)
  (cond ((null? seq) '())
        ((pred (car seq)) (cons (car seq) (keep pred (cdr seq))))
        (else (keep pred (cdr seq)))))

;; A position is a list of columns, the newest row first.
(define empty-board '())

(define (adjoin-position column positions) (cons column positions))

;; Is the newest queen attacked by one of the queens below it?
(define (safe? positions)
  (let ((column (car positions)))
    (let loop ((rest (cdr positions)) (distance 1))
      (cond ((null? rest) #t)
            ((or (= (car rest) column)
                 (= (abs (- (car rest) column)) distance))
             #f)
            (else (loop (cdr rest) (+ distance 1)))))))

(define (queens board-size)
  (define (queen-cols k)
    (if (= k 0)
        (list empty-board)
        (keep safe?
              (flatmap (lambda (rest-of-queens)
                         (map (lambda (column) (adjoin-position column rest-of-queens))
                              (enumerate-interval 1 board-size)))
                       (queen-cols (- k 1))))))
  (queen-cols board-size))

(define (draw-board positions size)
  (for-each
   (lambda (column)
     (do ((c 1 (+ c 1))) ((> c size))
       (write-string (if (= c column) " Q" " .")))
     (newline))
   (reverse positions)))

(do ((n 1 (+ n 1))) ((> n 8))
  (display "queens(")
  (display n)
  (display ") = ")
  (display (length (queens n)))
  (display " solutions")
  (newline))

(define first-solution (car (queens 8)))
(newline)
(display "first solution for 8: ")
(write (reverse first-solution))
(newline)
(draw-board first-solution 8)
