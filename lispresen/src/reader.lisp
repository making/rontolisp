;;;; reader.lisp -- top-level forms of a deck with their line numbers, and diagnostics.
;;;;
;;;; A deck is read, never evaluated, and #. is refused, so reading cannot run code from
;;;; it either. Each top-level form is read on its own so that its start offset, hence its
;;;; line, is known for every diagnostic about it.

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
      (let ((*line* line) (*read-eval* nil) (next nil))
        (handler-case (multiple-value-bind (form end)
                          (read-from-string source t nil :start pos)
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
