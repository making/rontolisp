// Shiori: the books, their tags and their progress, as the database keeps them.
package com.example.shiori.book;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.shiori.book.BookSearch.Sort;
import com.example.shiori.jdbc.Database;
import com.example.shiori.jdbc.JdbcClient;

// A book is read from book_cards, the books with their tags (Schema).
public final class BookRepository {

    private final Database database;

    public BookRepository(Database database) {
        this.database = database;
    }

    public Optional<Book> find(long id) throws SQLException {
        return this.database.sql("select * from book_cards where id = ?").params(id).first(BookRepository::book);
    }

    // --- the library ---------------------------------------------------------------------

    // How many books a search finds.
    public int count(BookSearch search) throws SQLException {
        Where where = where(search);
        return this.database.sql("select count(*)::int from book_cards" + where.clause())
            .params(where.parameters().toArray())
            .first(row -> row.getInt(1))
            .orElseThrow();
    }

    // The books on the search's page, in its order.
    public List<Book> find(BookSearch search) throws SQLException {
        Where where = where(search);
        List<Object> parameters = new ArrayList<>(where.parameters());
        parameters.add(BookSearch.PER_PAGE);
        parameters.add(search.offset());
        return this.database
            .sql("select * from book_cards" + where.clause() + " order by " + orderBy(search.sort())
                    + " limit ? offset ?")
            .params(parameters.toArray())
            .list(BookRepository::book);
    }

