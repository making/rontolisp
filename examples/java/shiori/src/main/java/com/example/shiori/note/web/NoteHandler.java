// Shiori: a note written down on a book's page, and one removed. Each answers htmx with
// the piece it changed and a toast, and a plain form post with a redirect back to the
// book's notes.
package com.example.shiori.note.web;

import java.util.Map;
import java.util.Optional;

import am.ik.yavi.core.Validated;
import am.ik.yavi.core.ValueValidator;
import com.example.shiori.book.BookRepository;
import com.example.shiori.note.NewNote;
import com.example.shiori.note.Note;
import com.example.shiori.note.NoteRepository;
import com.example.shiori.web.ErrorPage;
import com.example.shiori.web.Fragment;
import com.example.shiori.web.Redirect;
import com.example.shiori.web.Requests;
import com.example.shiori.web.Templates;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class NoteHandler {

    private static final ValueValidator<Map<String, ?>, NewNote> NOTE = NewNote.SCHEMA
        .readingParameters(Requests.FORM);

    private final NoteRepository notes;

    private final BookRepository books;

    public NoteHandler(NoteRepository notes, BookRepository books) {
        this.notes = notes;
        this.books = books;
    }

    // POST /books/{id}/notes
    public ServerResponse add(ServerRequest request) throws Exception {
        Optional<Long> bookId = Requests.id(request);
        if (bookId.isEmpty() || this.books.find(bookId.get()).isEmpty()) {
            return ErrorPage.notFound(request);
        }
        return switch (NOTE.validate(request.params())) {
            case Validated.Valid<NewNote>(NewNote note) -> {
                Note added = this.notes.add(bookId.get(), note);
                yield Requests.htmx(request)
                        ? Fragment.of(Templates.NOTE, NoteView.of(added)).toast("書き留めました").response()
                        : Redirect.to("/books/" + bookId.get() + "#notes").flash("書き留めました").response(request);
            }
            // the form swaps nothing on this status (its hx-status:422): it keeps what was
            // typed, and the browser's own `required` stops the common case before it is
            // sent
            case Validated.Invalid<NewNote> refused -> Fragment.empty().status(422).response();
        };
    }

    // POST /notes/{id}/delete
    public ServerResponse delete(ServerRequest request) throws Exception {
        Optional<Long> id = Requests.id(request);
        Optional<Long> bookId = id.isEmpty() ? Optional.empty() : this.notes.delete(id.get());
        if (bookId.isEmpty()) {
            return ErrorPage.notFound(request);
        }
        if (Requests.htmx(request)) {
            // an empty 200: the form's hx-swap="delete" removes the note
            return Fragment.empty().toast("削除しました").response();
        }
        return Redirect.to("/books/" + bookId.get() + "#notes").flash("削除しました").response(request);
    }

}
