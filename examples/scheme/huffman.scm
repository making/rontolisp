;;;; Huffman coding: count the characters of a message, build the optimal prefix
;;;; code tree from the counts, print the code table, then encode the message to
;;;; bits and decode the bits back. Leaves and inner nodes are records; the
;;;; weight-ordered set is a sorted list.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/huffman.scm

(import (scheme base) (scheme write))

(define-record-type leaf
  (make-leaf symbol weight)
  leaf?
  (symbol leaf-symbol)
  (weight leaf-weight))

(define-record-type node
  (make-node left right symbols weight)
  node?
  (left node-left)
  (right node-right)
  (symbols node-symbols)
  (weight node-weight))

(define (symbols tree)
  (if (leaf? tree) (list (leaf-symbol tree)) (node-symbols tree)))

(define (weight tree) (if (leaf? tree) (leaf-weight tree) (node-weight tree)))

(define (make-code-tree left right)
  (make-node left right (append (symbols left) (symbols right))
             (+ (weight left) (weight right))))

;; Insert into a list kept in ascending weight order.
(define (adjoin-set x set)
  (cond ((null? set) (list x))
        ((< (weight x) (weight (car set))) (cons x set))
        (else (cons (car set) (adjoin-set x (cdr set))))))

;; Character counts as an association list, in first-seen order.
(define (frequencies chars)
  (let loop ((chars chars) (table '()))
    (if (null? chars)
        (reverse table)
        (let ((entry (assv (car chars) table)))
          (if entry
              (begin
                (set-cdr! entry (+ (cdr entry) 1))
                (loop (cdr chars) table))
              (loop (cdr chars) (cons (cons (car chars) 1) table)))))))

(define (generate-huffman-tree pairs)
  (let merge ((set
               (fold-left (lambda (set pair)
                            (adjoin-set (make-leaf (car pair) (cdr pair)) set))
                          '() pairs)))
    (if (null? (cdr set))
        (car set)
        (merge (adjoin-set (make-code-tree (car set) (cadr set)) (cddr set))))))

(define (fold-left f init items)
  (if (null? items) init (fold-left f (f init (car items)) (cdr items))))

(define (encode-symbol sym tree)
  (let walk ((tree tree) (bits '()))
    (cond ((leaf? tree) (reverse bits))
          ((memv sym (symbols (node-left tree)))
           (walk (node-left tree) (cons 0 bits)))
          ((memv sym (symbols (node-right tree)))
           (walk (node-right tree) (cons 1 bits)))
          (else (error "symbol not in tree" sym)))))

(define (encode chars tree)
  (apply append (map (lambda (c) (encode-symbol c tree)) chars)))

(define (decode bits tree)
  (let loop ((bits bits) (branch tree) (out '()))
    (cond ((leaf? branch) (loop bits tree (cons (leaf-symbol branch) out)))
          ((null? bits) (reverse out))
          ((= (car bits) 0) (loop (cdr bits) (node-left branch) out))
          (else (loop (cdr bits) (node-right branch) out)))))

(define (bits->string bits)
  (list->string (map (lambda (b) (if (= b 0) #\0 #\1)) bits)))

(define message "abracadabra alakazam")
(define chars (string->list message))
(define table (frequencies chars))
(define tree (generate-huffman-tree table))

(display "message: ")
(write message)
(newline)
(display "code table:")
(newline)
(for-each (lambda (entry)
            (write-string "  ")
            (write (car entry))
            (write-string " x")
            (display (cdr entry))
            (write-string " -> ")
            (display (bits->string (encode-symbol (car entry) tree)))
            (newline)) table)

(define bits (encode chars tree))
(display "encoded: ")
(display (bits->string bits))
(newline)
(display "bits: ")
(display (length bits))
(display " (fixed 8-bit: ")
(display (* 8 (length chars)))
(display ")")
(newline)

(define decoded (list->string (decode bits tree)))
(display "decoded: ")
(write decoded)
(newline)
(display "round trip: ")
(display (if (string=? decoded message) "ok" "MISMATCH"))
(newline)
