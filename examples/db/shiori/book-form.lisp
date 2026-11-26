;; Shiori: the book form -- registering, editing and removing a book.

;;; --- the book form -------------------------------------------------------------

(defparameter *form-fields*
  '("title" "author" "pages" "current_page" "status" "rating" "started_on"
    "finished_on" "tags" "summary"))

(defun date-text-p (text)
  "True when TEXT looks like the YYYY-MM-DD an <input type=date> sends. The
   server checks the date itself; this keeps a malformed one a form error
   rather than a failed statement."
  (and (= (length text) 10)
       (every (lambda (i) (digit-char-p (char text i))) '(0 1 2 3 5 6 8 9))
       (char= (char text 4) #\-) (char= (char text 7) #\-)))

(defun validate-book (values)
  "VALUES (an alist of the form's strings) checked. Returns the errors as an
   alist of (field . message), nil when there are none."
  (let ((errors '()))
    (flet ((value (name) (cdr (assoc name values :test #'string=)))
           (fail (field message) (push (cons field message) errors)))
      (let ((title (value "title"))
            (author (value "author"))
            (pages (value "pages"))
            (current (value "current_page")))
        (cond ((string= title "") (fail :title "書名を入力してください。"))
              ((> (length title) 200) (fail :title "書名は200字以内で入力してください。")))
        (cond ((string= author "") (fail :author "著者を入力してください。"))
              ((> (length author) 200) (fail :author "著者は200字以内で入力してください。")))
        (unless (or (string= pages "") (positive-integer pages))
          (fail :pages "頁数は1以上の整数で入力してください。"))
        (cond ((not
                (or (string= current "") (string= current "0")
                    (positive-integer current)))
               (fail :current-page "読んだ頁は0以上の整数で入力してください。"))
              ((and (positive-integer pages) (positive-integer current)
                    (> (positive-integer current) (positive-integer pages)))
               (fail :current-page "読んだ頁が頁数を超えています。")))
        (unless (assoc (value "status") *statuses* :test #'string=)
          (fail :status "状態を選んでください。"))
        (unless (member (value "rating") '("" "1" "2" "3" "4" "5")
                        :test #'string=)
          (fail :rating "評価は1から5の星で選んでください。"))
        (dolist (field '("started_on" "finished_on"))
          (unless (or (string= (value field) "") (date-text-p (value field)))
            (fail (column-key field) "日付は 2026-01-31 の形で入力してください。")))))
    (nreverse errors)))

(defun form-values (req)
  "The book form's fields out of the request, trimmed, missing ones as \"\"."
  (loop for field in *form-fields*
        collect
          (cons field
                (string-trim '(#\Space #\Tab #\Newline #\Return)
                             (or (param req field) "")))))

(defun book-form (req values errors &key id (status 200))
  "The form, filled with VALUES (form strings), ERRORS beside their fields.
   ID says it edits that book rather than creates one."
  (flet ((value (name) (cdr (assoc name values :test #'string=))))
    (let ((title (if id "本を編集" "本を登録")))
      (page req "book-form" title
            (list (cons :heading title)
                  (cons :action (if id (format nil "/books/~d" id) "/books"))
                  (cons :cancel (if id (format nil "/books/~d" id) "/books"))
                  (cons :editing (and id t))
                  (cons :form (loop for (name . text) in values
                                    collect (cons (column-key name) text)))
                  (cons :errors errors)
                  (cons :error-list (mapcar
                                     (lambda (e) (list (cons :message (cdr e))))
                                     errors))
                  (cons :spine (spine-style (value "title")
                                            (positive-integer (value "pages"))))
                  (cons :spine-json *spine-json*)
                  (cons :status-options (loop for (v . label) in *statuses*
                                              collect
                                                (list (cons :value v)
                                                      (cons :label label)
                                                      (cons :checked
                                                            (string= v
                                                             (value
                                                              "status"))))))
                  (cons :rating-options (loop for n from 5 downto 1
                                              collect
                                                (list (cons :n n)
                                                      (cons :checked
                                                            (string=
                                                             (princ-to-string n)
                                                             (value
                                                              "rating")))))))
            :status status
            :nav (if id "library" "new")))))

(defun book-parameters (values)
  "The form's strings as statement parameters: \"\" is NULL, numbers are
   numbers. A finished book is read to its last page."
  (flet ((value (name) (cdr (assoc name values :test #'string=)))
         (blank-null (text) (if (string= text "") nil text)))
    (let* ((pages (positive-integer (value "pages")))
           (status (value "status"))
           (current
            (if (and pages (string= status "finished"))
                pages
                (or (positive-integer (value "current_page")) 0))))
      (list (value "title") (value "author") pages current status
            (positive-integer (value "rating"))
            (blank-null (value "started_on")) (blank-null (value "finished_on"))
            (value "summary")))))

(defun new-book (req)
  (book-form req
             (loop for field in *form-fields*
                   collect (cons field (if (string= field "status") "want" "")))
             nil))

(defun create-book (req)
  (let ((values (form-values req)) (db (tiny:request-get req :db)))
    (let ((errors (validate-book values)))
      (if errors
          ;; 422 with the form again. htmx 4 swaps a 422 in like a 200, so the
          ;; errors appear without a reload either way.
          (book-form req values errors :status 422)
          (let ((id
                 (with-transaction (db)
                   (let ((id
                          (apply #'query-value db
                                 "insert into books (title, author, pages,
                                          current_page, status, rating, started_on,
                                          finished_on, summary)
                                        values ($1, $2, $3, $4, $5, $6, $7::date,
                                                $8::date, $9)
                                        returning id"
                                 (book-parameters values))))
                     (save-tags db id
                                (cdr (assoc "tags" values :test #'string=)))
                     id))))
            (redirect req (format nil "/books/~d" id)
                      (format nil "『~a』を本棚に加えました"
                       (cdr (assoc "title" values :test #'string=)))))))))

(defun edit-book (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (book (and id (find-book db id))))
    (if (null book)
        (not-found req)
        (book-form req
                   (loop for field in *form-fields*
                         for value = (cdr (assoc (column-key field) book))
                         collect
                           (cons field (if value (princ-to-string value) "")))
                   nil
                   :id id))))

(defun update-book (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (values (form-values req))
         (errors (validate-book values)))
    (cond ((null (and id (find-book db id))) (not-found req))
          (errors (book-form req values errors :id id :status 422))
          (t
           (with-transaction (db)
             (apply #'query db
                    "update books set title = $2, author = $3, pages = $4,
                         current_page = $5, status = $6, rating = $7,
                         started_on = $8::date, finished_on = $9::date,
                         summary = $10, updated_at = now()
                       where id = $1" id (book-parameters values))
             (save-tags db id (cdr (assoc "tags" values :test #'string=))))
           (redirect req (format nil "/books/~d" id) "変更を保存しました")))))

(defun delete-book (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (title
          (and id
               (query-value db
                            "delete from books where id = $1
                                         returning title" id))))
    (cond ((null title) (not-found req))
          (t
           (query db
                  "delete from tags t where not exists
                          (select 1 from book_tags bt where bt.tag_id = t.id)")
           (redirect req "/books" (format nil "『~a』を本棚から外しました" title))))))
