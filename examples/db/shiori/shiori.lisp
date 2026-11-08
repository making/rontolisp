;; Shiori (栞) -- a reading journal: a bookshelf, reading progress, quotes and
;; notes, tags, and a dashboard that charts the books finished per month.
;;
;; The stack, every layer the real upstream library loaded unmodified:
;;
;;   cl-postgres      the PostgreSQL wire protocol; every statement prepared,
;;                    every value a $n parameter
;;   cl-mustache      the HTML: templates/*.mustache, one layout and a partial
;;                    per page and per reusable piece
;;   tiny-routes/lite the routing and the middleware, served through Clack
;;   htmx 4           in the browser (loaded from a CDN): page navigation,
;;                    incremental search and in-place updates
;;
;; htmx is an enhancement, not a requirement. Every form posts and every link
;; navigates without JavaScript; when the HX-Request header says htmx sent the
;; request, the same route answers with a fragment -- a single partial -- or with
;; an HX-Location instead of a 303. See README.md for the request flow.
;;
;; The templates and the stylesheet are read when the program is COMPILED (the
;; define-asset macro below), so the built class or component carries them and
;; runs from any directory.
;;
;; It needs a server, and reads where to find it out of DATABASE_URL
;; (../database-url.lisp parses the URL):
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;   rontolisp examples/db/shiori/shiori.lisp
;; Then open http://127.0.0.1:8080. The first start creates the schema and
;; shelves eighteen books, so there is something to look at.
;;
;; JVM class (self-contained: the server travels beside it):
;;   rontolisp examples/db/shiori/shiori.lisp -o Shiori.class && java -cp . Shiori
;; Native executable:
;;   rontolisp examples/db/shiori/shiori.lisp -o shiori --native && ./shiori
;; WASI component under wasmtime serve:
;;   rontolisp examples/db/shiori/shiori.lisp -o shiori.wasm --component --optimize
;;   wasmtime serve -S cli=y -S tcp=y -S inherit-network=y --env DATABASE_URL shiori.wasm

(ql:quickload '("clack" "tiny-routes/lite" "cl-postgres" "cl-mustache"))

(load "../database-url.lisp")

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

;;; --- the database ------------------------------------------------------------

(defun connect ()
  "A connection to the database. One per request -- see wrap-connection."
  (multiple-value-bind (database user password host port)
      (database-url-parts (uiop:getenv "DATABASE_URL"))
    (cl-postgres:open-database database user password host port)))

(defun column-key (name)
  "A column name as the keyword a template looks up: current_page becomes
   :current-page, so {{current-page}} finds it."
  (intern (substitute #\- #\_ (string-upcase name)) :keyword))

;; Rows as alists a template can read directly. SQL NULL arrives as :null,
;; which a Mustache section would treat as TRUE; nil is what it needs.
(cl-postgres:def-row-reader template-row-reader (fields)
  (loop while (cl-postgres:next-row)
        collect
          (loop for field across fields
                collect
                  (let ((value (cl-postgres:next-field field)))
                    (cons (column-key (cl-postgres:field-name field))
                          (if (eq value :null) nil value))))))

(defun query (db sql &rest params)
  "Rows of SQL with PARAMS bound to $1, $2 ... A value is never spliced into
   the text: the statement is parsed by the server first (the unnamed prepared
   statement) and the values travel separately. nil binds as SQL NULL."
  (cl-postgres:prepare-query db "" sql)
  (cl-postgres:exec-prepared db ""
                             (mapcar (lambda (p) (if (null p) :null p)) params)
                             'template-row-reader))

(defun query-row (db sql &rest params) (first (apply #'query db sql params)))

(defun query-value (db sql &rest params)
  (cdr (first (apply #'query-row db sql params))))

(defmacro with-transaction ((db) &body body)
  "BODY in one transaction: committed when it returns, rolled back when it
   exits any other way."
  (let ((done (gensym "DONE")))
    `(let ((,done nil))
       (cl-postgres:exec-query ,db "begin")
       (unwind-protect (multiple-value-prog1 (progn ,@body) (setf ,done t))
         (cl-postgres:exec-query ,db (if ,done "commit" "rollback"))))))

(defmacro with-database ((db) &body body)
  `(let ((,db (connect)))
     (unwind-protect (progn ,@body) (cl-postgres:close-database ,db))))

;;; --- schema and first-run data -----------------------------------------------

(defparameter *schema*
  '("create table if not exists books (
       id bigserial primary key,
       title text not null check (length(title) between 1 and 200),
       author text not null check (length(author) between 1 and 200),
       pages integer check (pages > 0),
       current_page integer not null default 0 check (current_page >= 0),
       status text not null default 'want'
         check (status in ('want', 'reading', 'finished', 'paused')),
       rating smallint check (rating between 1 and 5),
       started_on date,
       finished_on date,
       summary text not null default '',
       created_at timestamptz not null default now(),
       updated_at timestamptz not null default now(),
       check (pages is null or current_page <= pages))"
    "create table if not exists tags (
       id bigserial primary key,
       name text not null unique)"
    "create table if not exists book_tags (
       book_id bigint not null references books on delete cascade,
       tag_id bigint not null references tags on delete cascade,
       primary key (book_id, tag_id))"
    "create table if not exists notes (
       id bigserial primary key,
       book_id bigint not null references books on delete cascade,
       kind text not null check (kind in ('quote', 'note')),
       page integer check (page > 0),
       body text not null check (length(body) between 1 and 2000),
       created_at timestamptz not null default now())"
    "create index if not exists notes_by_book on notes (book_id, created_at desc)"
    ;; Everything a page shows of a book, derived in one place. Dropped and
    ;; recreated so that a changed column list never needs a migration.
    "drop view if exists book_cards"
    "create view book_cards as
     select b.id, b.title, b.author, b.pages, b.current_page, b.status,
            b.rating, b.summary, b.updated_at,
            case when b.pages > 0
                 then least(100, round(b.current_page * 100.0 / b.pages))::int
                 else 0 end as progress,
            to_char(b.started_on, 'YYYY-MM-DD') as started_on,
            to_char(b.finished_on, 'YYYY-MM-DD') as finished_on,
            to_char(b.started_on, 'YYYY年FMMM月FMDD日') as started_label,
            to_char(b.finished_on, 'YYYY年FMMM月FMDD日') as finished_label,
            to_char(b.finished_on, 'YYYY.MM.DD') as finished_stamp,
            coalesce((select string_agg(t.name, ',' order by t.name)
                        from book_tags bt join tags t on t.id = bt.tag_id
                       where bt.book_id = b.id), '') as tags,
            (select count(*)::int from notes n where n.book_id = b.id)
              as note_count
       from books b")
  "The schema, idempotent: this top level runs once per INSTANCE, and how long
   an instance lives is the host's business (a fresh one per request under
   wasmCloud).")

;; (title author pages current-page status rating started-days-ago
;;  finished-days-ago tags summary)
(defparameter *seed-books*
  '(("こころ" "夏目漱石" 376 376 "finished" 5 70 41 "小説,日本文学"
     "先生と私、そして遺書。明治の終わりとともに閉じてゆく一人の倫理。三度目の再読。")
    ("吾輩は猫である" "夏目漱石" 608 214 "reading" nil 18 nil "小説,日本文学,ユーモア"
     "苦沙弥先生の書斎に集う人々を、猫の目から。脱線こそが本筋。")
    ("草枕" "夏目漱石" 208 208 "finished" 4 130 112 "小説,日本文学"
     "非人情の旅。筋を追わずに、どこを開いて読んでもよい小説。")
    ("羅生門・鼻" "芥川龍之介" 184 184 "finished" 4 160 151 "短編,日本文学" "")
    ("銀河鉄道の夜" "宮沢賢治" 272 272 "finished" 5 205 190 "童話,日本文学"
     "ジョバンニとカムパネルラの夜の旅。読むたびに終着駅の意味が変わる。")
    ("檸檬" "梶井基次郎" 296 0 "want" nil nil nil "短編,日本文学" "")
    ("山月記・李陵" "中島敦" 224 224 "finished" 4 250 236 "短編,日本文学,中国"
     "漢文調の硬質な文体が心地よい。李徴の独白は何度読んでも刺さる。")
    ("方丈記" "鴨長明" 160 160 "finished" 4 300 284 "古典,エッセイ"
     "災厄の記録と、小さな庵の暮らし。八百年前のミニマリズム。")
    ("徒然草" "兼好法師" 320 88 "reading" nil 25 nil "古典,エッセイ" "")
    ("Structure and Interpretation of Computer Programs"
     "Harold Abelson, Gerald Jay Sussman" 657 312 "reading" nil 60 nil
     "プログラミング,Lisp,計算機科学" "第3章、ストリームまで。演習は REPL で一つずつ。")
    ("On Lisp" "Paul Graham" 413 413 "finished" 5 120 74 "プログラミング,Lisp"
     "マクロで言語を育てる本。ボトムアップ設計の考え方が全編を貫く。")
    ("Paradigms of Artificial Intelligence Programming" "Peter Norvig" 946 388
     "paused" nil 220 nil "プログラミング,Lisp,AI" "GPS と ELIZA まで。Prolog の章から再開する予定。")
    ("Common Lisp: The Language" "Guy L. Steele Jr." 1029 540 "reading" nil 140
     nil "プログラミング,Lisp" "通読ではなく辞書として。format と loop の章に付箋。")
    ("Gödel, Escher, Bach" "Douglas Hofstadter" 777 0 "want" nil nil nil
     "数学,思想,計算機科学" "")
    ("Walden" "Henry David Thoreau" 352 352 "finished" 4 340 322 "エッセイ,自然" "")
    ("Pride and Prejudice" "Jane Austen" 432 432 "finished" 5 30 9 "小説,英文学"
     "会話の応酬だけで人物が立ち上がる。エリザベスの機知。")
    ("Moby-Dick" "Herman Melville" 720 140 "paused" nil 190 nil "小説,英文学,海"
     "捕鯨の博物誌の章で一休み。")
    ("The Art of Computer Programming, Vol. 1" "Donald E. Knuth" 672 0 "want"
     nil nil nil "計算機科学,数学" "")))

;; (title kind page days-ago body)
(defparameter *seed-notes*
  '(("こころ" "quote" 98 45 "精神的に向上心のないものは馬鹿だ。")
    ("こころ" "note" nil 42 "Kの言葉が、後半で先生自身に返ってくる。この構造に初読では気づかなかった。")
    ("吾輩は猫である" "quote" 5 17 "吾輩は猫である。名前はまだ無い。")
    ("草枕" "quote" 5 125 "智に働けば角が立つ。情に棹させば流される。意地を通せば窮屈だ。とかくに人の世は住みにくい。")
    ("方丈記" "quote" 9 296 "ゆく河の流れは絶えずして、しかも、もとの水にあらず。")
    ("徒然草" "quote" 11 24
     "つれづれなるままに、日暮らし硯にむかひて、心にうつりゆくよしなし事を、そこはかとなく書きつくれば、あやしうこそものぐるほしけれ。")
    ("山月記・李陵" "quote" 20 240 "臆病な自尊心と、尊大な羞恥心")
    ("銀河鉄道の夜" "quote" 241 192 "ほんとうのさいわいは一体何だろう。")
    ("Walden" "quote" 90 330
     "I went to the woods because I wished to live deliberately.")
    ("Pride and Prejudice" "quote" 1 28
     "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.")
    ("Moby-Dick" "quote" 1 188 "Call me Ishmael.")
    ("Structure and Interpretation of Computer Programs" "note" 312 3
     "ストリームは遅延評価されたリスト。3.5節の素数の篩を REPL で動かしてみた。")
    ("On Lisp" "note" 82 80 "第8章「いつマクロを使うか」。関数で書けるなら関数で、という線引きが明快。")
    ("Common Lisp: The Language" "note" 540 6 "~{~a~^, ~} の組み合わせを覚えておくこと。")))

(defun save-tags (db book-id tags)
  "Replace the tags of BOOK-ID by TAGS, a comma-separated string. Unknown tag
   names are created; tags no book uses any more are removed."
  (query db "delete from book_tags where book_id = $1" book-id)
  (query db
         "insert into tags (name)
             select distinct trim(t) from unnest(string_to_array($1, ',')) t
              where trim(t) <> ''
             on conflict (name) do nothing" tags)
  (query db
         "insert into book_tags (book_id, tag_id)
             select $1, id from tags
              where name in (select trim(t) from unnest(string_to_array($2, ',')) t)"
         book-id tags)
  (query db
         "delete from tags t
              where not exists (select 1 from book_tags bt where bt.tag_id = t.id)"))

(defun seed (db)
  "Shelve the sample books, dated relative to today so that the dashboard's
   chart of the last twelve months always has something in it."
  (dolist (book *seed-books*)
    (destructuring-bind (title author pages current status rating started
                               finished tags summary) book
      (let ((id
             (query-value db
                          "insert into books (title, author, pages, current_page,
                                status, rating, started_on, finished_on, summary,
                                updated_at)
                              values ($1, $2, $3, $4, $5, $6,
                                current_date - $7::int, current_date - $8::int, $9,
                                now() - make_interval(days => coalesce($8::int, $7::int, 400)))
                              returning id" title author pages current status
                          rating started finished summary)))
        (save-tags db id tags))))
  (dolist (note *seed-notes*)
    (destructuring-bind (title kind page days-ago body) note
      (query db
             "insert into notes (book_id, kind, page, body, created_at)
                 select id, $2, $3, $4, now() - make_interval(days => $5::int)
                   from books where title = $1" title kind page body
             days-ago))))

;; Two instances starting at once must not both create the schema or both seed
;; it: the advisory lock makes the second wait for the first, and then find the
;; books already there.
(with-database (db)
  (with-transaction (db)
    (query db "select pg_advisory_xact_lock(7457)")
    ;; "drop view if exists" on a fresh database is a NOTICE, not news.
    (query db "set local client_min_messages = warning")
    (dolist (statement *schema*) (cl-postgres:exec-query db statement))
    (when (zerop (query-value db "select count(*)::int from books"))
      (seed db))))

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
   (layout.mustache) computes the same in JavaScript, out of *spine-json*."
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

;;; --- requests and responses ----------------------------------------------------

(defun param (req name)
  "The decoded value of NAME in the query string or the form body."
  (cdr (assoc name (tiny:request-get req :params) :test #'string=)))

(defun htmx-p (req) (tiny:request-header req "hx-request"))

(defun htmx-target-id (req)
  "The id of the element htmx will swap the answer into. htmx 4 names the
   target as tag#id (div#shelf); only the id is the page's contract."
  (let* ((target (tiny:request-header req "hx-target"))
         (hash (and target (position #\# target))))
    (and hash (rontolisp:url-decode (subseq target (1+ hash))))))

(defun partial-request-p (req)
  "True when htmx swaps the answer into part of the page rather than the
   whole body -- a fragment is expected."
  (equal (tiny:request-header req "hx-request-type") "partial"))

(defun positive-integer (text)
  "TEXT as a positive integer, or nil when it is not exactly one."
  (when (and (stringp text) (plusp (length text)))
    (multiple-value-bind (n end) (parse-integer text :junk-allowed t)
      (when (and n (= end (length text)) (plusp n)) n))))

(defun html (status body &rest headers)
  (tiny:make-response :status status
   :headers (list* :content-type "text/html; charset=utf-8" headers)
   :body body))

(defun flash-cookie (message)
  (format nil "shiori_flash=~a; Path=/; Max-Age=60; HttpOnly; SameSite=Lax"
          (rontolisp:url-encode message)))

(defun toast-trigger (message)
  "An HX-Trigger header value raising the page's toast event. A header is
   ISO-8859-1, so the message travels URL-encoded and the page decodes it.
   The event is raised on the body: htmx raises it after the swap, by which
   time the form that sent the request may have been swapped out."
  (format nil "{\"toast\":{\"value\":\"~a\",\"target\":\"body\"}}"
          (rontolisp:url-encode message)))

(defun redirect (req location &optional flash)
  "After a POST. A browser gets 303 + Location, so a reload does not post
   again. htmx follows a redirect inside its fetch, where it cannot see it, so it
   gets HX-Location instead and navigates itself (pushing the new URL). Either
   way the message rides in a cookie the next page shows once."
  (let ((cookie (and flash (list :set-cookie (flash-cookie flash)))))
    (if (htmx-p req)
        (tiny:make-response :status 200
                            :headers (list* :hx-location location cookie)
                            :body "")
        (tiny:make-response :status 303
                            :headers (list* :location location cookie)))))

(defun take-flash (req)
  "The flash message the previous response left, if any."
  (let ((cookies (tiny:request-header req "cookie")))
    (when cookies
      (loop for pair in (uiop:split-string cookies :separator ";")
            for trimmed = (string-left-trim " " pair)
            when
              (and (> (length trimmed) 13)
                   (string= "shiori_flash=" trimmed :end2 13))
            return (rontolisp:url-decode (subseq trimmed 13))))))

(defun page (req template title data &key (status 200) (nav template))
  "A whole page: TEMPLATE inside the layout. A flash message is shown once and
   its cookie cleared in the same response. When htmx expected a fragment -- an
   in-place update that met a missing book or an error -- the page is swapped
   in for the whole body instead of into the fragment's slot."
  (let* ((flash (take-flash req))
         (body
          (render "layout"
                  (append (list (cons :page template) (cons :title title)
                                (cons :flash flash)
                                (cons :stylesheet *stylesheet-url*)
                                (cons :nav-home (string= nav "home"))
                                (cons :nav-library (string= nav "library"))
                                (cons :nav-new (string= nav "new"))) data))))
    (apply #'html status body
           (append
            (and flash
                 (list
                  :set-cookie
                  "shiori_flash=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"))
            (and (partial-request-p req)
                 (list :hx-retarget "body" :hx-reswap "innerHTML"))))))

(defun error-page (req status heading message)
  (page req "error" heading
   (list (cons :status status) (cons :heading heading) (cons :message message))
   :status status
   :nav nil))

(defun not-found (req)
  (error-page req 404 "この頁は落丁しています"
              (format nil "~a という頁はありません。本棚から探してください。" (tiny:path-info req))))

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

;;; --- static files ----------------------------------------------------------------

(defun static (content-type text cache-control)
  (tiny:make-response :status 200
   :headers (list :content-type content-type :cache-control cache-control)
   :body text))

;;; --- middleware --------------------------------------------------------------------

(defun wrap-params (handler)
  "Middleware: the query string and an url-encoded form body, decoded, as one
   alist under :params. (tiny-routes' own query middleware does not decode
   percent escapes, and leaves form bodies alone.)"
  (lambda (req)
    (funcall handler
             (tiny:request-append req
                                  :params
                                  (append
                                   (rontolisp:query-params
                                    (tiny:query-string req))
                                   (let ((type (tiny:content-type req)))
                                     (when (and type
                                            (search
                                             "application/x-www-form-urlencoded"
                                             type))
                                       (rontolisp:query-params
                                        (tiny:request-body req)))))))))

(defun wrap-connection (handler)
  "Middleware: one connection per request, as :db, closed even when the handler
   signals. Installed post-match, so a request bound for the 404 page or the
   stylesheet opens none."
  (lambda (req)
    (with-database (db) (funcall handler (tiny:request-append req :db db)))))

(defun wrap-errors (handler)
  "Middleware: anything a handler did not foresee becomes the 500 page, and the
   condition goes to the log rather than to the visitor."
  (lambda (req)
    (handler-case (funcall handler req)
      (error (c)
        (format *error-output* "~&~a ~a: ~a~%" (tiny:request-method req)
                (tiny:path-info req) c)
        (error-page req 500 "頁を開けませんでした" "データベースに接続できるか確かめてから、もう一度開いてください。")))))

;;; --- routes ------------------------------------------------------------------------

;; Literal paths before parameterized ones: /books/new would otherwise match
;; /books/:id.
(tiny:define-routes *app-routes*
  (tiny:define-get "/" (req) (dashboard req))
  (tiny:define-get "/books" (req) (library req))
  (tiny:define-post "/books" (req) (create-book req))
  (tiny:define-get "/books/new" (req) (new-book req))
  (tiny:define-get "/books/:id" (req) (show-book req))
  (tiny:define-post "/books/:id" (req) (update-book req))
  (tiny:define-get "/books/:id/edit" (req) (edit-book req))
  (tiny:define-post "/books/:id/delete" (req) (delete-book req))
  (tiny:define-post "/books/:id/progress" (req) (record-progress req))
  (tiny:define-post "/books/:id/rating" (req) (record-rating req))
  (tiny:define-post "/books/:id/notes" (req) (add-note req))
  (tiny:define-post "/notes/:id/delete" (req) (delete-note req)))

(tiny:define-routes *static-routes*
  (tiny:define-get "/static/app.css" (req)
    (static "text/css; charset=utf-8" *stylesheet*
            ;; Its URL changes with its text (*stylesheet-url*).
            "public, max-age=31536000, immutable"))
  (tiny:define-get "/favicon.svg" (req)
    (static "image/svg+xml" *favicon* "public, max-age=86400"))
  (tiny:define-any "*" (req) (not-found req)))

(defparameter *app*
  (tiny:pipe (tiny:routes (tiny:pipe *app-routes*
                           (tiny:wrap-post-match-middleware #'wrap-connection))
                          *static-routes*) (wrap-errors) (wrap-params)
             (tiny:wrap-request-body :max-bytes 65536)))

(clack:clackup *app*
               :server :rontolisp
               :address "0.0.0.0"
               :port 8080
               :use-thread nil)
