;; Shiori: one book's page and the updates made on it in place: progress,
;; rating, notes.

;;; --- one book ------------------------------------------------------------------

(defun show-book (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (book (and id (find-book db id))))
    (if (null book)
        (not-found req)
        (page req "book" (cdr (assoc :title book))
              (list (cons :book book) (cons :editable t)
                    (cons :notes (mapcar #'decorate-note
                                         (query db
                                                "select id, kind, page, body, book_id,
                                                kind = 'quote' as quote,
                                                to_char(created_at, 'YYYY.MM.DD') as created
                                           from notes where book_id = $1
                                          order by created_at desc, id desc"
                                                id))))
              :nav "library"))))

;;; --- in-place updates: progress, rating, notes -----------------------------------
;;
;; Each answers htmx with the fragment it changed and a toast, and a plain form
;; post with a redirect back to the book.

(defun fragment (template data toast)
  (html 200 (render template data) :hx-trigger (toast-trigger toast)))

(defun record-progress (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (book (and id (find-book db id)))
         (pages (and book (cdr (assoc :pages book))))
         (text (param req "current_page"))
         ;; The form's second button says "read to the end" rather than
         ;; carrying a page: the number field is submitted either way.
         (current
          (cond ((and pages (param req "finished")) pages)
                ((equal text "0") 0)
                (t (positive-integer text)))))
    (cond ((null book) (not-found req))
          ((or (null current) (and pages (> current pages)))
           (html 422
                 (render "progress"
                         (list (cons :book book)
                               (cons :error
                                     (format nil "読んだ頁は0から~aまでの数で入力してください。"
                                             (or pages "")))))))
          (t (let* ((done (and pages (= current pages)))
                    (book
                     (progn
                       (query db
                              "update books set current_page = $2,
                                    status = case when $3 then 'finished'
                                                  when status = 'want' and $2::int > 0
                                                    then 'reading'
                                                  else status end,
                                    started_on = coalesce(started_on, current_date),
                                    finished_on = case when $3 then current_date
                                                       else finished_on end,
                                    updated_at = now()
                                  where id = $1" id current
                              (if done "true" "false"))
                       (find-book db id)))
                    (toast
                     (if done
                         (format nil "『~a』を読み終えました" (cdr (assoc :title book)))
                         (format nil "p.~d まで記録しました" current))))
               (if (htmx-p req)
                   (fragment "progress"
                             (list (cons :book book) (cons :partials t)
                                   (cons :pressed done)) toast)
                   (redirect req (format nil "/books/~d" id) toast)))))))

(defun record-rating (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (rating (positive-integer (param req "rating"))))
    (cond ((or (null id) (null (find-book db id))) (not-found req))
          ((not (and rating (<= rating 5))) (html 422 ""))
          (t
           (query db
            "update books set rating = $2, updated_at = now() where id = $1" id
            rating)
           (let ((book (find-book db id))
                 (toast (format nil "評価を ~a にしました" (subseq "★★★★★" 0 rating))))
             (if (htmx-p req)
                 (fragment "stars" (list (cons :book book) (cons :editable t))
                           toast)
                 (redirect req (format nil "/books/~d" id) toast)))))))

(defun add-note (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (kind (if (equal (param req "kind") "quote") "quote" "note"))
         (body
          (string-trim '(#\Space #\Tab #\Newline #\Return)
                       (or (param req "body") "")))
         (page-text (or (param req "page") "")))
    (cond ((or (null id) (null (find-book db id))) (not-found req))
          ((or (string= body "") (> (length body) 2000)
               (not (or (string= page-text "") (positive-integer page-text))))
           ;; The form swaps nothing on this status (its hx-status:422): it
           ;; keeps what was typed, and the browser's own `required` already
           ;; stops the common case before it is sent.
           (html 422 ""))
          (t (let ((note
                    (query-row db
                               "insert into notes (book_id, kind, page, body)
                                     values ($1, $2, $3, $4)
                                     returning id, kind, page, body, book_id,
                                       kind = 'quote' as quote,
                                       to_char(created_at, 'YYYY.MM.DD') as created"
                               id kind (positive-integer page-text) body))
                   (toast "書き留めました"))
               (if (htmx-p req)
                   (fragment "note" (decorate-note note) toast)
                   (redirect req (format nil "/books/~d#notes" id) toast)))))))

(defun delete-note (req)
  (let* ((db (tiny:request-get req :db))
         (id (positive-integer (tiny:path-parameter req :id)))
         (book-id
          (and id
               (query-value db
                            "delete from notes where id = $1
                                           returning book_id" id))))
    (cond ((null book-id) (not-found req))
          ;; An empty 200: the form's hx-swap="delete" removes the note.
          ((htmx-p req) (html 200 "" :hx-trigger (toast-trigger "削除しました")))
          (t (redirect req (format nil "/books/~d#notes" book-id) "削除しました")))))
