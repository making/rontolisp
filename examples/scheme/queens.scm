;;;; The n-queens puzzle by depth-first search with an explicit stack. A partial
;;;; board is the list of columns of the queens placed so far, the latest row
;;;; first; the search pops a board, pushes every safe one-row extension, and
;;;; collects the full boards. Counts the solutions for n = 1..8, the ones that
;;;; stay distinct under rotation and reflection, and draws the first for n = 8.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/queens.scm

(import (scheme base) (scheme write))

;; Can the next row take a queen in column col?
(define (free? col placed)
  (let check ((above placed) (gap 1))
    (or (null? above)
        (let ((c (car above)))
          (and (not (= c col)) (not (= (abs (- c col)) gap))
               (check (cdr above) (+ gap 1)))))))

;; The safe extensions of board by one row, lowest column first.
(define (extensions board n)
  (let try ((col n) (out '()))
    (cond ((= col 0) out)
          ((free? col board) (try (- col 1) (cons (cons col board) out)))
          (else (try (- col 1) out)))))

;; Every solution as columns from the top row down, in lexicographic order.
(define (solutions n)
  (let search ((stack (list '())) (found '()))
    (cond ((null? stack) (reverse found))
          ((= (length (car stack)) n)
           (search (cdr stack) (cons (reverse (car stack)) found)))
          (else
           (search (append (extensions (car stack) n) (cdr stack)) found)))))

;; The eight symmetries of the square, applied to a board as a column list.
(define (mirror board n) (map (lambda (c) (- (+ n 1) c)) board))

(define (quarter-turn board n)
  (let ((turned (make-vector n 0)))
    (let place ((row 1) (cols board))
      (if (pair? cols)
          (begin
            (vector-set! turned (- (car cols) 1) (- (+ n 1) row))
            (place (+ row 1) (cdr cols)))))
    (vector->list turned)))

(define (images board n)
  (let turn ((k 0) (b board) (out '()))
    (if (= k 4)
        out
        (turn (+ k 1) (quarter-turn b n) (cons b (cons (mirror b n) out))))))

(define (lex<? a b)
  (cond ((null? a) #f)
        ((< (car a) (car b)) #t)
        ((> (car a) (car b)) #f)
        (else (lex<? (cdr a) (cdr b)))))

(define (canonical board n)
  (let pick ((best board) (rest (images board n)))
    (cond ((null? rest) best)
          ((lex<? (car rest) best) (pick (car rest) (cdr rest)))
          (else (pick best (cdr rest))))))

(define (count-distinct boards n)
  (let tally ((boards boards) (seen '()))
    (if (null? boards)
        (length seen)
        (let ((key (canonical (car boards) n)))
          (tally (cdr boards) (if (member key seen) seen (cons key seen)))))))

(define (right-align value width)
  (let ((text (number->string value)))
    (string-append (make-string (max 0 (- width (string-length text))) #\space)
                   text)))

(write-string " n  solutions  distinct")
(newline)
(do ((n 1 (+ n 1))) ((> n 8))
  (let ((all (solutions n)))
    (write-string (right-align n 2))
    (write-string (right-align (length all) 11))
    (write-string (right-align (count-distinct all n) 10))
    (newline)))

(define first-of-eight (car (solutions 8)))
(newline)
(write-string "first solution for 8: ")
(write first-of-eight)
(newline)
(for-each (lambda (col)
            (do ((c 1 (+ c 1))) ((> c 8))
              (write-string (if (= c col) " Q" " .")))
            (newline)) first-of-eight)
