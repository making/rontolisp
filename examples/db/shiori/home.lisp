;; Shiori: the dashboard (GET /).

;;; --- the dashboard -------------------------------------------------------------

(defparameter *weekdays* #("日" "月" "火" "水" "木" "金" "土"))

(defun months-with-books (db)
  "The last twelve months, oldest first, each with the books finished in it:
   the dashboard draws a month as the pile of those books' spines."
  (let ((months
         (query db
                "select to_char(m, 'YYYY-MM') as key,
                               to_char(m, 'FMMM') || '月' as label,
                               to_char(m, 'YYYY年FMMM月') as full_label,
                               m = date_trunc('month', current_date) as current
                          from generate_series(
                                 date_trunc('month', current_date) - interval '11 months',
                                 date_trunc('month', current_date),
                                 interval '1 month') as m
                         order by m"))
        (books
         (query db
                "select id, title, pages,
                              to_char(finished_on, 'YYYY-MM') as key
                         from books
                        where status = 'finished'
                          and finished_on >= date_trunc('month', current_date)
                                             - interval '11 months'
                        order by finished_on, id")))
    (loop for month in months
          for key = (cdr (assoc :key month))
          for mine =
            (remove-if-not (lambda (b) (string= key (cdr (assoc :key b))))
                           books)
          collect
            (append (list (cons :count (if mine (length mine) ""))
                          (cons :books (loop for book in mine
                                             for title =
                                               (cdr (assoc :title book))
                                             collect
                                               (list (cons :title title)
                                                     (cons :id
                                                           (cdr
                                                            (assoc :id book)))
                                                     (cons :style
                                                           (pile-style title
                                                            (cdr
                                                             (assoc :pages
                                                                    book))))))))
                    month))))

(defun dashboard (req)
  (let* ((db (tiny:request-get req :db))
         (stats
          (query-row db
                     "select count(*)::int as total,
                                   count(*) filter (where status = 'reading')::int as reading,
                                   count(*) filter (where status = 'finished'
                                     and date_trunc('year', finished_on)
                                       = date_trunc('year', current_date))::int
                                     as finished_this_year,
                                   to_char(coalesce(sum(case when status = 'finished'
                                     then pages else current_page end), 0),
                                     'FM999,999,999') as pages_read,
                                   to_char(current_date, 'FMMM月FMDD日') as today,
                                   extract(dow from current_date)::int as dow
                              from books"))
         (tags
          (query db
                 "select t.name, count(*)::int as n
                         from tags t join book_tags bt on bt.tag_id = t.id
                        group by t.name order by t.name"))
         (notes
          (query db
                 "select n.id, n.kind, n.page, n.body, b.id as book_id,
                               b.title, n.kind = 'quote' as quote,
                               to_char(n.created_at, 'YYYY.MM.DD') as created
                          from notes n join books b on b.id = n.book_id
                         order by n.created_at desc, n.id desc limit 5"))
         (reading
          (query db
                 "select * from book_cards where status = 'reading'
                           order by updated_at desc, id desc limit 5"))
         (finished
          (query db
                 "select * from book_cards where status = 'finished'
                            order by finished_on desc, id desc limit 12")))
    (page req "home" "ホーム"
          (list (cons :stats stats)
                (cons :weekday (aref *weekdays* (cdr (assoc :dow stats))))
                (cons :has-reading (plusp (cdr (assoc :reading stats))))
                (cons :months (months-with-books db))
                (cons :tags (mapcar (lambda (tag)
                                      (cons (cons :href
                                                  (tag-href
                                                   (cdr (assoc :name tag))))
                                            tag)) tags))
                (cons :notes (mapcar #'decorate-note notes))
                (cons :show-source t)
                (cons :reading-now (mapcar #'decorate-book reading))
                (cons :recently-finished (mapcar #'decorate-book finished))))))
