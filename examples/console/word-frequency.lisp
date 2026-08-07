;;;; Word frequency counter in rontolisp
;;;; Demonstrates hash tables, string manipulation, sorting, and
;;;; higher-order functions. Runs on all three backends.
;;;;
;;;; Run:
;;;;   rontolisp examples/console/word-frequency.lisp
;;;;   rontolisp examples/console/word-frequency.lisp -o WordFreq.class && java WordFreq
;;;;   rontolisp examples/console/word-frequency.lisp -o word-frequency.wasm && wasmtime run -W gc word-frequency.wasm

(defun take (n lst)
  "Return the first N elements of LST."
  (if (or (<= n 0) (null lst)) nil (cons (car lst) (take (1- n) (cdr lst)))))

(defun string-lessp (a b)
  "Lexicographic string comparison using char<."
  (let ((len-a (length a)) (len-b (length b)) (j 0) (result nil) (decided nil))
    ;; Compare character by character
    (while (and (not decided) (< j len-a) (< j len-b))
      (when (char< (char a j) (char b j))
        (setq result t)
        (setq decided t))
      (when (and (not decided) (char< (char b j) (char a j)))
        (setq result nil)
        (setq decided t))
      (setq j (1+ j)))
    ;; If all compared characters are equal, shorter string is less
    (when (not decided) (setq result (< len-a len-b)))
    result))

(defun word-frequency (text)
  "Count word occurrences in TEXT, returning a hash table of word -> count."
  (let ((freq (make-hash-table)) (len (length text)) (pos 0) (done nil))
    (while (and (not done) (< pos len))
      ;; Skip non-alpha characters
      (while (and (< pos len) (not (alpha-char-p (char text pos))))
        (setq pos (1+ pos)))
      (when (>= pos len) (setq done t))
      (when (not done)
        ;; Read word start
        (let ((start pos))
          (while (and (< pos len) (alpha-char-p (char text pos)))
            (setq pos (1+ pos)))
          (let ((word (string-downcase (subseq text start pos))))
            (setf (gethash word freq) (1+ (or (gethash word freq) 0)))))))
    freq))

(defun top-words (freq-table &optional (n 10))
  "Return the N most frequent words from FREQ-TABLE as a sorted list of (word . count) pairs."
  (let ((pairs nil))
    (maphash (lambda (word count) (push (cons word count) pairs)) freq-table)
    (setq pairs (sort pairs (lambda (a b) (> (cdr a) (cdr b)))))
    (take n pairs)))

(defparameter *sample-text*
  "To be or not to be that is the question
Whether 'tis nobler in the mind to suffer
The slings and arrows of outrageous fortune
Or to take arms against a sea of troubles
And by opposing end them")

(format t "Word frequency analysis:~%~%")
(let ((freq (word-frequency *sample-text*)))
  (format t "Total unique words: ~d~%" (hash-table-count freq))
  (format t "~%Top 10 words:~%")
  (let ((rank 1))
    (dolist (pair (top-words freq 10))
      (format t "  ~2d. ~-12s ~d~%" rank (car pair) (cdr pair))
      (setq rank (1+ rank))))
  (format t "~%Words appearing exactly once:~%")
  (let ((singletons nil))
    (maphash (lambda (word count) (when (= count 1) (push word singletons)))
             freq)
    (format t "  ~a~%" (sort (copy-list singletons) #'string-lessp))))