    // How many books stand where, every status there (none counted 0).
    public Map<Status, Integer> countByStatus() throws SQLException {
        Map<Status, Integer> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status, 0);
        }
        List<Map.Entry<Status, Integer>> counted = this.database
            .sql("select status, count(*)::int as n from books group by status")
            .list(row -> Map.entry(Status.of(row.getString("status")), row.getInt("n")));
        for (Map.Entry<Status, Integer> count : counted) {
            counts.put(count.getKey(), count.getValue());
        }
        return counts;
    }

    private record Where(String clause, List<Object> parameters) {
    }

    private static Where where(BookSearch search) {
        List<String> clauses = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (search.status() != null) {
            clauses.add("status = ?");
            parameters.add(search.status().value());
        }
        if (search.tag() != null) {
            clauses.add("""
                    exists (select 1 from book_tags bt join tags t on t.id = bt.tag_id
                             where bt.book_id = book_cards.id and t.name = ?)""");
            parameters.add(search.tag());
        }
        if (!search.q().isEmpty()) {
            // strpos, not like: what the visitor typed is matched literally, so a % or an _
            // in it is just a character
            clauses.add("strpos(lower(title || ' ' || author || ' ' || tags), lower(?)) > 0");
            parameters.add(search.q());
        }
        return new Where(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), parameters);
    }

    // An ORDER BY cannot be a parameter. It is spliced into the statement, which is safe
    // only because it is always one of these texts, chosen by the sort, never the
    // request's text.
    private static String orderBy(Sort sort) {
        return switch (sort) {
            case UPDATED -> "updated_at desc, id desc";
            case TITLE -> "title asc, id asc";
            case AUTHOR -> "author asc, title asc";
            case RATING -> "rating desc nulls last, updated_at desc";
        };
    }

    // --- the dashboard -------------------------------------------------------------------

    public ReadingStats stats() throws SQLException {
        return this.database.sql("""
                select count(*)::int as total,
                       count(*) filter (where status = 'reading')::int as reading,
                       count(*) filter (where status = 'finished'
                         and date_trunc('year', finished_on) = date_trunc('year', current_date))::int
                         as finished_this_year,
                       coalesce(sum(case when status = 'finished' then pages else current_page end), 0)
                         as pages_read,
                       current_date as today
                  from books""")
            .first(row -> new ReadingStats(row.getInt("total"), row.getInt("reading"),
                    row.getInt("finished_this_year"), row.getLong("pages_read"),
                    row.getObject("today", LocalDate.class)))
            .orElseThrow();
    }

    // The books being read, the latest touched first.
    public List<Book> readingNow(int limit) throws SQLException {
        return this.database.sql("""
                select * from book_cards where status = 'reading'
                 order by updated_at desc, id desc limit ?""").params(limit).list(BookRepository::book);
    }

    // The books finished, the latest first.
    public List<Book> recentlyFinished(int limit) throws SQLException {
        return this.database.sql("""
                select * from book_cards where status = 'finished'
                 order by finished_on desc, id desc limit ?""").params(limit).list(BookRepository::book);
    }

    // The books finished on a day or after it, in the order they were finished.
    public List<Book> finishedSince(LocalDate day) throws SQLException {
        return this.database.sql("""
                select * from book_cards where status = 'finished' and finished_on >= ?
                 order by finished_on, id""").params(day).list(BookRepository::book);
    }

    public List<TagCount> tagCounts() throws SQLException {
        return this.database.sql("""
                select t.name, count(*)::int as n
                  from tags t join book_tags bt on bt.tag_id = t.id
                 group by t.name order by t.name""").list(row -> new TagCount(row.getString("name"), row.getInt("n")));
    }

    // --- changes -------------------------------------------------------------------------

    // The new book's id.
    public long create(BookDetails book) throws SQLException {
        return this.database.inTransaction(jdbc -> {
            long id = jdbc.sql("""
                    insert into books (title, author, pages, current_page, status, rating, started_on,
                                       finished_on, summary)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id""")
                .params(book.title(), book.author(), book.pages(), book.pageRead(), book.status().value(),
                        book.rating(), book.startedOn(), book.finishedOn(), book.summary())
                .first(row -> row.getLong(1))
                .orElseThrow();
            replaceTags(jdbc, id, book.tags());
            return id;
        });
    }

    public void update(long id, BookDetails book) throws SQLException {
        this.database.inTransaction(jdbc -> {
            jdbc.sql("""
                    update books set title = ?, author = ?, pages = ?, current_page = ?, status = ?,
                                     rating = ?, started_on = ?, finished_on = ?, summary = ?,
                                     updated_at = now()
                     where id = ?""")
                .params(book.title(), book.author(), book.pages(), book.pageRead(), book.status().value(),
                        book.rating(), book.startedOn(), book.finishedOn(), book.summary(), id)
                .update();
            replaceTags(jdbc, id, book.tags());
            return null;
        });
    }

    // The title of the book removed, if there was one. Its notes go with it, and the tags
    // no other book carries.
    public Optional<String> delete(long id) throws SQLException {
        Optional<String> title = this.database.sql("delete from books where id = ? returning title")
            .params(id)
            .first(row -> row.getString(1));
        if (title.isPresent()) {
            this.database.sql("""
                    delete from tags t
                     where not exists (select 1 from book_tags bt where bt.tag_id = t.id)""").update();
        }
        return title;
    }

    // Reading to a page: a book read to its end is finished, and one wanted is being read
    // once a page is.
    public void recordProgress(Book book, int page) throws SQLException {
        boolean done = book.endsAt(page);
        this.database.sql("""
                update books set current_page = ?,
                       status = case when ? then 'finished'
                                     when status = 'want' and ? > 0 then 'reading'
                                     else status end,
                       started_on = coalesce(started_on, current_date),
                       finished_on = case when ? then current_date else finished_on end,
                       updated_at = now()
                 where id = ?""").params(page, done, page, done, book.id()).update();
    }

    public void rate(long id, Rating rating) throws SQLException {
        this.database.sql("update books set rating = ?, updated_at = now() where id = ?")
            .params(rating.stars(), id)
            .update();
    }

    // Replaces the tags of a book by those of a comma-separated text, in the caller's
    // transaction. A tag no book has yet is made; one no book carries any more is removed.
    public static void replaceTags(JdbcClient jdbc, long bookId, String tags) throws SQLException {
        jdbc.sql("delete from book_tags where book_id = ?").params(bookId).update();
        jdbc.sql("""
                insert into tags (name)
                select distinct trim(t) from unnest(string_to_array(?, ',')) t
                 where trim(t) <> ''
                on conflict (name) do nothing""").params(tags).update();
        jdbc.sql("""
                insert into book_tags (book_id, tag_id)
                select ?, id from tags
                 where name in (select trim(t) from unnest(string_to_array(?, ',')) t)""")
            .params(bookId, tags)
            .update();
        jdbc.sql("""
                delete from tags t
                 where not exists (select 1 from book_tags bt where bt.tag_id = t.id)""").update();
    }

    private static Book book(ResultSet row) throws SQLException {
        List<String> tags = new ArrayList<>();
        for (String name : row.getString("tags").split(",")) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return new Book(row.getLong("id"), row.getString("title"), row.getString("author"),
                row.getObject("pages", Integer.class), row.getInt("current_page"), Status.of(row.getString("status")),
                row.getObject("rating", Integer.class), row.getString("summary"),
                row.getObject("started_on", LocalDate.class), row.getObject("finished_on", LocalDate.class),
                List.copyOf(tags));
    }

}
