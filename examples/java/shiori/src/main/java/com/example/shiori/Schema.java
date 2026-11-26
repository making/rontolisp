// Shiori: the schema, and the sample books shelved on the first start.
package com.example.shiori;

import java.sql.SQLException;
import java.util.List;

import com.example.shiori.book.BookRepository;
import com.example.shiori.jdbc.Database;
import com.example.shiori.jdbc.JdbcClient;

final class Schema {

    private Schema() {
    }

    // Idempotent: the program's start runs it, once per process and under wasmtime serve
    // once per instance, which lives as long as the host keeps it.
    private static final List<String> STATEMENTS = List.of("""
            create table if not exists books (
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
              check (pages is null or current_page <= pages))""", """
            create table if not exists tags (
              id bigserial primary key,
              name text not null unique)""", """
            create table if not exists book_tags (
              book_id bigint not null references books on delete cascade,
              tag_id bigint not null references tags on delete cascade,
              primary key (book_id, tag_id))""", """
            create table if not exists notes (
              id bigserial primary key,
              book_id bigint not null references books on delete cascade,
              kind text not null check (kind in ('quote', 'note')),
              page integer check (page > 0),
              body text not null check (length(body) between 1 and 2000),
              created_at timestamptz not null default now())""",
            "create index if not exists notes_by_book on notes (book_id, created_at desc)",
            // A book with its tags, which a search matches too. Dropped and made again so
            // that a changed column list never needs a migration.
            "drop view if exists book_cards", """
            create view book_cards as
            select b.id, b.title, b.author, b.pages, b.current_page, b.status, b.rating, b.summary,
                   b.started_on, b.finished_on, b.updated_at,
                   coalesce((select string_agg(t.name, ',' order by t.name)
                               from book_tags bt join tags t on t.id = bt.tag_id
                              where bt.book_id = b.id), '') as tags
              from books b""");

    // A book of the first start, dated so many days before today.
    private record SampleBook(String title, String author, int pages, int currentPage, String status, Integer rating,
            Integer startedDaysAgo, Integer finishedDaysAgo, String tags, String summary) {
    }

    private record SampleNote(String title, String kind, Integer page, int daysAgo, String body) {
    }

    private static final List<SampleBook> BOOKS = List.of(
            new SampleBook("こころ", "夏目漱石", 376, 376, "finished", 5, 70, 41, "小説,日本文学",
                    "先生と私、そして遺書。明治の終わりとともに閉じてゆく一人の倫理。三度目の再読。"),
            new SampleBook("吾輩は猫である", "夏目漱石", 608, 214, "reading", null, 18, null, "小説,日本文学,ユーモア",
                    "苦沙弥先生の書斎に集う人々を、猫の目から。脱線こそが本筋。"),
            new SampleBook("草枕", "夏目漱石", 208, 208, "finished", 4, 130, 112, "小説,日本文学",
                    "非人情の旅。筋を追わずに、どこを開いて読んでもよい小説。"),
            new SampleBook("羅生門・鼻", "芥川龍之介", 184, 184, "finished", 4, 160, 151, "短編,日本文学", ""),
            new SampleBook("銀河鉄道の夜", "宮沢賢治", 272, 272, "finished", 5, 205, 190, "童話,日本文学",
                    "ジョバンニとカムパネルラの夜の旅。読むたびに終着駅の意味が変わる。"),
            new SampleBook("檸檬", "梶井基次郎", 296, 0, "want", null, null, null, "短編,日本文学", ""),
            new SampleBook("山月記・李陵", "中島敦", 224, 224, "finished", 4, 250, 236, "短編,日本文学,中国",
                    "漢文調の硬質な文体が心地よい。李徴の独白は何度読んでも刺さる。"),
            new SampleBook("方丈記", "鴨長明", 160, 160, "finished", 4, 300, 284, "古典,エッセイ",
                    "災厄の記録と、小さな庵の暮らし。八百年前のミニマリズム。"),
            new SampleBook("徒然草", "兼好法師", 320, 88, "reading", null, 25, null, "古典,エッセイ", ""),
            new SampleBook("Structure and Interpretation of Computer Programs", "Harold Abelson, Gerald Jay Sussman",
                    657, 312, "reading", null, 60, null, "プログラミング,Lisp,計算機科学", "第3章、ストリームまで。演習は REPL で一つずつ。"),
            new SampleBook("On Lisp", "Paul Graham", 413, 413, "finished", 5, 120, 74, "プログラミング,Lisp",
                    "マクロで言語を育てる本。ボトムアップ設計の考え方が全編を貫く。"),
            new SampleBook("Paradigms of Artificial Intelligence Programming", "Peter Norvig", 946, 388, "paused",
                    null, 220, null, "プログラミング,Lisp,AI", "GPS と ELIZA まで。Prolog の章から再開する予定。"),
            new SampleBook("Common Lisp: The Language", "Guy L. Steele Jr.", 1029, 540, "reading", null, 140, null,
                    "プログラミング,Lisp", "通読ではなく辞書として。format と loop の章に付箋。"),
            new SampleBook("Gödel, Escher, Bach", "Douglas Hofstadter", 777, 0, "want", null, null, null,
                    "数学,思想,計算機科学", ""),
            new SampleBook("Walden", "Henry David Thoreau", 352, 352, "finished", 4, 340, 322, "エッセイ,自然", ""),
            new SampleBook("Pride and Prejudice", "Jane Austen", 432, 432, "finished", 5, 30, 9, "小説,英文学",
                    "会話の応酬だけで人物が立ち上がる。エリザベスの機知。"),
            new SampleBook("Moby-Dick", "Herman Melville", 720, 140, "paused", null, 190, null, "小説,英文学,海",
                    "捕鯨の博物誌の章で一休み。"),
            new SampleBook("The Art of Computer Programming, Vol. 1", "Donald E. Knuth", 672, 0, "want", null, null,
                    null, "計算機科学,数学", ""));

