;; Shiori: the library (GET /books) -- search, filters, sorting and pages.

;;; --- the library -----------------------------------------------------------------

(defparameter *per-page* 36)

(defparameter *sorts*
  '(("updated" "最近更新した順" "updated_at desc, id desc")
    ("title" "タイトル順" "title asc, id asc")
    ("author" "著者順" "author asc, title asc")
    ("rating" "評価の高い順" "rating desc nulls last, updated_at desc"))
  "Each sort the library offers: its parameter value, its label and the ORDER
   BY it stands for. The ORDER BY is spliced into the SQL -- it cannot be a
   parameter -- which is safe only because it is always one of these literals,
   chosen by key, never the request's text.")

(defun library-filters (req)
  "The library's filters out of the query string, each validated: an unknown
   status or sort is ignored rather than trusted."
  (let ((status (param req "status")) (sort (param req "sort")))
    (list :q (string-trim " " (or (param req "q") ""))
          :status (and status (assoc status *statuses* :test #'string=) status)
          :tag (let ((tag (param req "tag"))) (and tag (string/= tag "") tag))
          :sort (if (assoc sort *sorts* :test #'equal) sort "updated")
          :page (or (positive-integer (param req "page")) 1))))

(defun library-where (filters)
  "The WHERE clause for FILTERS and its parameters, numbered from $1."
  (let ((clauses '()) (params '()))
    (flet ((add (template value)
             (push value params)
             (push (format nil template (length params)) clauses)))
      (when (getf filters :status) (add "status = $~d" (getf filters :status)))
      (when (getf filters :tag)
        (add "exists (select 1 from book_tags bt join tags t on t.id = bt.tag_id
                       where bt.book_id = book_cards.id and t.name = $~d)"
             (getf filters :tag)))
      ;; strpos, not like: what the visitor typed is matched literally, so a %
      ;; or an _ in it is just a character.
      (when (string/= (getf filters :q) "")
        (add
         "strpos(lower(title || ' ' || author || ' ' || tags), lower($~d)) > 0"
         (getf filters :q))))
    (values
     (if clauses (format nil " where ~{~a~^ and ~}" (reverse clauses)) "")
     (reverse params))))

(defun library-url (filters &rest overrides)
  "/books with FILTERS as its query string, OVERRIDES (a plist) replacing
   some of them. Defaults are left out, so the URL stays short."
  (let ((merged (copy-list filters)))
    (loop for (key value) on overrides by #'cddr
          do (setf (getf merged key) value))
    (let ((pairs
           (loop for (key default) in
                   '((:q "") (:status nil) (:tag nil) (:sort "updated")
                     (:page 1))
                 for value = (getf merged key)
                 unless (equal value default)
                 collect
                   (format nil "~a=~a" (string-downcase key)
                           (rontolisp:url-encode (princ-to-string value))))))
      (format nil "/books~@[?~{~a~^&~}~]" pairs))))

(defun pagination (filters total-pages)
  "Page links around the current page: the first, the last, two either side,
   and a gap marker wherever pages are skipped."
  (let ((current (getf filters :page)) (links '()) (last-shown 0))
    (loop for n from 1 to total-pages
          when (or (= n 1) (= n total-pages) (<= (abs (- n current)) 2))
          do (when (> n (1+ last-shown)) (push (list (cons :gap t)) links))
            (push (list (cons :n n) (cons :current (= n current))
                        (cons :href (library-url filters :page n))) links)
            (setf last-shown n))
    (list (cons :links (reverse links))
     (cons :prev (and (> current 1) (library-url filters :page (1- current))))
     (cons :next (and (< current total-pages)
                      (library-url filters :page (1+ current)))))))

(defun library (req)
  (let* ((db (tiny:request-get req :db)) (filters (library-filters req)))
    (multiple-value-bind (where params) (library-where filters)
      (let* ((total
              (apply #'query-value db
                     (format nil "select count(*)::int from book_cards~a" where)
                     params))
             (total-pages (max 1 (ceiling total *per-page*)))
             (page-number (min (getf filters :page) total-pages))
             (filters
              (progn
                (setf (getf filters :page) page-number)
                filters))
             (books
              (apply #'query db
                     (format nil
                             "select * from book_cards~a order by ~a
                                        limit ~d offset ~d" where
                             (third
                              (assoc (getf filters :sort) *sorts*
                                     :test #'string=)) *per-page*
                             (* (1- page-number) *per-page*)) params))
             (counts
              (query db
                     "select status, count(*)::int as n from books
                                 group by status"))
             (all (reduce #'+ counts :key (lambda (c) (cdr (assoc :n c)))))
             (data
              (list (cons :books (mapcar #'decorate-book books))
                    (cons :total total) (cons :q (getf filters :q))
                    (cons :tag (getf filters :tag))
                    (cons :clear-tag (library-url filters :tag nil :page 1))
                    (cons :filtered (or (getf filters :status)
                                        (getf filters :tag)
                                        (string/= (getf filters :q) "")))
                    (cons :pagination (and (> total-pages 1)
                                           (pagination filters total-pages)))
                    (cons :tabs (cons (list (cons :value "") (cons :label "すべて")
                                            (cons :n all)
                                            (cons :checked
                                                  (null
                                                   (getf filters :status))))
                                      (loop for (value . label) in *statuses*
                                            collect
                                              (list (cons :value value)
                                                    (cons :label label)
                                                    (cons :n
                                                          (or (cdr
                                                               (assoc
                                                                :n
                                                                (find value
                                                                 counts
                                                                 :test #'string=
                                                                 :key
                                                                 (lambda (c)
                                                                   (cdr
                                                                    (assoc
                                                                     :status
                                                                     c)))))) 0))
                                                    (cons :checked
                                                          (equal value
                                                                 (getf filters
                                                                  :status)))))))
                    (cons :sorts (loop for (value label) in *sorts*
                                       collect
                                         (list (cons :value value)
                                               (cons :label label)
                                               (cons :selected
                                                     (string= value
                                                              (getf filters
                                                               :sort)))))))))
        ;; The search form targets #shelf: answer only the shelf. Anything else
        ;; -- a boosted link, a bookmark, a reload -- gets the whole page.
        (if (equal (htmx-target-id req) "shelf")
            (html 200 (render "shelf" data))
            (page req "library" "本棚" data))))))
