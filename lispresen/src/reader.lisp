;;;; reader.lisp -- top-level forms of a deck with their line numbers, and diagnostics.
;;;;
;;;; A deck is read by lispresen's own reader, never evaluated: the syntax is the same on
;;;; every backend whatever its READ-FROM-STRING accepts, there is no #. to refuse, and a
;;;; symbol keeps the case it is written with (a formula tells N from n). Each top-level
;;;; form is read on its own so that its start offset, hence its line, is known for every
;;;; diagnostic about it.

(in-package :lispresen)

;;; One problem found in a deck. SEVERITY is :error or :warning.
(defstruct diagnostic severity line slide title message)

(defvar *diagnostics*
  nil
  "Diagnostics of the deck being rendered, newest first.")
(defvar *line* nil "Line of the top-level form being processed.")
(defvar *slide* nil "Number of the slide being processed.")
(defvar *slide-title* nil "Title of the slide being processed.")

(defun report (severity message)
  (push (make-diagnostic :severity severity
                         :line *line*
                         :slide *slide*
                         :title *slide-title*
                         :message message) *diagnostics*))

(defun format-diagnostic (file d)
  "One diagnostic as FILE:LINE: slide N \"TITLE\": SEVERITY: MESSAGE."
  (with-output-to-string (out)
    (write-string file out)
    (when (diagnostic-line d) (format out ":~d" (diagnostic-line d)))
    (write-string ": " out)
    (when (diagnostic-slide d)
      (format out "slide ~d" (diagnostic-slide d))
      (when (diagnostic-title d)
        (write-char #\Space out)
        (write-char (code-char 34) out)
        (write-string (diagnostic-title d) out)
        (write-char (code-char 34) out))
      (write-string ": " out))
    (write-string (string-downcase (symbol-name (diagnostic-severity d))) out)
    (write-string ": " out)
    (write-string (diagnostic-message d) out)))

(defun blank-code-p (code)
  (or (= code 32) (= code 9) (= code 10) (= code 13) (= code 12)))

(defun skip-blank (s pos)
  "The index of the first character at or after POS outside whitespace and comments."
  (let ((n (length s)))
    (loop
      (when (>= pos n) (return pos))
      (let ((c (char s pos)))
        (cond ((blank-code-p (char-code c)) (incf pos))
              ((char= c #\;)
               (loop while (and (< pos n) (char/= (char s pos) #\Newline))
                     do (incf pos)))
              ((and (char= c #\#) (< (1+ pos) n) (char= (char s (1+ pos)) #\|))
               (let ((end (search "|#" s :start2 (+ pos 2))))
                 (setf pos (if end (+ end 2) n))))
              (t (return pos)))))))

;;; --- the deck reader ------------------------------------------------------------
;;;
;;; Syntax: lists, a dotted tail (deck syntax only inside (cells ...), checked after the
;;; read), strings with \" and \\ (a backslash escapes the next character), characters
;;; (#\x, #\Space, #\Newline, #\Tab), 'x for (quote x), integers, ratios, decimals (read
;;; as floats, which every element refuses with a message), keywords, t and nil, and
;;; symbols, whose case is kept; |...| and \ escape characters in a symbol. Comments are ;
;;; and #| ... |#. Anything else after # (#., #+, #n=) and backquote are errors.

(defun delimiter-code-p (code)
  (or (blank-code-p code) (member code '(40 41 34 39 59 96 44))))

(defun digits-p (s start end)
  (and (< start end)
       (loop for i from start below end always (digit-char-p (char s i)))))

(defun number-token (s)
  "The number the token S spells, or nil: an integer (a trailing dot allowed), a ratio,
or a decimal, with or without an exponent, which is read as a float."
  (let* ((n (length s))
         (i (if (and (> n 0) (find (char s 0) "+-")) 1 0))
         (neg (and (> n 0) (char= (char s 0) #\-)))
         (dot (position #\. s :start i))
         (slash (position #\/ s :start i))
         (exp (position-if (lambda (c) (find c "eEsSfFdDlL")) s :start i)))
    (cond ((= i n) nil)
          ((and (digits-p s i n)) (let ((v (parse-integer s :start i))) (if neg (- v) v)))
          ((and (= (1- n) (or dot n)) (digits-p s i dot))
           (let ((v (parse-integer s :start i :end dot))) (if neg (- v) v)))
          ((and slash (digits-p s i slash) (digits-p s (1+ slash) n))
           (let ((d (parse-integer s :start (1+ slash))))
             (and (> d 0)
                  (let ((v (/ (parse-integer s :start i :end slash) d)))
                    (if neg (- v) v)))))
          ((and (let ((end (or exp n)))
                  (if dot
                      (and (< dot end) (digits-p s (1+ dot) end)
                           (or (= dot i) (digits-p s i dot)))
                      (digits-p s i end)))
                (or (null exp)
                    (let ((j (if (and (< (1+ exp) n) (find (char s (1+ exp)) "+-"))
                                 (+ exp 2)
                                 (1+ exp))))
                      (digits-p s j n)))
                (or dot exp))
           ;; The token is checked above, so every backend's reader parses it alike.
           (let ((*read-eval* nil)) (values (read-from-string s))))
          (t nil))))

(defun character-named (name)
  (cond ((= (length name) 1) (char name 0))
        ((string-equal name "Space") #\Space)
        ((string-equal name "Newline") #\Newline)
        ((string-equal name "Tab") #\Tab)
        ((string-equal name "Return") (code-char 13))
        (t (error "unknown character name"))))

(defun read-deck-token (s pos)
  "The datum of the token starting at POS and the index after it. Escapes (|...| and \\)
keep the characters they cover as written and make the token a symbol."
  (let ((n (length s)) (name (make-string-output-stream)) (escaped nil) (text nil))
    (loop while (< pos n)
          do
            (let ((c (char s pos)))
              (cond ((char= c #\|)
                     (setf escaped t)
                     (incf pos)
                     (loop
                       (when (>= pos n) (error "unterminated |"))
                       (let ((d (char s pos)))
                         (incf pos)
                         (cond ((char= d #\|) (return))
                               ((char= d #\\)
                                (when (>= pos n) (error "unterminated |"))
                                (write-char (char s pos) name)
                                (incf pos))
                               (t (write-char d name))))))
                    ((char= c #\\)
                     (setf escaped t)
                     (when (>= (1+ pos) n) (error "a backslash ends the deck"))
                     (write-char (char s (1+ pos)) name)
                     (incf pos 2))
                    ((delimiter-code-p (char-code c)) (return))
                    (t
                     (write-char c name)
                     (incf pos)))))
    (setf text (get-output-stream-string name))
    (values
     (cond (escaped (make-symbol text))
           ((string= text "") (error "an empty token"))
           ((string-equal text "t") t)
           ((string-equal text "nil") nil)
           ((char= (char text 0) #\:) (intern (subseq text 1) :keyword))
           ((number-token text))
           ((string= text ".") (error "a dot outside a dotted pair"))
           (t (make-symbol text)))
     pos)))

(defun read-deck-form (s pos)
  "The datum starting at or after POS in S and the index after it; an error when none
can be read there."
  (setf pos (skip-blank s pos))
  (let ((n (length s)))
    (when (>= pos n) (error "end of the deck"))
    (let ((c (char s pos)))
      (cond ((char= c #\()
             (let ((items nil) (tail nil))
               (incf pos)
               (loop
                 (setf pos (skip-blank s pos))
                 (when (>= pos n) (error "an unclosed list"))
                 (cond ((char= (char s pos) #\)) (incf pos) (return))
                       ((and (char= (char s pos) #\.) (< (1+ pos) n)
                             (delimiter-code-p (char-code (char s (1+ pos)))))
                        (when (null items) (error "a dot starts a list"))
                        (multiple-value-setq (tail pos) (read-deck-form s (1+ pos)))
                        (setf pos (skip-blank s pos))
                        (unless (and (< pos n) (char= (char s pos) #\)))
                          (error "a dotted tail holds one datum"))
                        (incf pos)
                        (return))
                       (t
                        (multiple-value-bind (x next) (read-deck-form s pos)
                          (push x items)
                          (setf pos next)))))
               (let ((list (nreverse items)))
                 (when tail (setf (cdr (last list)) tail))
                 (values list pos))))
            ((char= c #\)) (error "an unexpected )"))
            ((char= c #\')
             (multiple-value-bind (x next) (read-deck-form s (1+ pos))
               (values (list 'quote x) next)))
            ((char= c #\")
             (let ((out (make-string-output-stream)))
               (incf pos)
               (loop
                 (when (>= pos n) (error "an unterminated string"))
                 (let ((d (char s pos)))
                   (incf pos)
                   (cond ((char= d #\") (return))
                         ((char= d #\\)
                          (when (>= pos n) (error "an unterminated string"))
                          (write-char (char s pos) out)
                          (incf pos))
                         (t (write-char d out)))))
               (values (get-output-stream-string out) pos)))
            ((char= c #\#)
             (unless (and (< (1+ pos) n) (char= (char s (1+ pos)) #\\))
               (error "unsupported # syntax"))
             (when (>= (+ pos 2) n) (error "a character ends the deck"))
             ;; The character itself, then the constituents that name a longer one.
             (let ((end (+ pos 3)))
               (loop while (and (< end n) (not (delimiter-code-p (char-code (char s end)))))
                     do (incf end))
               (values (character-named (subseq s (+ pos 2) end)) end)))
            (t (read-deck-token s pos))))))

(defun cells-form-p (x)
  "True for a (cells ...) form, whose argument is a datum that may hold dotted pairs."
  (and (consp x) (car x) (symbolp (car x))
       (string-equal (symbol-name (car x)) "CELLS")))

(defun well-formed-p (form limit)
  "True when every list in FORM is a proper list, but for the dotted pairs of a (cells
...) datum, and FORM holds at most LIMIT conses; a form read from LIMIT characters holds
more only through shared or circular structure."
  (labels ((walk (x data)
             (let ((args (and (not data) (cells-form-p x))))
               (loop while (consp x)
                     do
                       (when (< (decf limit) 0) (return-from well-formed-p nil))
                       (when (consp (car x)) (walk (car x) (or data args)))
                       (setf x (cdr x)))
               (when (and x (not data)) (return-from well-formed-p nil)))))
    (walk form nil)
    t))

(defun read-forms (source)
  "The top-level forms of SOURCE as (FORM LINE) lists. A form that cannot be read is
reported and ends the read; a dotted, shared or circular one is reported and skipped."
  (let ((pos 0) (line 1) (counted 0) (forms nil) (n (length source)))
    (loop
      (setf pos (skip-blank source pos))
      (when (>= pos n) (return))
      (loop while (< counted pos)
            do (when (char= (char source counted) #\Newline) (incf line))
              (incf counted))
      (let ((*line* line) (next nil))
        (handler-case (multiple-value-bind (form end) (read-deck-form source pos)
                        (if (well-formed-p form (- end pos))
                            (push (list form line) forms)
                            (report :error
                                    "dotted lists outside (cells ...) and #n= labels are not deck syntax"))
                        (setf next end))
          (error ()
            (report
             :error
             "cannot read this form (unbalanced parentheses or bad syntax)")))
        (if next (setf pos next) (return))))
    (nreverse forms)))
