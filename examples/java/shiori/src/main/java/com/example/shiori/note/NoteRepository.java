// Shiori: the notes, as the database keeps them.
package com.example.shiori.note;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.shiori.jdbc.Database;

public final class NoteRepository {

    // A note with its book's title, and the day it was written in the database's time zone.
    private static final String COLUMNS = """
            n.id, n.kind, n.page, n.body, n.book_id, b.title, n.created_at::date as written_on""";

    private final Database database;

    public NoteRepository(Database database) {
        this.database = database;
    }

    // The latest notes of every book.
    public List<Note> recent(int limit) throws SQLException {
        return this.database.sql("select " + COLUMNS + """

                  from notes n join books b on b.id = n.book_id
                 order by n.created_at desc, n.id desc limit ?""").params(limit).list(NoteRepository::note);
    }

    // A book's notes, the latest first.
    public List<Note> ofBook(long bookId) throws SQLException {
        return this.database.sql("select " + COLUMNS + """

                  from notes n join books b on b.id = n.book_id
                 where n.book_id = ?
                 order by n.created_at desc, n.id desc""").params(bookId).list(NoteRepository::note);
    }

    // The note written down, without its book's title.
    public Note add(long bookId, NewNote note) throws SQLException {
        return this.database.sql("""
                insert into notes (book_id, kind, page, body) values (?, ?, ?, ?)
                returning id, kind, page, body, book_id, null::text as title,
                          created_at::date as written_on""")
            .params(bookId, note.kind().value(), note.page(), note.body())
            .first(NoteRepository::note)
            .orElseThrow();
    }

    // The book of the note removed, if there was one.
    public Optional<Long> delete(long id) throws SQLException {
        return this.database.sql("delete from notes where id = ? returning book_id")
            .params(id)
            .first(row -> row.getLong(1));
    }

    private static Note note(ResultSet row) throws SQLException {
        return new Note(row.getLong("id"), Note.Kind.of(row.getString("kind")), row.getObject("page", Integer.class),
                row.getString("body"), row.getLong("book_id"), row.getString("title"),
                row.getObject("written_on", LocalDate.class));
    }

}
