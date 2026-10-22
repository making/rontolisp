;;;; highlight.lisp -- one tokenizer for Lisp, Scheme and Clojure code.
;;;;
;;;; Classes: hl-c comments, hl-s strings and characters, hl-k keywords (:name), hl-d the
;;;; head of a form that defines or binds (def..., let, lambda, if, ...), hl-n numbers and
;;;; constants (nil, t, true, false, #t, #f). [], {} and #{} are delimiters like (). The
;;;; player's editor re-highlights with the same rules (HIGHLIGHT in player.lisp), so an
;;;; edited block looks like the deck's.
;;;;
;;;; A console transcript is highlighted by line: the $ of a command line as hl-k, every
;;;; other line, the output, as hl-c.

(in-package :lispresen)

(defparameter *form-heads*
  '("lambda" "fn" "let" "let*" "letrec" "letfn" "flet" "labels" "if" "when"
    "unless" "cond" "case" "and" "or" "loop" "do" "dolist" "dotimes" "progn"
    "begin" "quote" "setf" "setq" "set!" "recur" "try" "catch" "handler-case"
    "unwind-protect" "block" "return-from" "multiple-value-bind"
    "destructuring-bind" "ns" "in-package" "require"))

(defparameter *constants*
  '("nil" "t" "true" "false" "#t" "#f" "#true" "#false"))

(defun delimiter-p (c)
  (or (blank-code-p (char-code c)) (find c "()[]{}\";'`,")))

(defun number-token-p (tok)
  (let ((c (char tok 0)))
    (or (digit-char-p c)
        (and (> (length tok) 1) (find c "+-.") (digit-char-p (char tok 1))))))

(defun token-end (text j)
  (let ((n (length text)))
    (loop while (and (< j n) (not (delimiter-p (char text j)))) do (incf j))
    j))

(defun token-class (text i j)
  (let ((tok (string-downcase (subseq text i j))))
    (cond ((char= (char tok 0) #\:) "k")
          ((member tok *constants* :test #'string=) "n")
          ((number-token-p tok) "n")
          ((and (> i 0) (char= (char text (1- i)) #\()
                (or (and (> (length tok) 3) (string= tok "def" :end1 3))
                    (member tok *form-heads* :test #'string=)))
           "d"))))

(defun write-highlighted (text out)
  "TEXT as HTML with a span around each highlighted token."
  (let ((n (length text)) (i 0))
    (flet ((emit (class end)
             (when class (format out "<span class=\"hl-~a\">" class))
             (write-escaped (subseq text i end) out)
             (when class (write-string "</span>" out))
             (setf i end)))
      (loop while (< i n)
            do
              (let ((c (char text i))
                    (next (and (< (1+ i) n) (char text (1+ i)))))
                (cond
                 ((char= c #\;)
                  (emit "c" (or (position #\Newline text :start i) n)))
                 ((and (char= c #\#) (eql next #\|))
                  (let ((end (search "|#" text :start2 (+ i 2))))
                    (emit "c" (if end (+ end 2) n))))
                 ((char= c #\")
                  (let ((j (1+ i)))
                    (loop while (< j n)
                          do
                            (let ((d (char text j)))
                              (cond ((char= d #\\) (incf j 2))
                                    ((char= d #\")
                                     (incf j)
                                     (return))
                                    (t (incf j)))))
                    (emit "s" (min j n))))
                 ((and (char= c #\#) (eql next #\\))
                  (emit "s" (token-end text (min n (+ i 3)))))
                 ((char= c #\\) (emit "s" (token-end text (min n (+ i 2)))))
                 ((delimiter-p c) (emit nil (1+ i)))
                 (t (let ((j (token-end text i)))
                      (emit (token-class text i j) j)))))))))

(defun write-console (lines out)
  "The lines of a console transcript as HTML: the prompt of a command line marked, the
output lines muted."
  (loop for (line . more) on lines
        do
          (cond ((prompt-line-p line)
                 (write-string "<span class=\"hl-k\">$</span>" out)
                 (write-escaped (subseq line 1) out))
                ((string= line ""))
                (t
                 (write-string "<span class=\"hl-c\">" out)
                 (write-escaped line out)
                 (write-string "</span>" out))) (when more (terpri out))))