    private static final List<SampleNote> NOTES = List.of(
            new SampleNote("こころ", "quote", 98, 45, "精神的に向上心のないものは馬鹿だ。"),
            new SampleNote("こころ", "note", null, 42, "Kの言葉が、後半で先生自身に返ってくる。この構造に初読では気づかなかった。"),
            new SampleNote("吾輩は猫である", "quote", 5, 17, "吾輩は猫である。名前はまだ無い。"),
            new SampleNote("草枕", "quote", 5, 125, "智に働けば角が立つ。情に棹させば流される。意地を通せば窮屈だ。とかくに人の世は住みにくい。"),
            new SampleNote("方丈記", "quote", 9, 296, "ゆく河の流れは絶えずして、しかも、もとの水にあらず。"),
            new SampleNote("徒然草", "quote", 11, 24,
                    "つれづれなるままに、日暮らし硯にむかひて、心にうつりゆくよしなし事を、そこはかとなく書きつくれば、あやしうこそものぐるほしけれ。"),
            new SampleNote("山月記・李陵", "quote", 20, 240, "臆病な自尊心と、尊大な羞恥心"),
            new SampleNote("銀河鉄道の夜", "quote", 241, 192, "ほんとうのさいわいは一体何だろう。"),
            new SampleNote("Walden", "quote", 90, 330, "I went to the woods because I wished to live deliberately."),
            new SampleNote("Pride and Prejudice", "quote", 1, 28,
                    "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife."),
            new SampleNote("Moby-Dick", "quote", 1, 188, "Call me Ishmael."),
            new SampleNote("Structure and Interpretation of Computer Programs", "note", 312, 3,
                    "ストリームは遅延評価されたリスト。3.5節の素数の篩を REPL で動かしてみた。"),
            new SampleNote("On Lisp", "note", 82, 80, "第8章「いつマクロを使うか」。関数で書けるなら関数で、という線引きが明快。"),
            new SampleNote("Common Lisp: The Language", "note", 540, 6, "~{~a~^, ~} の組み合わせを覚えておくこと。"));

    // Two instances starting at once must not both make the schema or both shelve the
    // samples: the advisory lock makes the second wait for the first, and then find the
    // books there.
    static void prepare(Database database) throws SQLException {
        database.inTransaction(jdbc -> {
            jdbc.sql("select pg_advisory_xact_lock(7457)").execute();
            // "drop view if exists" on a fresh database is a NOTICE, not news
            jdbc.sql("set local client_min_messages = warning").execute();
            for (String sql : STATEMENTS) {
                jdbc.sql(sql).execute();
            }
            if (jdbc.sql("select count(*)::int from books").first(row -> row.getInt(1)).orElseThrow() == 0) {
                seed(jdbc);
            }
            return null;
        });
    }

    // The samples are dated relative to today, so the dashboard's chart of the last
    // twelve months always has something in it.
    private static void seed(JdbcClient jdbc) throws SQLException {
        for (SampleBook book : BOOKS) {
            long id = jdbc.sql("""
                    insert into books (title, author, pages, current_page, status, rating, started_on,
                                       finished_on, summary, updated_at)
                    values (?, ?, ?, ?, ?, ?, current_date - ?::int, current_date - ?::int, ?,
                            now() - make_interval(days => coalesce(?::int, ?::int, 400)))
                    returning id""")
                .params(book.title(), book.author(), book.pages(), book.currentPage(), book.status(), book.rating(),
                        book.startedDaysAgo(), book.finishedDaysAgo(), book.summary(), book.finishedDaysAgo(),
                        book.startedDaysAgo())
                .first(row -> row.getLong(1))
                .orElseThrow();
            BookRepository.replaceTags(jdbc, id, book.tags());
        }
        for (SampleNote note : NOTES) {
            jdbc.sql("""
                    insert into notes (book_id, kind, page, body, created_at)
                    select id, ?, ?, ?, now() - make_interval(days => ?::int)
                      from books where title = ?""")
                .params(note.kind(), note.page(), note.body(), note.daysAgo(), note.title())
                .update();
        }
    }

}
