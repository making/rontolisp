;; Shiori: the schema, and the sample books shelved on the first start.

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
