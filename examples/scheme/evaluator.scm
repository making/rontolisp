;;;; An interpreter for a small stack language in the style of Forth and Joy. A
;;;; program is a list of words: a number pushes itself, a list pushes itself
;;;; unevaluated (a quotation: code as data), and a symbol runs the word it names.
;;;; A word is either built in -- a Scheme procedure over the machine -- or
;;;; defined by the program as a quotation. Recursion, conditionals and loops are
;;;; all words taking quotations. Each line of the program runs in turn and the
;;;; stack is shown after it; a failing line is reported and the run goes on.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/evaluator.scm

(import (scheme base) (scheme write))

(define-record-type machine
  (make-machine stack dictionary)
  machine?
  (stack machine-stack set-machine-stack!)
  (dictionary machine-dictionary set-machine-dictionary!))

(define (push! m value) (set-machine-stack! m (cons value (machine-stack m))))

(define (pop! m)
  (let ((stack (machine-stack m)))
    (if (null? stack) (error "stack underflow"))
    (set-machine-stack! m (cdr stack))
    (car stack)))

;; Run every word of a quotation.
(define (execute! m words) (for-each (lambda (word) (step! m word)) words))

(define (step! m word)
  (cond ((or (number? word) (boolean? word) (list? word)) (push! m word))
        ((assq word (machine-dictionary m))
         =>
         (lambda (definition)
           (let ((body (cdr definition)))
             (if (procedure? body) (body m) (execute! m body)))))
        (else (error "unknown word" word))))

;; Built-in words. A binary operator pops its right operand first.
(define (binary op)
  (lambda (m)
    (let* ((right (pop! m)) (left (pop! m))) (push! m (op left right)))))

(define built-ins
  (list (cons '+ (binary +)) (cons '- (binary -)) (cons '* (binary *))
        (cons '/ (binary /)) (cons 'mod (binary modulo)) (cons '= (binary =))
        (cons '< (binary <)) (cons '> (binary >))
        (cons 'dup
              (lambda (m)
                (let ((x (pop! m)))
                  (push! m x)
                  (push! m x)))) (cons 'drop (lambda (m) (pop! m)))
        (cons 'swap
              (lambda (m)
                (let* ((b (pop! m)) (a (pop! m)))
                  (push! m b)
                  (push! m a))))
        (cons 'over
              (lambda (m)
                (let* ((b (pop! m)) (a (pop! m)))
                  (push! m a)
                  (push! m b)
                  (push! m a))))
        ;; cond (then) (else) if
        (cons 'if
              (lambda (m)
                (let* ((else-part (pop! m))
                       (then-part (pop! m))
                       (test (pop! m)))
                  (execute! m (if test then-part else-part)))))
        ;; n (body) times
        (cons 'times
              (lambda (m)
                (let* ((body (pop! m)) (n (pop! m)))
                  (do ((i 0 (+ i 1))) ((= i n))
                    (execute! m body)))))
        ;; (name) (body) define
        (cons 'define
              (lambda (m)
                (let* ((body (pop! m)) (name (car (pop! m))))
                  (set-machine-dictionary! m
                   (cons (cons name body) (machine-dictionary m))))))
        (cons 'print
              (lambda (m)
                (write (pop! m))
                (newline)))))

(define program
  '((2 3 + 4 *)
    (drop)
    ((square) (dup *) define)
    (7 square 1 +)
    ((factorial) (dup 1 > (dup 1 - factorial *) (drop 1) if) define)
    (drop 20 factorial)
    ((fib) (dup 2 < () (dup 1 - fib swap 2 - fib +) if) define)
    (drop 15 fib)
    (drop 1 10 (2 *) times)
    ((countdown) (dup print dup 0 > (1 - countdown) () if) define)
    (drop 3 countdown)
    (drop 22 7 /)
    (drop drop)
    (1 nothing)))

(define (show-stack m)
  (write-string "  [")
  (let loop ((items (reverse (machine-stack m))) (first #t))
    (if (pair? items)
        (begin
          (if (not first) (write-string " "))
          (write (car items))
          (loop (cdr items) #f))))
  (write-string "]")
  (newline))

(define vm (make-machine '() built-ins))

(for-each (lambda (line)
            (write-string "> ")
            (write line)
            (newline)
            (guard (e
                    ((error-object? e)
                     (write-string "  error: ")
                     (write-string (error-object-message e))
                     (for-each (lambda (irritant)
                                 (write-string " ")
                                 (write irritant)) (error-object-irritants e))
                     (newline))) (execute! vm line))
            (show-stack vm)) program)
