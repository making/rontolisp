;;;; Infinite streams with `cons-stream`: the tail of a stream is delayed, so a
;;;; stream can stand for an unbounded sequence and only the part that is looked
;;;; at is ever computed. Every stream here comes from one generator, `unfold`,
;;;; which repeats a step on a seed: the natural numbers, the rows of Pascal's
;;;; triangle and the look-and-say sequence, then filtered and mapped views of
;;;; them, and a count showing each cell is computed once.
;;;;
;;;; `cons-stream` and the stream procedures are SICP-compatibility names, so
;;;; this file has no (import ...): an import list would hide them.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/streams.scm

;; seed, (step seed), (step (step seed)), ...
(define (unfold seed step) (cons-stream seed (unfold (step seed) step)))

(define naturals (unfold 0 (lambda (n) (+ n 1))))

;; Each row of Pascal's triangle is the pairwise sums of the row above, padded.
(define (next-row row) (map + (cons 0 row) (append row '(0))))
(define pascal (unfold '(1) next-row))

;; Look-and-say: read the digits aloud in runs ("1211" is one 1, one 2, two 1s).
(define (say digits)
  (let run ((rest (cdr digits)) (digit (car digits)) (count 1) (out '()))
    (cond ((null? rest) (reverse (cons digit (cons count out))))
          ((= (car rest) digit) (run (cdr rest) digit (+ count 1) out))
          (else (run (cdr rest) (car rest) 1 (cons digit (cons count out)))))))
(define look-and-say (unfold '(1) say))

(define (digit-sum n)
  (if (< n 10) n (+ (remainder n 10) (digit-sum (quotient n 10)))))
(define sevens (stream-filter (lambda (n) (= (digit-sum n) 7)) naturals))
(define triangular
  (stream-map (lambda (n) (quotient (* n (+ n 1)) 2)) naturals))

(define (show label value)
  (display label)
  (display value)
  (newline))

(show "naturals:          " (stream-head naturals 10))
(show "triangular:        " (stream-head triangular 10))
(show "digit sum 7:       " (stream-head sevens 10))
(show "the 100th one:     " (stream-ref sevens 99))
(show "Pascal row 6:      " (stream-ref pascal 6))
(show "middle of row 30:  " (list-ref (stream-ref pascal 30) 15))
(for-each (lambda (term)
            (show "look-and-say:      "
                  (apply string-append (map number->string term))))
          (stream-head look-and-say 6))
(show "length of term 30: " (length (stream-ref look-and-say 29)))

;; A step that counts its own calls: walking the same stream twice reuses the
;; cells the first walk computed.
(define steps 0)
(define counted
  (unfold 0
          (lambda (n)
            (set! steps (+ steps 1))
            (+ n 1))))
(stream-ref counted 5)
(stream-ref counted 5)
(show "steps after two walks to element 5: " steps)

(define answer
  (delay
    (begin
      (display "[computing] ")
      (* 6 7))))
(show "" (list (force answer) (force answer)))
