// Shiori: one book's page and the changes made on it in place: progress and rating. Each
// change answers htmx with the piece it changed and a toast, and a plain form post with a
// redirect back to the book.
package com.example.shiori.book.web;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import am.ik.yavi.core.Validated;
import am.ik.yavi.core.ValueValidator;
import com.example.shiori.book.Book;
import com.example.shiori.book.BookRepository;
import com.example.shiori.book.Progress;
import com.example.shiori.book.Rating;
import com.example.shiori.note.NoteRepository;
import com.example.shiori.note.web.NoteView;
import com.example.shiori.web.ErrorPage;
import com.example.shiori.web.Fragment;
import com.example.shiori.web.Nav;
import com.example.shiori.web.Page;
import com.example.shiori.web.Redirect;
import com.example.shiori.web.Requests;
import com.example.shiori.web.Templates;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class BookHandler {

    private static final ValueValidator<Map<String, ?>, Progress> PROGRESS = Progress.SCHEMA
        .readingParameters(Requests.FORM);

    private static final ValueValidator<Map<String, ?>, Rating> RATING = Rating.SCHEMA
        .readingParameters(Requests.FORM);

    private final BookRepository books;

    private final NoteRepository notes;

    public BookHandler(BookRepository books, NoteRepository notes) {
        this.books = books;
        this.notes = notes;
    }

    // book.mustache: the stars can be changed there.
    record BookPage(BookCard book, boolean editable, List<NoteView> notes) {
    }

    // progress.mustache: answering htmx, it carries the pieces of the page the change
    // moves too (partials), and the stamp is pressed when the book was just finished.
    record ProgressPiece(BookCard book, boolean partials, boolean pressed, String error) {
    }

    // stars.mustache
    record StarsPiece(BookCard book, boolean editable) {
    }

    // GET /books/{id}
    public ServerResponse show(ServerRequest request) throws Exception {
        Optional<Book> book = book(request);
        if (book.isEmpty()) {
            return ErrorPage.notFound(request);
        }
        List<NoteView> notes = NoteView.of(this.notes.ofBook(book.get().id()));
        return Page.of(Templates.BOOK, new BookPage(BookCard.of(book.get()), true, notes))
            .title(book.get().title())
            .nav(Nav.LIBRARY)
            .response(request);
    }

    // POST /books/{id}/progress
    public ServerResponse progress(ServerRequest request) throws Exception {
        Optional<Book> found = book(request);
        if (found.isEmpty()) {
            return ErrorPage.notFound(request);
        }
        Book book = found.get();
        Integer page = switch (PROGRESS.validate(request.params())) {
            case Validated.Valid<Progress>(Progress sent) -> sent.pageRead(book.pages());
            case Validated.Invalid<Progress> refused -> null;
        };
        if (page == null || !book.hasPage(page)) {
            String error = "読んだ頁は0から" + (book.pages() == null ? "" : book.pages()) + "までの数で入力してください。";
            return Fragment.of(Templates.PROGRESS, new ProgressPiece(BookCard.of(book), false, false, error))
                .status(422)
                .response();
        }
        boolean done = book.endsAt(page);
        this.books.recordProgress(book, page);
        Book recorded = this.books.find(book.id()).orElseThrow();
        String toast = done ? "『" + recorded.title() + "』を読み終えました" : "p." + page + " まで記録しました";
        if (Requests.htmx(request)) {
            return Fragment.of(Templates.PROGRESS, new ProgressPiece(BookCard.of(recorded), true, done, null))
                .toast(toast)
                .response();
        }
        return Redirect.to("/books/" + book.id()).flash(toast).response(request);
    }

    // POST /books/{id}/rating
    public ServerResponse rating(ServerRequest request) throws Exception {
        Optional<Book> book = book(request);
        if (book.isEmpty()) {
            return ErrorPage.notFound(request);
        }
        long id = book.get().id();
        return switch (RATING.validate(request.params())) {
            case Validated.Valid<Rating>(Rating rating) -> {
                this.books.rate(id, rating);
                String toast = "評価を " + "★★★★★".substring(0, rating.stars()) + " にしました";
                yield Requests.htmx(request) ? stars(id).toast(toast).response()
                        : Redirect.to("/books/" + id).flash(toast).response(request);
            }
            // the form swaps nothing on this status (its hx-status:422)
            case Validated.Invalid<Rating> refused -> Fragment.empty().status(422).response();
        };
    }

    // The stars of a book as they are now.
    private Fragment stars(long id) throws SQLException {
        return Fragment.of(Templates.STARS, new StarsPiece(BookCard.of(this.books.find(id).orElseThrow()), true));
    }

    // The book the path names, if there is one.
    private Optional<Book> book(ServerRequest request) throws SQLException {
        Optional<Long> id = Requests.id(request);
        return id.isEmpty() ? Optional.empty() : this.books.find(id.get());
    }

}
