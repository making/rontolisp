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
  footer
  footer-run
  callouts
  kicker
  agenda
  logo
  url
  progress
  page-url
  page-image
  page-image-font
  description
  lang
  slides)

;;; KIND is :title (generated from the deck header), :section or :slide. LAYOUT is the
;;; requested layout name or nil; FIT is the layout result (layout.lisp).
;;; CALLOUTS lists the slide's callouts in source order. KICKER is the label above the
;;; title, :deck until the deck's :kicker decides it. META lists the (TEXT RUN) of each
;;; line under the title of the title slide: the author, the date and the URL. STEPS
;;; lists what a page advance reveals, in source order: the place (:item ITEM) of a
;;; stepped item, the place (:block BLOCK N) of a stepped block, N its advance, the place
;;; (:series CHART I N) of a chart's Ith series under :step :series, code
;;; blocks with :steps, and callouts; STEP-COUNT is how many advances that takes
;;; (NUMBER-STEPS). MORPH is the :morph option: its first code block changes from the
;;; previous slide's. SOURCE holds the inline content of its (source ...) line, SOURCE-RUN
;;; its run. FOOTER is :deck (the deck's footer line shows) or nil (the slide drops it).
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
  (footer :deck)
  meta
  steps
  (step-count 0)
  morph
  source
  source-run)

;;; LEAD is true for a (lead ...) paragraph, set in the muted color.
(defstruct para inlines run lead)
;;; AGENDA is true for the list of an agenda slide that highlights its CURRENT item.
(defstruct bullets ordered items agenda)
;;; STEP is the page advance that reveals the item of a (ul :step t ...), or nil.
(defstruct item inlines sublist run current step)
;;; RUN is the deck's :run option (true unless :run nil); RUNNABLE is set when the slide is
;;; fitted, from the language, RUN and the deck's runtime.
;;; STEPS lists, for each step of :steps, the 0-based indices of the lines it brings
;;; forward; STEP-NUMBERS the page advance of each. MORPH is set when a slide's :morph
;;; pairs the block (morph.lisp): (:target . PAIRS) for the block that changes, PAIRS the
;;; index of the old token each of its tokens continues or nil; :source for the block it
;;; changes from. BAND is set when the slide is fitted for runnable code whose first rows
;;; start below its Run and Reset buttons (TOOLS-BAND-P).
(defstruct code-block
  lang
  lines
  (run t)
  runnable
  band
  timeout
  fresh
  stdin
  steps
  step-numbers
  morph)
;;; An (image ...) or, with VIDEO true, a (video ...): the layout treats both alike.
;;; STRIP is the width kept free on the right for the bubble of a callout on the image.
;;; FIT is :contain when an image filling the slide or a side of it is shown whole on the
;;; background, nil to cover its area (the :fit option); SHARE is the (NUMERATOR
;;; DENOMINATOR) of the canvas width it takes beside text, nil for half (the :share option).
;;; POSTER, LOOP, MUTED and AUTOPLAY are a video's options; AUTOPLAY plays it when its
;;; slide is entered.
(defstruct figure src alt caption caption-run strip fit share video poster loop muted
  autoplay)

(defun figure-noun (f) (if (figure-video f) "video" "image"))
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
;;; A diagram drawn from an S-expression. KIND is :flow, boxes joined by arrows, whose
;;; LABELS hold each box's inline content and RUNS their runs, DATUM its flow-graph; a
;;; chart, :bars, :columns, :lines or :pie, whose LABELS are its rows' labels and DATUM
;;; its chart; or :cells, the box-and-pointer drawing of the list DATUM. SIZE is the label
;;; size and DRAWING the shapes (diagram.lisp), both set when the slide is fitted.
(defstruct diagram kind labels runs datum size drawing)
;;; The data of a chart. SERIES holds the inline name of each series, nil for a chart of
;;; one; VALUES each row's values, one per series, as rationals, and TEXTS the same as
;;; written; UNIT the text written after every value; GROUPED true when a value is written
;;; with a thousands comma, so a number lispresen computes is too. STACK, AXIS and DONUT
;;; are the options. MARKS lists (CALLOUT SERIES ROW) of each callout around a value;
;;; STEPS the place of each series among the slide's steps under :step :series, else nil.
;;; SERIES-RUNS are the names' runs, set when the slide is fitted.
(defstruct chart series series-runs values texts unit grouped stack axis donut marks
  steps)
;;; The arrows of a flow. DIRECTION is :right or :down; EDGES are (FROM TO LABEL) between
;;; box indices, LABEL the index of the arrow's label among EDGE-LABELS (inline content) or
;;; nil. EDGE-RUNS are the labels' runs, set when the slide is fitted; PLAN is how the flow
;;; is drawn at any size, worked out once by diagram.lisp.
(defstruct flow-graph direction edges edge-labels edge-runs plan)
;;; Consecutive columns of a slide, side by side in one row; WIDTHS is set when the slide
;;; is fitted.
(defstruct column-row columns widths)

;;; A comment in a speech bubble pointing at its anchor. TARGET is the block it points
;;; at, nil for an inline anchor (the run holding it lists it among its ANCHORS); AT is
;;; the text that names the anchor inside a code block; STEP is the :step option, :deck
;;; when the deck's :callouts decides whether a page advance shows the bubble, and
;;; STEP-NUMBER that advance, nil for a bubble shown with the slide; WITHIN is the place
;;; among the slide's steps of the stepped item or block the callout stands in, whose
;;; advance reveals a bubble that is no step of its own. The rest is set when the slide
;;; is fitted (layout.lisp): RUN is the comment's text run, FRAGMENTS the anchor's
;;; rectangles (one per line), and SIZE, SIDE, X, Y, WIDTH, HEIGHT and TAIL place the
;;; bubble. DIAGRAM-BOX is the anchor's rectangle from the top left of the diagram it lies
;;; in, which the player finds it by, nil for any other anchor.
(defstruct callout
  id
  text
  at
  step
  step-number
  within
  target
  run
  fragments
  diagram-box
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
(defvar *step-within*
  nil
  "The place among *STEPS* of the stepped item or block being parsed, or nil.")

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
            (let ((name (string-upcase (symbol-name (car args)))))
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

(defun written (x)
  "The datum X as the deck writes it, for a diagnostic: a symbol by its name (the reader
keeps its case), anything else as printed."
  (cond ((keywordp x) (concatenate 'string ":" (symbol-name x)))
        ((and x (symbolp x) (not (eq x t))) (symbol-name x))
        (t (prin1-to-string x))))

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
  (member tag '("B" "I" "MARK" "ACCENT" "TT" "LINK" "SAY" "MATH") :test #'equal))

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
                                            :deck)
                                  :within *step-within*)))
               (when (equal (callout-at c) "")
                 (report :error ":at of say must not be empty")
                 (setf (callout-at c) nil))
               (push c *callouts*)
               c))))))

