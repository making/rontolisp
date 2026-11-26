;;;; Canonical Huffman coding, the way DEFLATE stores a code: only each symbol's
;;;; code LENGTH comes from the Huffman merge, and the bit patterns are then
;;;; handed out in (length, symbol) order, so the lengths alone describe the
;;;; whole code. No tree is ever built: a merge just lengthens the codes of every
;;;; symbol in the two lightest groups, and decoding matches (length, value)
;;;; pairs. Encodes a message, decodes it back and checks the Kraft sum.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/huffman.scm

(import (scheme base) (scheme write))

;; One row of the code table.
(define-record-type entry
  (make-entry symbol count bits code)
  entry?
  (symbol entry-symbol)
  (count entry-count)
  (bits entry-bits set-entry-bits!)
  (code entry-code set-entry-code!))

;; Character counts, in first-seen order.
(define (tally chars)
  (let loop ((chars chars) (rows '()))
    (cond ((null? chars) (reverse rows))
          ((assv (car chars) rows)
           =>
           (lambda (row)
             (set-cdr! row (+ (cdr row) 1))
             (loop (cdr chars) rows)))
          (else (loop (cdr chars) (cons (cons (car chars) 1) rows))))))

;; A group is (weight . entries). Remove the lightest group from a list,
;; answering it and the rest; the earliest wins a tie.
(define (take-lightest groups)
  (let scan ((rest (cdr groups)) (best (car groups)) (others '()))
    (cond ((null? rest) (values best (reverse others)))
          ((< (car (car rest)) (car best))
           (scan (cdr rest) (car rest) (cons best others)))
          (else (scan (cdr rest) best (cons (car rest) others))))))

;; Merge the two lightest groups until one is left; each merge adds one bit to
;; the code of every entry in either group.
(define (assign-lengths! entries)
  (let merge
      ((groups (map (lambda (e) (cons (entry-count e) (list e))) entries)))
    (if (pair? (cdr groups))
        (let*-values (((a rest) (take-lightest groups))
                      ((b rest) (take-lightest rest)))
          (for-each (lambda (e) (set-entry-bits! e (+ (entry-bits e) 1)))
                    (append (cdr a) (cdr b)))
          (merge
           (cons (cons (+ (car a) (car b)) (append (cdr a) (cdr b))) rest))))))

;; Stable merge sort.
(define (sort-by before? items)
  (define (merge-lists a b)
    (cond ((null? a) b)
          ((null? b) a)
          ((before? (car b) (car a)) (cons (car b) (merge-lists a (cdr b))))
          (else (cons (car a) (merge-lists (cdr a) b)))))
  (let split ((items items))
    (if (or (null? items) (null? (cdr items)))
        items
        (let halve ((slow items) (fast (cdr items)) (front '()))
          (if (or (null? fast) (null? (cdr fast)))
              (merge-lists (split (reverse (cons (car slow) front)))
                           (split (cdr slow)))
              (halve (cdr slow) (cddr fast) (cons (car slow) front)))))))

(define (canonical-order a b)
  (or (< (entry-bits a) (entry-bits b))
      (and (= (entry-bits a) (entry-bits b))
           (char<? (entry-symbol a) (entry-symbol b)))))

;; Consecutive codes within a length; a longer length shifts the next code left.
(define (assign-codes! ordered)
  (let next ((rows ordered) (code 0) (bits (entry-bits (car ordered))))
    (if (pair? rows)
        (let* ((row (car rows))
               (code (* code (expt 2 (- (entry-bits row) bits)))))
          (set-entry-code! row code)
          (next (cdr rows) (+ code 1) (entry-bits row))))))

(define (code-string row)
  (let ((digits (number->string (entry-code row) 2)))
    (string-append (make-string (- (entry-bits row) (string-length digits)) #\0)
                   digits)))

(define (encode chars table)
  (apply string-append
         (map (lambda (c)
                (code-string
                 (let find ((rows table))
                   (if (char=? (entry-symbol (car rows)) c)
                       (car rows)
                       (find (cdr rows)))))) chars)))

;; Read bits one at a time, growing (length, value) until a row matches.
(define (decode bits table)
  (let read-bit ((i 0) (len 0) (value 0) (out '()))
    (let ((hit
           (let find ((rows table))
             (cond ((null? rows) #f)
                   ((and (= (entry-bits (car rows)) len)
                         (= (entry-code (car rows)) value))
                    (car rows))
                   (else (find (cdr rows)))))))
      (cond (hit (read-bit i 0 0 (cons (entry-symbol hit) out)))
            ((= i (string-length bits)) (list->string (reverse out)))
            (else
             (read-bit (+ i 1) (+ len 1)
                       (+ (* 2 value) (if (char=? (string-ref bits i) #\1) 1 0))
                       out))))))

(define message "she sells sea shells by the sea shore")
(define chars (string->list message))
(define table
  (map (lambda (row) (make-entry (car row) (cdr row) 0 0)) (tally chars)))
(assign-lengths! table)
(define ordered (sort-by canonical-order table))
(assign-codes! ordered)

(write-string "message: ")
(write message)
(newline)
(write-string "code (symbol count length bits):")
(newline)
(for-each (lambda (row)
            (write-string "  ")
            (write (entry-symbol row))
            (write-string " ")
            (write (entry-count row))
            (write-string " ")
            (write (entry-bits row))
            (write-string " ")
            (write-string (code-string row))
            (newline)) ordered)

(define bits (encode chars ordered))
(write-string "encoded: ")
(write-string bits)
(newline)
(write-string "length: ")
(write (string-length bits))
(write-string " bits, against ")
(write (* 8 (length chars)))
(write-string " at 8 bits a character")
(newline)
(write-string "Kraft sum: ")
(write (apply + (map (lambda (row) (/ 1 (expt 2 (entry-bits row)))) ordered)))
(newline)
(define decoded (decode bits ordered))
(write-string "decoded: ")
(write decoded)
(newline)
(write-string (if (string=? decoded message) "round trip ok" "MISMATCH"))
(newline)
