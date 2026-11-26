;; Shiori: the templates and assets read at compile time, and how a book is
;; drawn -- its spine, its stars, its tags.

;;; --- assets ------------------------------------------------------------------

(defmacro define-asset (name file)
  "Bind NAME to the text of FILE, read while the program is compiled. The path
   is resolved against this source file, so it does not matter where the
   compiler or the program is started from."
  `(defparameter ,name
     ,(uiop:read-file-string (merge-pathnames file *load-truename*))))

(defmacro define-templates (name &rest template-names)
  "Bind NAME to an alist of (template-name . text), every text read at compile
   time from templates/<template-name>.mustache."
  `(defparameter ,name
     (list
      ,@(loop for template in template-names
              collect
                `(cons ,template
                       ,(uiop:read-file-string
                         (merge-pathnames
                          (format nil "templates/~a.mustache" template)
                          *load-truename*)))))))

;; Every template doubles as a partial: the layout includes the page through a
;; DYNAMIC partial ({{>*page}}), and a page includes the pieces it shares with
;; the others ({{>spine}}, {{>stars}} ...). An htmx request renders one of the
;; pieces on its own.
(define-templates *templates*
  "layout"
  "home"
  "library"
  "shelf"
  "book"
  "book-form"
  "error"
  "spine"
  "stars"
  "progress"
  "note"
  "pagination")

(define-asset *stylesheet* "static/app.css")
(define-asset *script* "static/app.js")
(define-asset *favicon* "static/favicon.svg")

(defparameter *compiled-templates*
  (let ((table (make-hash-table :test #'equal)))
    (dolist (entry *templates* table)
      (setf (gethash (car entry) table)
            (mustache:compile-template (cdr entry)))))
  "Each template parsed once, at start-up. A partial is still parsed where it
   is included -- that is cl-mustache's own model.")

(defun render (template data)
  "TEMPLATE rendered with DATA (an alist) to a string. Every template is
   reachable as a partial, so any template can include any other."
  (with-output-to-string (out)
    (funcall (gethash template *compiled-templates*)
             (mustache:make-context :data data :partials *templates*) out)))

;;; --- presenting a book ---------------------------------------------------------

(defparameter *statuses*
  '(("reading" . "読書中") ("want" . "読みたい") ("finished" . "読了") ("paused" . "中断"))
  "Every status in display order, with its label.")

(defun status-label (status) (cdr (assoc status *statuses* :test #'string=)))

(defparameter *cloths*
  '((212 48 30) (350 52 33) (158 38 24) (36 62 34) (226 38 23) (12 54 37)
    (355 35 26) (70 32 31) (100 10 34) (280 26 33) (30 6 17) (172 24 34))
  "The bookcloth a spine can be bound in, as hue, saturation and lightness:
   ai, enji, fukamidori, yamabuki-cha, tetsukon, sabishu, ebicha, uguisu,
   rikyu-nezumi, edo-murasaki, sumi, seiji-nezumi.")

(defparameter *spine-heights*
  '(188 204 224)
  "The heights a spine stands at, in pixels: a book's format.")

(defparameter *spine-width*
  '(12 22 72 30)
  "Pages per pixel of spine width, the narrowest and widest spine, and the
   width of a book whose page count is not known.")

(defun text-hash (text)
  (let ((hash 0))
    (loop for c across text
          do (setf hash (mod (+ (* hash 31) (char-code c)) 1000003)))
    hash))

(defun spine-width (pages)
  (destructuring-bind (per narrowest widest unknown) *spine-width*
    (if pages (max narrowest (min widest (round pages per))) unknown)))

(defun spine-style (title pages)
  "The custom properties app.css builds a spine from. The cloth and the
   height follow from the title alone, so a book keeps its look; the width
   follows from the page count. The book form's live preview
   (static/app.js) computes the same in JavaScript, out of *spine-json*."
  (let ((hash (text-hash title)))
    (destructuring-bind (h s l) (nth (mod hash (length *cloths*)) *cloths*)
      (format nil "--h: ~d; --s: ~d%; --l: ~d%; --w: ~dpx; --sh: ~dpx" h s l
              (spine-width pages)
              (nth (mod (floor hash (length *cloths*)) (length *spine-heights*))
                   *spine-heights*)))))

(defun pile-style (title pages)
  "A finished book lying in the dashboard's monthly pile: its cloth, and a
   length that grows with its height on the shelf."
  (let ((hash (text-hash title)))
    (destructuring-bind (h s l) (nth (mod hash (length *cloths*)) *cloths*)
      (format nil "--h: ~d; --s: ~d%; --l: ~d%; --t: ~dpx" h s l
              (max 9 (min 18 (round (spine-width pages) 4)))))))

(defparameter *stylesheet-url*
  (format nil "/static/app.css?v=~(~36r~)" (text-hash *stylesheet*))
  "The stylesheet's URL carries a hash of its text, so a changed stylesheet
   is a new URL and the old one can be cached for good.")

(defparameter *script-url*
  (format nil "/static/app.js?v=~(~36r~)" (text-hash *script*))
  "The page's script, versioned like the stylesheet.")

(defparameter *spine-json*
  (format nil
          "{\"cloths\":[~{[~{~d~^,~}]~^,~}],\"heights\":[~{~d~^,~}],\"width\":[~{~d~^,~}]}"
          *cloths* *spine-heights* *spine-width*))

(defun japanese-p (text)
  "True when TEXT holds Japanese, which a quote then sets top to bottom on
   manuscript paper."
  (some (lambda (c) (>= (char-code c) #x3000)) text))

(defun decorate-note (row)
  (cons (cons :vertical (and (cdr (assoc :quote row))
                             (japanese-p (cdr (assoc :body row))))) row))

(defun tag-href (name) (format nil "/books?tag=~a" (rontolisp:url-encode name)))

(defun stars (rating)
  (loop for n from 1 to 5
        collect (list (cons :n n) (cons :on (and rating (<= n rating))))))

(defun split-tags (tags)
  (loop for name in (uiop:split-string (or tags "") :separator ",")
        for trimmed = (string-trim " " name)
        unless (string= trimmed "")
        collect (list (cons :name trimmed) (cons :href (tag-href trimmed)))))

(defun decorate-book (row)
  "ROW (a book_cards row) plus what the templates derive from it."
  (let ((status (cdr (assoc :status row))) (title (cdr (assoc :title row))))
    (append (list (cons :spine (spine-style title (cdr (assoc :pages row))))
                  (cons :status-label (status-label status))
                  (cons :reading (string= status "reading"))
                  (cons :finished (string= status "finished"))
                  (cons :latin (not (japanese-p title)))
                  (cons :want (string= status "want"))
                  (cons :paused (string= status "paused"))
                  (cons :stars (stars (cdr (assoc :rating row))))
                  (cons :tag-list (split-tags (cdr (assoc :tags row))))) row)))

(defun find-book (db id)
  (let ((row (query-row db "select * from book_cards where id = $1" id)))
    (and row (decorate-book row))))
