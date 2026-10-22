;;;; model.lisp -- the deck model and the parser from read forms to it.
;;;;
;;;; Tags are matched by SYMBOL-NAME, so the package the runtime reader interns into does
;;;; not matter. Options are keyword/value pairs at the start of a form (after the source
;;;; of an IMAGE, the URL of a LINK).
;;;;
;;;; Inline content is a list of nodes: a string, (:b nodes), (:i nodes), (:mark nodes),
;;;; (:accent nodes), (:tt "string"), (:link "url" nodes) or (:say callout nodes).
;;;;
;;;; A deck can be rendered from an untrusted source, so nothing here evaluates a form.

(in-package :lispresen)

(defstruct deck
  line
  title
  author
  date
  theme
  aspect
  runtime
  page-numbers
  callouts
  kicker
  agenda
  logo
  url
  slides)

;;; KIND is :title (generated from the deck header), :section or :slide. LAYOUT is the
;;; requested layout name or nil; FIT is the layout result (layout.lisp).
;;; CALLOUTS lists the slide's callouts in source order. KICKER is the label above the
;;; title, :deck until the deck's :kicker decides it. META lists the (TEXT RUN) of each
;;; line under the title of the title slide: the author, the date and the URL. STEPS
;;; lists what a page advance reveals, in source order (stepped items, code blocks with
;;; :steps, callouts), and STEP-COUNT how many advances that takes (NUMBER-STEPS).
(defstruct slide
  number
  line
  kind
  title
  layout
  class
  blocks
  notes
  title-run
  fit
  callouts
  kicker
  kicker-run
  meta
  steps
  (step-count 0))

;;; LEAD is true for a (lead ...) paragraph, set in the muted color.
(defstruct para inlines run lead)
;;; AGENDA is true for the list of an agenda slide that highlights its CURRENT item.
(defstruct bullets ordered items agenda)
;;; STEP is the page advance that reveals the item of a (ul :step t ...), or nil.
(defstruct item inlines sublist run current step)
;;; RUN is the deck's :run option (true unless :run nil); RUNNABLE is set when the slide is
;;; fitted, from the language, RUN and the deck's runtime.
;;; STEPS lists, for each step of :steps, the 0-based indices of the lines it brings
;;; forward; STEP-NUMBERS the page advance of each.
(defstruct code-block
  lang
  lines
  (run t)
  runnable
  timeout
  fresh
  stdin
  steps
  step-numbers)
;;; STRIP is the width kept free on the right for the bubble of a callout on the image.
(defstruct figure src alt caption caption-run strip)
(defstruct table-block
  header
  rows
  align
  caption
  row-header
  caption-run
  size
  widths
  aligns)
(defstruct cell inlines colspan run)
(defstruct quotation inlines cite run cite-run)
(defstruct column blocks)
;;; Consecutive columns of a slide, side by side in one row; WIDTHS is set when the slide
;;; is fitted.
(defstruct column-row columns widths)

;;; A comment in a speech bubble pointing at its anchor. TARGET is the block it points
;;; at, nil for an inline anchor (the run holding it lists it among its ANCHORS); AT is
;;; the text that names the anchor inside a code block; STEP is the :step option, :deck
;;; when the deck's :callouts decides whether a page advance shows the bubble, and
;;; STEP-NUMBER that advance, nil for a bubble shown with the slide. The rest
;;; is set when the slide
;;; is fitted (layout.lisp): RUN is the comment's text run, FRAGMENTS the anchor's
;;; rectangles (one per line), and SIZE, SIDE, X, Y, WIDTH, HEIGHT and TAIL place the
;;; bubble.
(defstruct callout
  id
  text
  at
  step
  step-number
  target
  run
  fragments
  size
  side
  x
  y
  width
  height
  tail
  overflow)

(defvar *callouts* nil "The callouts of the slide being parsed, newest first.")
(defvar *steps* nil "The STEPS of the slide being parsed, newest first.")

;;; --- form helpers ------------------------------------------------------------

(defun form-tag (form)
  "The upper-case name of FORM's head symbol, or nil."
  (and (consp form) (car form) (symbolp (car form))
       (string-upcase (symbol-name (car form)))))