(defun parse-inline (x)
  "The inline nodes X stands for; an unknown form is reported and dropped."
  (cond ((stringp x) (list (text-leaf x)))
        ((floatp x)
         ;; Its printing is the one thing the backends may not share.
         (report :error "a decimal in text is written as a string, such as \"2.5\"")
         nil)
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
                        (report :error
                                ":at belongs to a callout around a code block or a diagram")
                        (setf (callout-at c) nil))
                      (list (list :say c nodes))))))
            ((equal tag "MATH")
             (let ((f (parse-math x nil))) (and f (list (list :math f)))))
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
          :error (format nil "unexpected ~a in text" (written x)))
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
                     (let ((*step-within* (or place *step-within*)))
                       (if (equal (form-tag x) "LI")
                           (parse-li (cdr x))
                           (make-item :inlines (parse-inline x))))))
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
                       "STEPS" "STEP")
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
  "The figure of an (image ...) or a (video ...) FORM, or nil after reporting."
  (let* ((src (cadr form))
         (video (equal (form-tag form) "VIDEO"))
         (noun (if video "video" "image")))
    (if (not (stringp src))
        (progn
          (report :error (format nil "(~a ...) takes the ~:*~a path first" noun))
          nil)
        (multiple-value-bind (opts rest)
            (split-options (cddr form)
                           (append '("ALT" "CAPTION" "FIT" "SHARE" "STEP")
                                   (and video '("POSTER" "LOOP" "MUTED" "AUTOPLAY")))
                           noun)
          (when rest
            (report :error (format nil "(~a ...) takes only a path and options" noun)))
          (let ((alt (string-option opts "ALT" noun)))
            (unless alt
              (report :warning (format nil "~a ~a has no :alt" noun src)))
            (make-figure :src src
                         :alt alt
                         :caption (string-option opts "CAPTION" noun)
                         :fit (parse-image-fit (opt opts "FIT") noun)
                         :share (parse-image-share (opt opts "SHARE") noun)
                         :video video
                         :poster (and video (raw-string-option opts "POSTER" noun))
                         :loop (and (opt opts "LOOP") t)
                         :muted (and (opt opts "MUTED") t)
                         :autoplay (and (opt opts "AUTOPLAY") t)))))))

(defun parse-image-fit (x noun)
  "The :fit of an image or video: :contain, or nil for :cover and when absent."
  (cond ((null x) nil)
        ((equal (symbol-value-name x) "COVER") nil)
        ((equal (symbol-value-name x) "CONTAIN") :contain)
        (t (report :error (format nil ":fit of ~a must be :cover or :contain" noun)) nil)))

(defparameter *image-shares* '((1 3) (1 2) (2 3)))

(defun parse-image-share (x noun)
  "The :share of an image or video as (NUMERATOR DENOMINATOR), or nil when absent."
  (cond ((null x) nil)
        ((and (rationalp x) (not (integerp x))
              (member (list (numerator x) (denominator x)) *image-shares*
                      :test #'equal))
         (list (numerator x) (denominator x)))
        (t (report :error (format nil ":share of ~a must be 1/3, 1/2 or 2/3" noun)) nil)))

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
                                    '("HEADER" "ALIGN" "CAPTION" "ROW-HEADER" "STEP")
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
  (multiple-value-bind (opts body) (split-options (cdr form) '("CITE" "STEP") "quote")
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
                                           (nth-value 1
                                                      (split-options (cdr form) '("STEP")
                                                                     "column")))))))

(defparameter *flow-boxes* 8 "A flow of more boxes than this is reported.")
(defparameter *cells-limit*
  32
  "The most conses a cells drawing holds; more are not legible on a slide.")

(defun flow-label (x)
  "The inline content of the box label X: a string, an inline form, or a list of them
for mixed content."
  (if (and (consp x) (not (form-tag x)))
      (parse-inlines x)
      (parse-inline x)))

(defun node-name-p (x)
  "True when X can name a box of a flow: a symbol, not a keyword, nil or t."
  (and x (symbolp x) (not (keywordp x)) (not (eq x t))))

(defun flow-cycle (n edges)
  "A cycle among N boxes joined by EDGES ((FROM TO LABEL) ...), as its box indices from
the first box back to it, or nil."
  (let ((state (make-array n :initial-element 0))
        (succs (make-array n :initial-element nil)))
    (dolist (e (reverse edges)) (push (second e) (aref succs (first e))))
    (labels ((visit (v path)
               (setf (aref state v) 1)
               (dolist (w (aref succs v))
                 (case (aref state w)
                   (0 (visit w (cons v path)))
                   (1 (return-from flow-cycle
                        (append (member w (reverse (cons v path))) (list w))))))
               (setf (aref state v) 2)))
      (dotimes (v n) (when (zerop (aref state v)) (visit v nil))))
    nil))

(defun parse-flow (form)
  "A diagram from (flow [:direction :right|:down] path...): boxes joined by arrows. The
body is one path, or several (-> step...) paths. A step is a box -- a label, which names
the box by its text; (node NAME label...), which names it by the symbol NAME; or NAME --
or (label inline...), which labels the arrow between the boxes either side of it."
  (multiple-value-bind (opts body)
      (split-options (cdr form) '("DIRECTION" "STEP") "flow")
    (let ((direction (symbol-value-name (opt opts "DIRECTION" :right)))
          (paths nil)
          (boxes nil)
          (count 0)
          (texts nil)
          (names nil)
          (edges nil)
          (edge-labels nil)
          (failed nil))
      (unless (member direction '("RIGHT" "DOWN") :test #'equal)
        (report :error ":direction of flow must be :right or :down")
        (setf direction "RIGHT"))
      (if (some (lambda (x) (equal (form-tag x) "->")) body)
          (dolist (x body)
            (if (equal (form-tag x) "->")
                (push (cdr x) paths)
                (report :error
                        "a flow written as (-> ...) paths takes every box in a path")))
          (push body paths))
      (labels ((new-box (inlines)
                 (push (cons count inlines) boxes)
                 (incf count)
                 (1- count))
               (named (x)
                 (let ((entry (assoc (name-of x) names :test #'string=)))
                   (or entry
                       (car (push (list (name-of x) (new-box nil) nil (symbol-name x))
                                  names)))))
               (step-box (x)
                 ;; The index of the box the step X stands for, nil after reporting.
                 (cond
                  ((node-name-p x) (second (named x)))
                  ((equal (form-tag x) "NODE")
                   (if (not (and (consp (cdr x)) (node-name-p (cadr x)) (cddr x)))
                       (progn
                         (report :error "(node ...) takes a name and the box's label")
                         nil)
                       (let ((entry (named (cadr x)))
                             (inlines (or (parse-inlines (cddr x)) (list ""))))
                         (if (third entry)
                             (report :error (format nil
                                                    "the flow declares the box ~a twice"
                                                    (symbol-name (cadr x))))
                             (setf (third entry) t
                                   (cdr (assoc (second entry) boxes)) inlines))
                         (second entry))))
                  (t
                   (let* ((callouts *callouts*)
                          (steps *steps*)
                          (inlines (flow-label x))
                          (text (and inlines (values (flatten-inlines inlines nil))))
                          (entry (and inlines (assoc text texts :test #'string=))))
                     (cond ((null inlines) nil)
                           (entry
                            ;; A later appearance names the box; its first one draws it.
                            (unless (eq callouts *callouts*)
                              (setf *callouts* callouts *steps* steps)
                              (late-callout text))
                            (cdr entry))
                           (t (let ((i (new-box inlines)))
                                (push (cons text i) texts)
                                i)))))))
               (late-callout (text)
                 (report :error
                         (format nil "a callout in the box ~s belongs on its first appearance"
                                 text)))
               (box-text (i)
                 (values (flatten-inlines (cdr (assoc i boxes)) nil)))
               (connect (from to label)
                 (let ((edge (find-if (lambda (e)
                                        (and (= (first e) from) (= (second e) to)))
                                      edges)))
                   (cond ((null edge)
                          (push (list from to nil) edges)
                          (setf edge (car edges)))
                         ((and label (third edge))
                          (report :error (format nil
                                                 "the arrow from ~s to ~s has two labels"
                                                 (box-text from) (box-text to)))
                          (setf label nil)))
                   (when label
                     (push label edge-labels)
                     (setf (third edge) (1- (length edge-labels)))))))
        (dolist (path (reverse paths))
          (let ((prev nil) (label nil) (labelled nil))
            (dolist (x path)
              (if (equal (form-tag x) "LABEL")
                  (progn
                    (when (or (null prev) labelled)
                      (report :error
                              "(label ...) stands between two boxes of a path"))
                    (setf labelled t
                          label (parse-inlines (cdr x)))
                    (unless label (report :error "(label ...) needs text")))
                  (let ((i (step-box x)))
                    (when i
                      (when prev (connect prev i (and labelled label)))
                      (setf prev i labelled nil label nil)))))
            (when labelled
              (report :error "(label ...) stands between two boxes of a path")))))
      (dolist (entry (reverse names))
        (unless (third entry)
          (setf failed t)
          (report :error (format nil
                                 "the flow names ~a, but no (node ~a ...) declares it"
                                 (fourth entry) (fourth entry)))))
      (setf edges (nreverse edges))
      (let ((box-labels (mapcar #'cdr (sort boxes #'< :key #'car)))
            (cycle (and (not failed) (flow-cycle count edges))))
        (cond
         ((zerop count)
          (report :error "(flow ...) needs at least one box")
          nil)
         (cycle
          (report :error (format nil "the flow has a cycle: ~{~s~^ -> ~}"
                                 (mapcar (lambda (i)
                                           (values
                                            (flatten-inlines (nth i box-labels) nil)))
                                         cycle)))
          nil)
         (failed nil)
         (t
          (when (> count *flow-boxes*)
            (report :warning (format nil
                                     "a flow of ~d boxes is hard to follow; keep it to ~d"
                                     count *flow-boxes*)))
          (make-diagram :kind :flow
                        :labels box-labels
                        :datum (make-flow-graph :direction (if (equal direction "DOWN")
                                                               :down
                                                               :right)
                                                :edges edges
                                                :edge-labels (reverse edge-labels)))))))))

;;; --- charts ------------------------------------------------------------------

(defparameter *chart-colors*
  6
  "The series colors of a theme: a chart of more series, or a pie of more slices, has no
color left for them.")
(defparameter *chart-series* 4 "A chart of more series than this is reported.")
(defparameter *chart-rows*
  '((:bars . 7) (:columns . 12) (:lines . 12) (:pie . 5))
  "Each chart's most rows before it is reported, as a table of more rows is.")

(defun series-step-p (x)
  "True for :step :series, which reveals a chart a series at a time."
  (and (keywordp x) (equal (symbol-value-name x) "SERIES")))

(defun chart-value (x)
  "The value X stands for in a chart, a rational, and the text it is written with: an
integer, or a decimal written as a string (\"2.1\", \"-0.5\", \"1,840\"), so no float
printing reaches the output. Nil for anything else."
  (cond ((integerp x) (values x (format nil "~d" x)))
        ((stringp x)
         (let* ((s (string-trim " " x))
                (n (length s))
                (minus (and (> n 0) (char= (char s 0) #\-)))
                (i (if minus 1 0))
                (whole 0)
                (frac 0)
                (scale 1)
                (digits 0))
           (loop while (and (< i n)
                            (or (digit-char-p (char s i))
                                (and (char= (char s i) #\,) (> digits 0))))
                 do
                   (let ((d (digit-char-p (char s i))))
                     (when d
                       (setf whole (+ (* whole 10) d))
                       (incf digits)))
                   (incf i))
           (when (and (< (1+ i) n) (char= (char s i) #\.) (> digits 0))
             (incf i)
             (loop while (and (< i n) (digit-char-p (char s i)))
                   do
                     (setf frac (+ (* frac 10) (digit-char-p (char s i)))
                           scale (* scale 10))
                     (incf i)))
           (if (and (> digits 0) (= i n))
               (let ((v (+ whole (/ frac scale)))) (values (if minus (- v) v) s))
               nil)))
        (t nil)))

(defun chart-tag (kind) (string-downcase (symbol-name kind)))

(defun chart-row-noun (kind)
  (case kind (:lines "points") (:pie "slices") (t "categories")))

(defun parse-chart-label (x)
  "The inline content of a row's label or a series' name: a string, an inline form, or a
list of them."
  (if (and (consp x) (not (form-tag x))) (parse-inlines x) (parse-inline x)))

(defun parse-chart-series (names tag)
  "The inline name of each series :series NAMES lists, nil when there is no :series;
:fail after reporting."
  (cond ((null names) nil)
        ((not (and (consp names) (not (form-tag names)) (consp (cdr names))
                   (null (cdr (last names)))))
         (report :error (format nil
                                ":series of ~a lists the names of two series or more"
                                tag))
         :fail)
        ((> (length names) *chart-colors*)
         (report :error (format nil
                                "a chart draws at most ~d series, one per theme color; fold the rest into one"
                                *chart-colors*))
         :fail)
        (t
         (let ((series (mapcar #'parse-chart-label names)))
           (if (some #'null series) :fail series)))))

(defun parse-chart-value (x chart pie place)
  "The value, text and callout of the value X of a chart's row; nil after reporting. X
may be wrapped in (say \"comment\" value), which points at its mark; PLACE is the step of
the value's series, or nil."
  (if (equal (form-tag x) "SAY")
      (let* ((*step-within* (or place *step-within*))
             (c (parse-say x))
             (content (say-content x)))
        (cond ((null c) nil)
              ((or (cdr content) (consp (car content)))
               (setf *callouts* (remove c *callouts*))
               (report :error "a (say ...) around a chart's value holds just the value")
               nil)
              (t
               (when (callout-at c)
                 (report :error ":at belongs to a callout around a code block or a diagram")
                 (setf (callout-at c) nil))
               (multiple-value-bind (v text) (parse-chart-value (car content) chart pie nil)
                 (if v
                     (progn
                       (push c *steps*)
                       (values v text c))
                     (progn
                       (setf *callouts* (remove c *callouts*))
                       nil))))))
      (multiple-value-bind (v text) (chart-value x)
        (cond ((null v)
               (report :error
                       (if (floatp x)
                           "a chart's value is a float; write it as a string, such as \"2.1\""
                           "a chart's value is an integer or a decimal string"))
               nil)
              ((and pie (< v 0))
               (report :error "a slice of a pie is 0 or more")
               nil)
              (t
               (when (find #\, text) (setf (chart-grouped chart) t))
               (values v text))))))

(defun parse-chart (form kind)
  "A diagram from (bars|columns|lines|pie [:unit \"s\"] [:series (name...)] ... row...):
a row is (label value...), its label inline content and one value per series."
  (let* ((tag (chart-tag kind))
         (pie (eq kind :pie))
         (allowed (append '("UNIT" "STEP")
                          (case kind
                            (:pie '("DONUT"))
                            (:lines '("SERIES"))
                            (t '("SERIES" "STACK" "AXIS"))))))
    (multiple-value-bind (opts body) (split-options (cdr form) allowed tag)
      (let* ((series (parse-chart-series (opt opts "SERIES") tag))
             (n (if (consp series) (length series) 1))
             (chart (make-chart :series (and (consp series) series)
                                :unit (or (string-option opts "UNIT" tag) "")
                                :stack (and (opt opts "STACK") (> n 1) t)
                                :axis (and (opt opts "AXIS") t)
                                :donut (and (opt opts "DONUT") t)))
             (b (make-diagram :kind kind :datum chart))
             (labels nil)
             (vals nil)
             (texts nil))
        (when (eq series :fail) (return-from parse-chart nil))
        (when (and (eq kind :columns) (some (lambda (row) (and (consp row) (form-tag row))) body))
          ;; A slip for the layout form, whose rows are blocks.
          (report :error "(columns ...) draws a column chart; blocks side by side are (column ...) each")
          (return-from parse-chart nil))
        (when (series-step-p (opt opts "STEP"))
          (if pie
              (report :error ":step :series reveals the series of bars, columns or lines")
              ;; Each series' place is taken before the rows are read, so a step callout
              ;; around one of its values comes after it.
              (setf (chart-steps chart)
                    (loop for i below n
                          collect (let ((place (list :series b i)))
                                    (push place *steps*)
                                    place)))))
        (loop while body
              do
                (let ((row (pop body)))
                  (cond ((keywordp row)
                         (report :error (format nil
                                                "the options of (~a ...) come before its rows"
                                                tag))
                         (pop body))
                        ((not (and (consp row) (not (form-tag row))
                                   (null (cdr (last row))) (= (length row) (1+ n))))
                         (report :error (if (= n 1)
                                            "a row of a chart is a list of its label and its value"
                                            (format nil
                                                    "a row of a chart is a list of its label and ~d values, one per series"
                                                    n))))
                        (t
                         (let ((nodes (parse-chart-label (car row))) (vs nil) (ts nil)
                               (ok t))
                           (loop for x in (cdr row)
                                 for i from 0
                                 do
                                   (multiple-value-bind (v text c)
                                       (parse-chart-value x chart pie
                                                          (nth i (chart-steps chart)))
                                     (if v
                                         (progn
                                           (push v vs)
                                           (push text ts)
                                           (when c
                                             (push (list c i (length labels))
                                                   (chart-marks chart))))
                                         (setf ok nil))))
                           (when (and ok nodes)
                             (push nodes labels)
                             (push (nreverse vs) vals)
                             (push (nreverse ts) texts)))))))
        (let ((rows (length labels))
              (limit (cdr (assoc kind *chart-rows*))))
          (setf (chart-marks chart) (nreverse (chart-marks chart)))
          (cond ((zerop rows)
                 (report :error (format nil "(~a ...) needs at least one row" tag))
                 (setf b nil))
                ((and pie (zerop (reduce #'+ (mapcar #'car vals))))
                 (report :error "a pie needs a slice above 0")
                 (setf b nil))
                ((and pie (> rows *chart-colors*))
                 (report :error (format nil
                                        "a pie draws at most ~d slices, one per theme color; fold the rest into one"
                                        *chart-colors*))
                 (setf b nil))
                (t
                 (when (> n *chart-series*)
                   (report :warning (format nil
                                            "a chart of ~d series is hard to read; keep it to ~d"
                                            n *chart-series*)))
                 (when (> rows limit)
                   (report :warning (format nil "a ~a of ~d ~a is hard to read; keep it to ~d"
                                            (diagram-name b) rows (chart-row-noun kind)
                                            limit)))))
          (if b
              (progn
                (setf (diagram-labels b) (nreverse labels)
                      (chart-values chart) (nreverse vals)
                      (chart-texts chart) (nreverse texts))
                (dolist (m (chart-marks chart)) (setf (callout-target (first m)) b))
                b)
              ;; The callouts around its values point at nothing.
              (let ((marks (mapcar #'first (chart-marks chart))))
                (setf *callouts* (remove-if (lambda (c) (member c marks)) *callouts*)
                      *steps* (remove-if (lambda (s)
                                           (or (member s marks)
                                               (member s (chart-steps chart))))
                                         *steps*))
                nil)))))))

(defun diagram-name (b)
  "What diagnostics call the diagram B."
  (case (diagram-kind b)
    (:flow "flow")
    (:bars "bar chart")
    (:columns "column chart")
    (:lines "line chart")
    (:pie "pie chart")
    (t "cells drawing")))

(defun conses-within-p (x limit)
  "True when X holds at most LIMIT conses."
  (let ((n 0))
    (labels ((walk (x)
               (loop while (consp x)
                     do
                       (when (> (incf n) limit)
                         (return-from conses-within-p nil)) (walk (car x))
                       (setf x (cdr x)))))
      (walk x)
      t)))

(defun bad-atom (x)
  "A description of the first atom in the datum X that cells cannot draw, or nil."
  (cond ((consp x) (or (bad-atom (car x)) (bad-atom (cdr x))))
        ((or (null x) (atom-label x)) nil)
        ((floatp x) "a float")
        ((vectorp x) "a vector")
        (t "this value")))

(defun parse-cells (form)
  "A diagram from (cells 'list): the list's box-and-pointer drawing. The datum may be
quoted or not."
  (let* ((args (nth-value 1 (split-options (cdr form) '("STEP") "cells")))
         (datum (and (consp args) (car args))))
    (when (and (equal (form-tag datum) "QUOTE") (consp (cdr datum))
               (null (cddr datum)))
      (setf datum (cadr datum)))
    (cond ((not (and (consp args) (null (cdr args))))
           (report :error "(cells ...) takes one list, such as '(1 (2 3))")
           nil)
          ((not (consp datum))
           (report :error "(cells ...) draws a list, such as '(1 (2 3))")
           nil)
          ((not (conses-within-p datum *cells-limit*))
           (report :error (format nil
                                  "(cells ...) draws at most ~d conses; this list has more"
                                  *cells-limit*))
           nil)
          ((bad-atom datum)
           (report :error (format nil
                                  "(cells ...) draws symbols, integers, ratios, strings and characters; ~a is not one"
                                  (bad-atom datum)))
           nil)
          (t (make-diagram :kind :cells :datum datum)))))

(defun diagram-label-texts (b)
  "The text of each label of the diagram B, in the order :at searches them: the boxes of
a flow, the rows of a chart and then its series, the atoms of cells as printed."
  (if (eq (diagram-kind b) :cells)
      (datum-atoms (diagram-datum b))
      (mapcar (lambda (nodes) (values (flatten-inlines nodes nil)))
              (append (diagram-labels b)
                      (and (chart-p (diagram-datum b))
                           (chart-series (diagram-datum b)))))))

(defun label-index (texts at)
  "The index of the label AT names among TEXTS: the first equal to it, else the first
holding it; nil when none does."
  (or (position at texts :test #'string=)
      (position-if (lambda (s) (search at s)) texts)))

(defparameter *anchor-blocks*
  '("P" "LEAD" "UL" "OL" "CODE" "IMAGE" "VIDEO" "TABLE" "QUOTE" "FLOW" "CELLS" "BARS"
    "COLUMNS" "LINES" "PIE" "MATH")
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
                  ((and (callout-at c)
                        (not (or (code-block-p b) (diagram-p b))))
                   (report
                    :error
                    ":at belongs to a callout around a code block or a diagram")
                   (setf (callout-at c) nil))
                  ((and (callout-at c) (code-block-p b)
                        (notany (lambda (l) (search (callout-at c) l))
                                (code-block-lines b)))
                   (report :error (format nil
                                          "the :at text ~s is not on a line of the code"
                                          (callout-at c)))
                   (setf (callout-at c) nil))
                  ((and (callout-at c) (diagram-p b)
                    (null (label-index (diagram-label-texts b) (callout-at c))))
                   (report :error (format nil
                                   "the :at text ~s is not a label of the ~a"
                                   (callout-at c) (diagram-name b)))
                   (setf (callout-at c) nil)))
            (when b
              (setf (callout-target c) b)
              ;; A bubble on a stepped block is revealed with it.
              (let ((place (block-place b *steps*)))
                (when place (setf (callout-within c) place)))
              (push c *steps*)))
          b))))

(defparameter *stepped-blocks*
  '("P" "LEAD" "CODE" "IMAGE" "VIDEO" "TABLE" "QUOTE" "COLUMN" "FLOW" "CELLS" "BARS"
    "COLUMNS" "LINES" "PIE" "MATH")
  "The blocks :step t reveals whole on a page advance.")

(defun step-option (x)
  "The value of :step among the leading options of the block form X, which follow the
path of an image or a video; nil when it has none."
  (let ((args (if (member (form-tag x) '("IMAGE" "VIDEO") :test #'equal)
                  (cddr x)
                  (cdr x))))
    (loop
      (unless (and (consp args) (keywordp (car args))) (return nil))
      (when (string-equal (symbol-name (car args)) "STEP")
        (return (and (consp (cdr args)) (cadr args))))
      (setf args (cddr args)))))

(defun block-place (b steps)
  "The place (:block B N) of the block B among STEPS, or nil when B is no step."
  (find-if (lambda (s) (and (consp s) (eq (car s) :block) (eq (second s) b))) steps))

(defun block-step (b steps)
  "The page advance that reveals the block B among STEPS, a slide's steps, or nil."
  (third (block-place b steps)))

(defun parse-block (x &optional in-column)
  "The block X stands for, or nil after reporting. IN-COLUMN is true inside a column. A
block with :step t takes its place among the slide's steps before its content is parsed,
so a step inside it (a callout) comes after it, and a bubble inside it that is no step
is revealed with it. A chart with :step :series takes a step per series instead."
  (if (and (member (form-tag x) *stepped-blocks* :test #'equal)
           (let ((step (step-option x)))
             (cond ((not (series-step-p step)) step)
                   ((member (form-tag x) '("BARS" "COLUMNS" "LINES" "PIE") :test #'equal)
                    nil)
                   (t
                    (report :error
                            ":step :series reveals the series of bars, columns or lines")
                    nil))))
      (let ((place (list :block)) (b nil))
        (push place *steps*)
        (let ((*step-within* place)) (setf b (parse-unstepped-block x in-column)))
        (if b
            (setf (cdr place) (list b))
            (setf *steps* (remove place *steps*)))
        b)
      (parse-unstepped-block x in-column)))

(defun parse-paragraph (x lead)
  "The paragraph of (p [:step t] inline...) or (lead ...)."
  (multiple-value-bind (opts body)
      (split-options (cdr x) '("STEP") (string-downcase (form-tag x)))
    (declare (ignore opts))
    (make-para :inlines (parse-inlines body) :lead lead)))

(defun parse-unstepped-block (x in-column)
  (cond ((stringp x) (make-para :inlines (list (text-leaf x))))
        ((consp x)
         (let ((tag (form-tag x)))
           (cond ((equal tag "P") (parse-paragraph x nil))
                 ((equal tag "LEAD") (parse-paragraph x t))
                 ((equal tag "SAY") (parse-block-say x in-column))
                 ((list-tag-p x) (parse-list x))
                 ((equal tag "CODE") (parse-code x))
                 ((member tag '("IMAGE" "VIDEO") :test #'equal) (parse-image x))
                 ((equal tag "TABLE") (parse-table x))
                 ((equal tag "QUOTE") (parse-quote x))
                 ((equal tag "COLUMN") (parse-column x in-column))
                 ((equal tag "FLOW") (parse-flow x))
                 ((equal tag "CELLS") (parse-cells x))
                 ((equal tag "BARS") (parse-chart x :bars))
                 ((equal tag "COLUMNS") (parse-chart x :columns))
                 ((equal tag "LINES") (parse-chart x :lines))
                 ((equal tag "PIE") (parse-chart x :pie))
                 ;; A formula in block position is a display formula of its own.
                 ((equal tag "MATH")
                  (parse-math (cons (car x)
                                    (nth-value 1 (split-options (cdr x) '("STEP") "math")))
                              t))
                 ((inline-tag-p tag) (make-para :inlines (parse-inline x)))
                 ((equal tag "LI")
                  (report :error "(li ...) belongs in (ul ...) or (ol ...)")
                  nil)
                 ((equal tag "SOURCE")
                  (report :error "(source ...) belongs in (slide ...) itself")
                  nil)
                 (t
                  (report :error (format nil "unknown element ~a"
                                  (if tag (string-downcase tag) "(a list)")))
                  nil))))
        (t
         (report
          :error (format nil "unexpected ~a on a slide" (written x)))
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
      (split-options (cdr form) '("TITLE" "KICKER" "LAYOUT" "CLASS" "MORPH" "FOOTER")
                     "slide")
    (let* ((title (string-option opts "TITLE" "slide"))
           (*slide-title* title)
           (*callouts* nil)
           (*steps* nil)
           (blocks nil)
           (notes nil)
           (source nil))
      (dolist (x body)
        (cond ((equal (form-tag x) "NOTES")
               (let ((args (drop-step (cdr x) "notes, which are not on the slide")))
                 (if (every #'stringp args)
                     (push (apply #'concatenate 'string args) notes)
                     (report :error "(notes ...) takes strings only"))))
              ((equal (form-tag x) "SOURCE")
               (if source
                   (report :error "a slide holds one (source ...)")
                   (setf source
                         (parse-source
                          (cons (car x)
                                (drop-step (cdr x) "a source, which shows with its slide"))))))
              (t (let ((b (parse-block x))) (when b (push b blocks))))))
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
       :footer (parse-slide-footer opts)
       :callouts (reverse *callouts*)
       :steps (reverse *steps*)
       :layout (let ((l (opt opts "LAYOUT")))
                 (and l (string-downcase (symbol-value-name l))))
       :class (opt opts "CLASS")
       :morph (and (opt opts "MORPH") t)
       :source source
       :blocks (group-columns (nreverse blocks))
       :notes (and notes (format nil "~{~a~^~%~}" (nreverse notes)))))))

(defun drop-step (args what)
  "ARGS without a leading :step and its value, which is reported: WHAT takes none."
  (if (and (keywordp (car args)) (string-equal (symbol-name (car args)) "STEP"))
      (progn
        (report :error (format nil ":step does not apply to ~a" what))
        (cddr args))
      args))

(defun strip-callouts (nodes)
  "The inline NODES with every callout replaced by the content it wraps."
  (mapcan (lambda (n)
            (cond ((or (stringp n) (member (car n) '(:tt :math))) (list n))
                  ((eq (car n) :say) (strip-callouts (third n)))
                  ((eq (car n) :link)
                   (list (list :link (second n) (strip-callouts (third n)))))
                  (t (list (list (car n) (strip-callouts (second n)))))))
          nodes))

(defun parse-source (form)
  "The inline content of (source inline...), the line naming where a slide's content
comes from. It takes no callouts: one in it is reported, and the content it wraps kept."
  (let* ((*callouts* nil) (*steps* nil) (nodes (parse-inlines (cdr form))))
    (when *callouts* (report :error "a source takes no callouts"))
    (unless nodes (report :error "(source ...) needs text"))
    (strip-callouts nodes)))

(defun place-step (place)
  "The page advance of PLACE, the place of a stepped item, block or series among a slide's
steps."
  (case (car place)
    (:item (item-step (second place)))
    (:series (fourth place))
    (t (third place))))

(defun number-steps (slide callouts)
  "Numbers the page advances of SLIDE in source order: one per stepped item or block, one
per step of a code block's :steps, one per step callout (by its :step, or by CALLOUTS, the
deck's :callouts, when it has none). A callout that is no step but stands in a stepped
item or block takes that one's advance."
  (let ((n 0))
    (dolist (s (slide-steps slide))
      (cond ((and (consp s) (eq (car s) :item)) (setf (item-step (second s)) (incf n)))
            ((and (consp s) (eq (car s) :series)) (setf (cdddr s) (list (incf n))))
            ((consp s) (setf (cddr s) (list (incf n))))
            ((code-block-p s)
             (setf (code-block-step-numbers s)
                   (loop repeat (length (code-block-steps s)) collect (incf n))))
            ((if (eq (callout-step s) :deck)
                 (eq callouts :step)
                 (callout-step s))
             (setf (callout-step-number s) (incf n)))
            ((callout-within s)
             (setf (callout-step-number s) (place-step (callout-within s))))))
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

(defun parse-lang-option (opts)
  "The :lang of the deck header: a BCP 47 tag of letters, digits and hyphens, or nil."
  (let ((v (string-option opts "LANG" "deck")))
    (cond ((null v) nil)
          ((and (plusp (length v))
                (every (lambda (c) (or (char= c #\-) (and (< (char-code c) 128) (alphanumericp c))))
                       v))
           v)
          (t
           (report :error ":lang of deck must be a language tag of letters, digits and -")
           nil))))

(defun parse-deck-header (form)
  (multiple-value-bind (opts rest) (split-options (cdr form)
                                                  '("TITLE" "AUTHOR" "DATE"
                                                    "THEME" "ASPECT" "RUNTIME"
                                                    "PAGE-NUMBERS" "FOOTER" "CALLOUTS"
                                                    "KICKER" "AGENDA" "LOGO"
                                                    "URL" "PROGRESS" "PAGE-URL"
                                                    "PAGE-IMAGE" "PAGE-IMAGE-FONT"
                                                    "DESCRIPTION" "LANG") "deck")
    (when rest (report :error "(deck ...) takes options only"))
    (make-deck :title (string-option opts "TITLE" "deck")
               :author (string-option opts "AUTHOR" "deck")
               :date (string-option opts "DATE" "deck")
               :lang (parse-lang-option opts)
               :theme (opt opts "THEME")
               :aspect (opt opts "ASPECT")
               :runtime (parse-runtime (opt opts "RUNTIME"))
               :page-numbers (opt opts "PAGE-NUMBERS")
               :footer (parse-footer-option (opt opts "FOOTER"))
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
               :progress (and (opt opts "PROGRESS") t)
               :description (string-option opts "DESCRIPTION" "deck")
               :page-url (let ((u (raw-string-option opts "PAGE-URL" "deck")))
                           (cond ((or (null u) (absolute-url-p u)) u)
                                 (t
                                  (report
                                   :error
                                   ":page-url must be an absolute http or https URL")
                                  nil)))
               :page-image (raw-string-option opts "PAGE-IMAGE" "deck")
               :page-image-font (let ((f (opt opts "PAGE-IMAGE-FONT")))
                                  (cond ((null f) nil)
                                        ((stringp f) (list f))
                                        ((and (consp f) (<= (length f) 2)
                                              (every #'stringp f))
                                         f)
                                        (t
                                         (report
                                          :error
                                          ":page-image-font of deck must be a font file or (regular bold)")
                                         nil)))
               :agenda (let ((a (opt opts "AGENDA")))
                         (cond ((or (null a) (eq a t) (stringp a)) a)
                               (t
                                (report
                                 :error
                                 ":agenda of deck must be t or a title string")
                                nil))))))

;;; A link preview names the page by its absolute URL, and its image likewise; the deck
;;; may write the image relative to the page, as an <img> on it would be.

(defun absolute-url-p (s)
  "Whether S is an http or https URL with a host."
  (let ((p (search "://" s)))
    (and p
         (member (subseq s 0 p) '("http" "https") :test #'string-equal)
         (< (+ p 3) (length s))
         (not (find (char s (+ p 3)) "/?#"))
         t)))

(defun resolve-url (base ref)
  "REF against the absolute URL BASE: REF itself when absolute, BASE's origin followed by
REF when REF starts with a slash, else REF in BASE's directory."
  (let* ((host (+ (search "://" base) 3))
         (path (or (position-if (lambda (c) (find c "/?#")) base :start host)
                   (length base)))
         (end (or (position-if (lambda (c) (find c "?#")) base :start path)
                  (length base)))
         (slash (position #\/ base :start path :end end :from-end t)))
    (cond ((search "://" ref) ref)
          ((and (plusp (length ref)) (char= (char ref 0) #\/))
           (concatenate 'string (subseq base 0 path) ref))
          (slash (concatenate 'string (subseq base 0 (1+ slash)) ref))
          (t (concatenate 'string (subseq base 0 path) "/" ref)))))

(defun parse-kicker-option (x)
  "The deck's :kicker: :section (each slide's kicker is its section's title) or nil."
  (cond ((null x) nil)
        ((equal (symbol-value-name x) "SECTION") :section)
        (t
         (report :error ":kicker of deck must be :section")
         nil)))

(defun parse-footer-option (x)
  "The inline content of the deck's :footer, a string, an inline form or a list of
inlines (\"text \" (link ...)); nil when absent."
  (when x
    (let* ((*callouts* nil) (*steps* nil)
           (nodes (parse-inlines (if (and (consp x) (not (symbolp (car x))))
                                     x
                                     (list x)))))
      (when *callouts* (report :error "a footer takes no callouts"))
      (strip-callouts nodes))))

(defun parse-slide-footer (opts)
  "A slide's :footer: :deck, or nil for (slide :footer nil), which drops the deck's."
  (cond ((not (assoc "FOOTER" opts :test #'string=)) :deck)
        ((null (opt opts "FOOTER")) nil)
        (t
         (report :error ":footer of slide must be nil")
         :deck)))

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
        (agenda nil)
        (marker nil))
    (when (and forms (equal (form-tag (car (car forms))) "DECK"))
      (let ((*line* (second (car forms))))
        (setf deck (parse-deck-header (car (car forms))))
        (setf (deck-line deck) *line*))
      (setf forms (cdr forms)))
    (unless deck (setf deck (make-deck)))
    (let ((*line* (deck-line deck)))
      (when (and (or (deck-logo deck) (deck-url deck)) (null (deck-title deck)))
        (report
         :warning
         ":logo and :url are shown on the title slide, which needs a :title"))
      (let ((image (deck-page-image deck)))
        (cond ((null image)
               (when (deck-page-image-font deck)
                 (report :warning
                         ":page-image-font draws the :page-image, which this deck lacks")))
              ((null (deck-page-url deck))
               (report :warning
                       ":page-image is the preview of the :page-url, which this deck lacks"))
              ((drawn-page-image-p deck)
               (unless (string-equal (or (extension image) "") "png")
                 (report :error ":page-image is drawn as a PNG; name it .png"))
               (unless (deck-title deck)
                 (report :warning
                         ":page-image draws the title slide, which needs a :title"))
               (unless (deck-page-image-font deck)
                 (report :error
                         ":page-image is drawn in the :page-image-font, which this deck lacks"))))))
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
    ;; The agenda slide stands where the first (agenda) is, else after the title slide,
    ;; and lists the sections after it.
    (let* ((markers
            (remove-if-not (lambda (e) (equal (form-tag (first e)) "AGENDA"))
                           expanded))
           (listed
            (if markers (cdr (member (first markers) expanded)) expanded)))
      (setf marker (first markers))
      (dolist (e markers)
        (let ((*line* (second e)))
          (when (rest (first e)) (report :error "(agenda) takes no arguments"))
          (cond ((not (deck-agenda deck))
                 (report :warning
                         "(agenda) places the agenda of (deck :agenda t), which this deck lacks"))
                ((not (eq e marker))
                 (report :warning
                         "the agenda stands at the first (agenda); this one is ignored")))))
      (when (deck-agenda deck)
        (if (some (lambda (e) (equal (form-tag (first e)) "SECTION")) listed)
            (let ((at
                   (+ number 1
                      (count-if (lambda (e)
                                  (member (form-tag (first e)) '("SLIDE" "SECTION")
                                          :test #'equal))
                                expanded
                                :end (or (position marker expanded) 0)))))
              ;; The expansion counted the slides without the agenda slide placed here.
              (dolist (d *diagnostics*)
                (when (and (diagnostic-slide d) (>= (diagnostic-slide d) at))
                  (incf (diagnostic-slide d))))
              (setf agenda
                    (make-slide :line (if marker (second marker) (deck-line deck))
                                :kind :slide))
              (unless marker
                (setf (slide-number agenda) (incf number))
                (push agenda slides)))
            (let ((*line* (if marker (second marker) (deck-line deck))))
              (report :warning (if marker
                                   ":agenda lists the sections after (agenda), and this deck has none"
                                   ":agenda lists the sections, and this deck has none"))))))
    (loop for entry in expanded
          for (form line) = entry
          do
            (let ((tag (form-tag form))
                  (*line* line)
                  (*slide* (1+ number))
                  (*slide-title* nil))
              (cond ((equal tag "AGENDA")
                     (when (and agenda (eq entry marker))
                       (setf (slide-number agenda) (incf number))
                       (push agenda slides)))
                    ((equal tag "SLIDE")
                     (let ((slide (parse-slide form (incf number) line)))
                       (number-steps slide (deck-callouts deck))
                       (when (slide-morph slide) (morph-code slide (car slides)))
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
                                              "expected (slide ...), (section ...), (agenda) or (define ...), found ~a"
                                              (if tag
                                                  (string-downcase tag)
                                                  (written form)))))))))
    (setf (deck-slides deck) (nreverse slides))
    (when agenda
      (add-agendas deck agenda
                   (remove-if-not (lambda (s)
                                    (and (eq (slide-kind s) :section)
                                         (slide-title s)))
                                  (cdr (member agenda (deck-slides deck))))))
    deck))