(defun option-name (key)
  (concatenate 'string ":" (string-downcase (symbol-name key))))

(defun split-options (args allowed context)
  "The leading keyword/value pairs of ARGS as ((NAME VALUE) ...) keyed by upper-case
name, and the remaining arguments. An option not in ALLOWED is reported."
  (let ((opts nil))
    (loop while (and args (keywordp (car args)))
          do
            (let ((name (symbol-name (car args))))
              (unless (member name allowed :test #'string=)
                (report :warning (format nil "unknown option ~a for ~a"
                                         (option-name (car args)) context)))
              (when (null (cdr args))
                (report :error (format nil "option ~a for ~a has no value"
                                       (option-name (car args)) context)))
              (push (list name (cadr args)) opts)
              (setf args (cddr args))))
    (values (nreverse opts) args)))

(defun opt (opts name &optional default)
  (let ((entry (assoc name opts :test #'string=)))
    (if entry (second entry) default)))

(defun symbol-value-name (x)
  "The upper-case name of a symbol option value such as :light, or nil."
  (and x (symbolp x) (string-upcase (symbol-name x))))

(defun raw-string-option (opts name context)
  "The string value of option NAME as written, or nil."
  (let ((v (opt opts name)))
    (cond ((null v) nil)
          ((stringp v) v)
          (t
           (report :error (format nil "~a of ~a must be a string"
                           (concatenate 'string ":" (string-downcase name))
                           context))
           nil))))

(defun string-option (opts name context)
  "The string value of option NAME as text: whitespace collapsed, or nil."
  (let ((v (raw-string-option opts name context))) (and v (text-leaf v))))

;;; --- text --------------------------------------------------------------------

(defun normalize-text (s)
  "S with every whitespace run collapsed the way HTML renders it: dropped when it holds
a line break between two wide characters, one space otherwise."
  (with-output-to-string (out)
    (let ((n (length s)) (i 0))
      (loop while (< i n)
            do
              (let ((c (char s i)))
                (if (blank-code-p (char-code c))
                    (let ((j i) (newline nil))
                      (loop while
                              (and (< j n)
                                   (blank-code-p (char-code (char s j))))
                            do
                              (when (char= (char s j) #\Newline)
                                (setf newline t)) (incf j))
                      (unless (and newline (> i 0) (< j n)
                                   (wide-code-p (char-code (char s (1- i))))
                                   (wide-code-p (char-code (char s j))))
                        (write-char #\Space out))
                      (setf i j))
                    (progn
                      (write-char c out)
                      (incf i))))))))

(defun check-full-width-alnum (s)
  (when (find-if (lambda (c)
                   (let ((code (char-code c)))
                     (or (<= #xFF10 code #xFF19) (<= #xFF21 code #xFF3A)
                         (<= #xFF41 code #xFF5A)))) s)
    (report
     :warning (format nil "full-width alphanumerics in ~s; use half-width" s))))

(defun text-leaf (s)
  (check-full-width-alnum s)
  (normalize-text s))

;;; --- inline content ----------------------------------------------------------

(defun inline-tag-p (tag)
  (member tag '("B" "I" "MARK" "ACCENT" "TT" "LINK" "SAY") :test #'equal))

(defun say-content (form)
  "The arguments of (say \"comment\" [:at \"text\"] content...) after the comment and
the options."
  (let ((args (cddr form)))
    (loop while (and args (keywordp (car args))) do (setf args (cddr args)))
    args))

(defun parse-say (form)
  "A new callout for (say \"comment\" [:at \"text\"] [:step t] content...), registered
in *CALLOUTS*; nil after reporting a malformed one."
  (let ((text (cadr form)))
    (multiple-value-bind (opts content)
        (split-options (cddr form) '("AT" "STEP") "say")
      (cond ((not (stringp text))
             (report :error "(say ...) takes the comment string first")
             nil)
            ((null content)
             (report :error "(say ...) needs the content it points at")
             nil)
            (t
             (let ((c
                    (make-callout :text (text-leaf text)
                                  :at (raw-string-option opts "AT" "say")
                                  :step (if (assoc "STEP" opts :test #'string=)
                                            (and (opt opts "STEP") t)
                                            :deck))))
               (when (equal (callout-at c) "")
                 (report :error ":at of say must not be empty")
                 (setf (callout-at c) nil))
               (push c *callouts*)
               c))))))

(defun parse-inline (x)
  "The inline nodes X stands for; an unknown form is reported and dropped."
  (cond ((stringp x) (list (text-leaf x)))
        ((numberp x) (list (princ-to-string x)))
        ((consp x)
         (let ((tag (form-tag x)))
           (cond ((equal tag "B") (list (list :b (parse-inlines (cdr x)))))
            ((equal tag "I") (list (list :i (parse-inlines (cdr x)))))
            ((equal tag "MARK") (list (list :mark (parse-inlines (cdr x)))))
            ((equal tag "ACCENT") (list (list :accent (parse-inlines (cdr x)))))
            ((equal tag "TT")
             (if (every #'stringp (cdr x))
                 (list
                  (list
                   :tt (normalize-text (apply #'concatenate 'string (cdr x)))))
                 (progn
                   (report :error "(tt ...) takes strings only")
                   nil)))
            ((equal tag "SAY")
             (let ((c (parse-say x)) (nodes (parse-inlines (say-content x))))
               (cond ((null c) nodes)
                     (t
                      ;; A callout's step follows the steps of what it wraps.
                      (push c *steps*)
                      (when (callout-at c)
                        (report
                         :error ":at belongs to a callout around a code block")
                        (setf (callout-at c) nil))
                      (list (list :say c nodes))))))
            ((equal tag "LINK")
             (if (stringp (cadr x))
                 (list
                  (list :link (cadr x)
                        (or (parse-inlines (cddr x)) (list (cadr x)))))
                 (progn
                   (report :error "(link ...) takes a URL string first")
                   nil)))
            (t
             (report :error (format nil "unknown inline element ~a"
                                    (if tag (string-downcase tag) "(a list)")))
             nil))))
        (t
         (report
          :error (format nil "unexpected ~a in text" (prin1-to-string x)))
         nil)))

(defun parse-inlines (args)
  (let ((nodes nil))
    (dolist (a args) (dolist (n (parse-inline a)) (push n nodes)))
    (nreverse nodes)))

;;; --- blocks ------------------------------------------------------------------

(defun list-tag-p (form) (member (form-tag form) '("UL" "OL") :test #'equal))

(defun parse-li (args)
  "An item from the arguments of (li ...): inline content, then at most one list."
  (let ((inlines nil) (sublist nil))
    (dolist (a args)
      (cond ((list-tag-p a)
             (when sublist
               (report :error "an item holds one nested list at most"))
             (setf sublist (parse-list a)))
            (t (dolist (n (parse-inline a)) (push n inlines)))))
    (make-item :inlines (nreverse inlines) :sublist sublist)))

(defun parse-list (form)
  "A bullets from (ul [:step t] ...) or (ol ...). A list directly inside a list nests under
the item before it. Under :step each item is a step of its own, its nested list
revealed with it."
  (multiple-value-bind (opts body)
      (split-options (cdr form) '("STEP") (string-downcase (form-tag form)))
    (let ((items nil) (step (opt opts "STEP")))
      (dolist (x body)
        (if (list-tag-p x)
            (cond
             ((null items) (report :error "a nested list must follow an item"))
             ((item-sublist (car items))
              (report :error "an item holds one nested list at most"))
             (t (setf (item-sublist (car items)) (parse-list x))))
            ;; The item's place among the steps is taken before its content is
            ;; parsed, so a step callout inside it comes after it.
            (let ((place (and step (list :item))))
              (when place (push place *steps*))
              (let ((it
                     (if (equal (form-tag x) "LI")
                         (parse-li (cdr x))
                         (make-item :inlines (parse-inline x)))))
                (when place (setf (cdr place) (list it)))
                (push it items)))))
      (make-bullets :ordered (equal (form-tag form) "OL")
                    :items (nreverse items)))))

(defun split-lines (s)
  (let ((lines nil) (start 0))
    (loop
      (let ((nl (position #\Newline s :start start)))
        (push (subseq s start nl) lines)
        (if nl (setf start (1+ nl)) (return))))
    (nreverse lines)))

(defun expand-tabs (s)
  (if (find #\Tab s)
      (with-output-to-string (out)
        (let ((col 0))
          (loop for c across s
                do
                  (if (char= c #\Tab)
                      (loop repeat (- 4 (mod col 4))
                            do (write-char #\Space out) (incf col))
                      (progn
                        (write-char c out)
                        (incf col))))))
      s))

(defun dedent (text)
  "TEXT as lines with the leading blank line, trailing blank lines, trailing whitespace
and the common indentation removed."
  (let* ((lines
          (mapcar
           (lambda (l) (string-right-trim '(#\Space #\Return) (expand-tabs l)))
           (split-lines text)))
         (lines (if (and lines (string= (car lines) "")) (cdr lines) lines))
         (lines
          (reverse (member-if (lambda (l) (string/= l "")) (reverse lines))))
         (indent nil))
    (dolist (l lines)
      (when (string/= l "")
        (let ((k (or (position-if (lambda (c) (char/= c #\Space)) l) 0)))
          (setf indent (if indent (min indent k) k)))))
    (mapcar (lambda (l) (if (string= l "") l (subseq l (or indent 0)))) lines)))

(defun positive-option (opts name default)
  (let ((v (opt opts name default)))
    (if (and (integerp v) (> v 0))
        v
        (progn
          (report :error (format nil "~a of code must be a positive integer"
                          (concatenate 'string ":" (string-downcase name))))
          default))))

(defun parse-code (form)
  (multiple-value-bind (opts body)
      (split-options (cdr form)
                     '("LANG" "FILE" "LINES" "RUN" "TIMEOUT" "FRESH" "STDIN"
                       "STEPS")
                     "code")
    (let ((file (raw-string-option opts "FILE" "code")) (range (opt opts "LINES")))
      (cond ((notevery #'stringp body)
             (report :error "(code ...) takes strings only")
             (setf file nil body nil))
            ((and file body)
             (report :error "(code :file ...) takes no text")
             (setf body nil)))
      (when range
        (cond ((null file)
               (report :error ":lines belongs to (code :file ...)")
               (setf range nil))
              ((not (and (listp range) (= (length range) 2)
                         (every #'integerp range)
                         (<= 1 (first range) (second range))))
               (report :error
                       ":lines must be (FIRST LAST), line numbers with FIRST <= LAST")
               (setf range nil))))
      (let ((text
             (if file
                 (code-file-text file range)
                 (and body (apply #'concatenate 'string body)))))
        (and text
             (let* ((lines (dedent text))
                    (b
                     (make-code-block :lang (or (string-option opts "LANG" "code")
                                                (and file (code-file-lang file)))
                                      :lines lines
                                      :run (opt opts "RUN" t)
                                      :timeout (positive-option opts "TIMEOUT" 10)
                                      :fresh (opt opts "FRESH")
                                      :stdin (raw-string-option opts "STDIN" "code")
                                      :steps (code-steps (opt opts "STEPS") lines))))
               (when (code-block-steps b) (push b *steps*))
               b))))))

(defun code-steps (steps lines)
  "The line indices each step of a code block's :steps brings forward. A step is a
string, the first line holding it; (FIRST LAST), lines of the block from 1; or a list of
those. Nil after reporting a malformed one."
  (labels ((fail (message)
             (report :error message)
             (return-from code-steps nil))
           (range-p (x)
             (and (consp x) (= (length x) 2) (every #'integerp x)))
           (part (x)
             (cond ((stringp x)
                    (let ((i (position-if (lambda (l) (search x l)) lines)))
                      (if (and i (string/= x ""))
                          (list i)
                          (fail (format nil
                                        "the :steps text ~s is not on a line of the code"
                                        x)))))
                   ((range-p x)
                    (destructuring-bind (first last) x
                      (unless (<= 1 first last (length lines))
                        (fail (format nil
                                      ":steps lines ~d-~d are not within the ~d line~:p of the code"
                                      first last (length lines))))
                      (loop for i from (1- first) below last collect i)))
                   (t
                    (fail
                     ":steps of code must list steps: a string, (FIRST LAST) or a list of them")))))
    (unless (listp steps)
      (fail ":steps of code must list steps: a string, (FIRST LAST) or a list of them"))
    (mapcar (lambda (step)
              (if (and (consp step) (not (range-p step)))
                  (sort (remove-duplicates (mapcan #'part step)) #'<)
                  (part step)))
            steps)))

(defun parse-image (form)
  (let ((src (cadr form)))
    (if (not (stringp src))
        (progn
          (report :error "(image ...) takes the image path first")
          nil)
        (multiple-value-bind (opts rest)
            (split-options (cddr form) '("ALT" "CAPTION") "image")
          (when rest
            (report :error "(image ...) takes only a path and options"))
          (let ((alt (string-option opts "ALT" "image")))
            (unless alt
              (report :warning (format nil "image ~a has no :alt" src)))
            (make-figure :src src
                         :alt alt
                         :caption (string-option opts "CAPTION" "image")))))))

(defun parse-cell (x)
  (if (equal (form-tag x) "CELL")
      (multiple-value-bind (opts body)
          (split-options (cdr x) '("COLSPAN") "cell")
        (let ((span (opt opts "COLSPAN" 1)))
          (unless (and (integerp span) (> span 0))
            (report :error ":colspan must be a positive integer")
            (setf span 1))
          (make-cell :inlines (parse-inlines body) :colspan span)))
      (make-cell :inlines (parse-inline x) :colspan 1)))

(defun parse-row (row)
  (if (and (consp row) (not (form-tag row)))
      (mapcar #'parse-cell row)
      (progn
        (report :error "a table row must be a list of cells")
        nil)))

(defun row-span (row) (reduce #'+ (mapcar #'cell-colspan row)))

(defun parse-table (form)
  (multiple-value-bind (opts body) (split-options (cdr form)
                                    '("HEADER" "ALIGN" "CAPTION" "ROW-HEADER")
                                    "table")
    (let* ((header-form (opt opts "HEADER"))
           (header (and header-form (parse-row header-form)))
           (rows (remove nil (mapcar #'parse-row body)))
           (align (opt opts "ALIGN"))
           (width
            (if header (row-span header) (and rows (row-span (car rows))))))
      (when (null rows) (report :error "a table needs at least one row"))
      (dolist (row rows)
        (unless (= (row-span row) width)
          (report :error (format nil
                          "a table row has ~d cells where the table has ~d"
                          (row-span row) width))))
      (when (and align
                 (not
                  (and (listp align) (= (length align) width)
                       (every (lambda (a)
                                (member (symbol-value-name a)
                                        '("LEFT" "RIGHT" "CENTER")
                                        :test #'equal)) align))))
        (report
         :error ":align must list :left, :right or :center for every column")
        (setf align nil))
      (when (and width (> width 5))
        (report :warning (format nil
                                 "a table with ~d columns is hard to read; keep it to 5"
                                 width)))
      (when (> (length rows) 7)
        (report :warning (format nil
                          "a table with ~d rows is hard to read; keep it to 7"
                          (length rows))))
      (and rows
           (make-table-block :header header
                             :rows rows
                             :align (mapcar #'symbol-value-name align)
                             :caption (string-option opts "CAPTION" "table")
                             :row-header (opt opts "ROW-HEADER"))))))

(defun parse-quote (form)
  (multiple-value-bind (opts body) (split-options (cdr form) '("CITE") "quote")
    (let ((inlines (parse-inlines body)))
      (if inlines
          (make-quotation :inlines inlines
                          :cite (string-option opts "CITE" "quote"))
          (progn
            (report :error "(quote ...) needs text")
            nil)))))

(defun parse-column (form in-column)
  (if in-column
      (progn
        (report :error "a column cannot hold another column")
        nil)
      (make-column :blocks (remove nil
                                   (mapcar (lambda (x) (parse-block x t))
                                           (cdr form))))))

(defparameter *anchor-blocks*
  '("P" "LEAD" "UL" "OL" "CODE" "IMAGE" "TABLE" "QUOTE")
  "The blocks a callout can point at as a whole.")

(defun parse-block-say (form in-column)
  "The block (say ...) stands for in block position: the one block it wraps, which its
callout points at, or else a paragraph holding it as inline content."
  (let ((content (say-content form)))
    (if (not
         (and (= (length content) 1)
              (member (form-tag (car content)) *anchor-blocks* :test #'equal)))
        (make-para :inlines (parse-inline form))
        (let* ((c (parse-say form)) (b (parse-block (car content) in-column)))
          (when c
            (cond ((null b) (setf *callouts* (remove c *callouts*)))
                  ((and (callout-at c) (not (code-block-p b)))
                   (report
                    :error ":at belongs to a callout around a code block")
                   (setf (callout-at c) nil))
                  ((and (callout-at c)
                        (notany (lambda (l) (search (callout-at c) l))
                                (code-block-lines b)))
                   (report :error (format nil
                                          "the :at text ~s is not on a line of the code"
                                          (callout-at c)))
                   (setf (callout-at c) nil)))
            (when b
              (setf (callout-target c) b)
              (push c *steps*)))
          b))))

(defun parse-block (x &optional in-column)
  "The block X stands for, or nil after reporting. IN-COLUMN is true inside a column."
  (cond ((stringp x) (make-para :inlines (list (text-leaf x))))
        ((consp x)
         (let ((tag (form-tag x)))
           (cond ((equal tag "P") (make-para :inlines (parse-inlines (cdr x))))
                 ((equal tag "LEAD")
                  (make-para :inlines (parse-inlines (cdr x)) :lead t))
                 ((equal tag "SAY") (parse-block-say x in-column))
                 ((list-tag-p x) (parse-list x))
                 ((equal tag "CODE") (parse-code x))
                 ((equal tag "IMAGE") (parse-image x))
                 ((equal tag "TABLE") (parse-table x))
                 ((equal tag "QUOTE") (parse-quote x))
                 ((equal tag "COLUMN") (parse-column x in-column))
                 ((inline-tag-p tag) (make-para :inlines (parse-inline x)))
                 ((equal tag "LI")
                  (report :error "(li ...) belongs in (ul ...) or (ol ...)")
                  nil)
                 (t
                  (report :error (format nil "unknown element ~a"
                                  (if tag (string-downcase tag) "(a list)")))
                  nil))))
        (t
         (report
          :error (format nil "unexpected ~a on a slide" (prin1-to-string x)))
         nil)))

;;; --- slides and the deck -----------------------------------------------------

(defun region-blocks (blocks)
  "BLOCKS with every column row replaced by the blocks of its columns."
  (mapcan (lambda (b)
            (if (column-row-p b)
                (mapcan (lambda (col) (copy-list (column-blocks col)))
                        (column-row-columns b))
                (list b))) blocks))

(defun group-columns (blocks)
  "BLOCKS with each run of consecutive columns gathered into one column row."
  (let ((out nil))
    (dolist (b blocks (nreverse out))
      (cond ((not (column-p b)) (push b out))
            ((column-row-p (car out))
             (setf (column-row-columns (car out))
                   (append (column-row-columns (car out)) (list b))))
            (t (push (make-column-row :columns (list b)) out))))))

(defun parse-slide (form number line)
  (multiple-value-bind (opts body)
      (split-options (cdr form) '("TITLE" "KICKER" "LAYOUT" "CLASS") "slide")
    (let* ((title (string-option opts "TITLE" "slide"))
           (*slide-title* title)
           (*callouts* nil)
           (*steps* nil)
           (blocks nil)
           (notes nil))
      (dolist (x body)
        (if (equal (form-tag x) "NOTES")
            (if (every #'stringp (cdr x))
                (push (apply #'concatenate 'string (cdr x)) notes)
                (report :error "(notes ...) takes strings only"))
            (let ((b (parse-block x))) (when b (push b blocks)))))
      (let ((i 0))
        (dolist (c (reverse *callouts*))
          (setf (callout-id c) (format nil "s~dc~d" number (incf i)))))
      (make-slide :number number
       :line line
       :kind :slide
       :title title
       :kicker (if (assoc "KICKER" opts :test #'string=)
                   (string-option opts "KICKER" "slide")
                   :deck)
       :callouts (reverse *callouts*)
       :steps (reverse *steps*)
       :layout (let ((l (opt opts "LAYOUT")))
                 (and l (string-downcase (symbol-value-name l))))
       :class (opt opts "CLASS")
       :blocks (group-columns (nreverse blocks))
       :notes (and notes (format nil "~{~a~^~%~}" (nreverse notes)))))))

(defun number-steps (slide callouts)
  "Numbers the page advances of SLIDE in source order: one per stepped item, one per step
of a code block's :steps, one per step callout (by its :step, or by CALLOUTS, the deck's
:callouts, when it has none)."
  (let ((n 0))
    (dolist (s (slide-steps slide))
      (cond ((consp s) (setf (item-step (second s)) (incf n)))
            ((code-block-p s)
             (setf (code-block-step-numbers s)
                   (loop repeat (length (code-block-steps s)) collect (incf n))))
            ((if (eq (callout-step s) :deck)
                 (eq callouts :step)
                 (callout-step s))
             (setf (callout-step-number s) (incf n)))))
    (setf (slide-step-count slide) n)))

(defun parse-section (form number line)
  (let ((title (cadr form)))
    (unless (and (stringp title) (null (cddr form)))
      (report :error "(section ...) takes one title string"))
    (make-slide :number number
                :line line
                :kind :section
                :title (and (stringp title) (text-leaf title)))))

(defun parse-runtime (x)
  "The runtime option as :hosted, :none or a URL string; nil when absent."
  (cond ((null x) nil)
   ((stringp x) (if (string= x "") :hosted x))
   ((equal (symbol-value-name x) "HOSTED") :hosted)
   ((equal (symbol-value-name x) "NONE") :none)
   (t
    (report :error ":runtime must be :hosted, :none or a URL string")
    nil)))

(defun parse-deck-header (form)
  (multiple-value-bind (opts rest) (split-options (cdr form)
                                                  '("TITLE" "AUTHOR" "DATE"
                                                    "THEME" "ASPECT" "RUNTIME"
                                                    "PAGE-NUMBERS" "CALLOUTS"
                                                    "KICKER" "AGENDA" "LOGO"
                                                    "URL") "deck")
    (when rest (report :error "(deck ...) takes options only"))
    (make-deck :title (string-option opts "TITLE" "deck")
               :author (string-option opts "AUTHOR" "deck")
               :date (string-option opts "DATE" "deck")
               :theme (opt opts "THEME")
               :aspect (opt opts "ASPECT")
               :runtime (parse-runtime (opt opts "RUNTIME"))
               :page-numbers (opt opts "PAGE-NUMBERS")
               :callouts (parse-callouts-option (opt opts "CALLOUTS"))
               :kicker (parse-kicker-option (opt opts "KICKER"))
               :logo (let ((l (opt opts "LOGO")))
                       (cond ((null l) nil)
                        ((stringp l) (parse-image (list 'image l)))
                        ((equal (form-tag l) "IMAGE") (parse-image l))
                        (t
                         (report
                          :error
                          ":logo of deck must be an image path or (image ...)")
                         nil)))
               :url (string-option opts "URL" "deck")
               :agenda (let ((a (opt opts "AGENDA")))
                         (cond ((or (null a) (eq a t) (stringp a)) a)
                               (t
                                (report
                                 :error
                                 ":agenda of deck must be t or a title string")
                                nil))))))

(defun parse-kicker-option (x)
  "The deck's :kicker: :section (each slide's kicker is its section's title) or nil."
  (cond ((null x) nil)
        ((equal (symbol-value-name x) "SECTION") :section)
        (t
         (report :error ":kicker of deck must be :section")
         nil)))

(defun parse-callouts-option (x)
  "The deck's :callouts as :step (a page advance shows each bubble) or :shown."
  (cond ((null x) :shown)
        ((equal (symbol-value-name x) "STEP") :step)
        ((equal (symbol-value-name x) "SHOWN") :shown)
        (t
         (report :error ":callouts must be :shown or :step")
         :shown)))

(defparameter *agenda-title*
  "目次"
  "The title of the agenda slides of (deck :agenda t).")

(defun agenda-list (titles current)
  "The list of the section TITLES on an agenda slide, the CURRENTth highlighted when it
is not nil."
  (make-bullets :ordered t
                :agenda (and current t)
                :items (loop for title in titles
                             for i from 0
                             collect
                               (make-item :inlines (list title)
                                          :current (eql i current)))))

(defun add-agendas (deck agenda sections)
  "Fills the AGENDA slide with the deck's SECTIONS, and turns each section slide into the
same list with its own section highlighted."
  (let* ((title
          (if (stringp (deck-agenda deck)) (deck-agenda deck) *agenda-title*))
         (titles (mapcar #'slide-title sections)))
    (setf (slide-title agenda) title (slide-blocks agenda)
          (list (agenda-list titles nil)))
    (loop for s in sections
          for i from 0
          do
            (setf (slide-kind s)
                  :slide (slide-title s) title (slide-blocks s)
                  (list (agenda-list titles i))))))

(defun parse-deck (forms)
  "The deck model of FORMS, the (FORM LINE) lists READ-FORMS answers. Template uses are
expanded first, so the sections an agenda lists are known before any slide is parsed."
  (let ((deck nil)
        (slides nil)
        (number 0)
        (section nil)
        (expanded nil)
        (agenda nil))
    (when (and forms (equal (form-tag (car (car forms))) "DECK"))
      (let ((*line* (second (car forms))))
        (setf deck (parse-deck-header (car (car forms))))
        (setf (deck-line deck) *line*))
      (setf forms (cdr forms)))
    (unless deck (setf deck (make-deck)))
    (when (and (or (deck-logo deck) (deck-url deck)) (null (deck-title deck)))
      (let ((*line* (deck-line deck)))
        (report
         :warning
         ":logo and :url are shown on the title slide, which needs a :title")))
    (when (deck-title deck)
      (push (make-slide :number (incf number)
                        :line 1
                        :kind :title
                        :title (deck-title deck)) slides))
    (let ((*templates* nil)
          (*expansion-budget* *expansion-limit*)
          (count number))
      (dolist (entry forms)
        (let ((*line* (second entry)) (*slide* nil) (*slide-title* nil))
          (if (equal (form-tag (first entry)) "DEFINE")
              (define-template (first entry))
              (dolist (form
                       (let ((*slide* (1+ count)))
                         (expand-top-level (first entry))))
                (push (list form *line*) expanded)
                (when (member (form-tag form) '("SLIDE" "SECTION")
                              :test #'equal)
                  (incf count)))))))
    (setf expanded (nreverse expanded))
    (when (deck-agenda deck)
      (if (some (lambda (e) (equal (form-tag (first e)) "SECTION")) expanded)
          (progn
            ;; The expansion counted the slides without the agenda slide placed here.
            (dolist (d *diagnostics*)
              (when (and (diagnostic-slide d) (> (diagnostic-slide d) number))
                (incf (diagnostic-slide d))))
            (push (setf agenda
                        (make-slide :number (incf number)
                                    :line (deck-line deck)
                                    :kind :slide)) slides))
          (let ((*line* (deck-line deck)))
            (report
             :warning ":agenda lists the sections, and this deck has none"))))
    (loop for (form line) in expanded
          do
            (let ((tag (form-tag form))
                  (*line* line)
                  (*slide* (1+ number))
                  (*slide-title* nil))
              (cond ((equal tag "SLIDE")
                     (let ((slide (parse-slide form (incf number) line)))
                       (number-steps slide (deck-callouts deck))
                       (when (eq (slide-kicker slide) :deck)
                         (setf (slide-kicker slide)
                               (and (eq (deck-kicker deck) :section)
                                    (slide-title slide) section)))
                       (push slide slides)))
                    ((equal tag "SECTION")
                     (let ((slide (parse-section form (incf number) line)))
                       (setf section (slide-title slide))
                       (push slide slides)))
                    ((equal tag "DECK")
                     (report :error "(deck ...) must be the first form"))
                    (t
                     (let ((*slide* nil))
                       (report :error (format nil
                                              "expected (slide ...), (section ...) or (define ...), found ~a"
                                              (if tag
                                                  (string-downcase tag)
                                                  (prin1-to-string form)))))))))
    (setf (deck-slides deck) (nreverse slides))
    (when agenda
      (add-agendas deck agenda
                   (remove-if-not (lambda (s)
                                    (and (eq (slide-kind s) :section)
                                         (slide-title s))) (deck-slides deck))))
    deck))
