// Shiori: the book form -- registering, editing and removing a book.
package com.example.shiori.book.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import am.ik.yavi.core.ConstraintViolation;
import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validated;
import am.ik.yavi.core.ValueValidator;
import am.ik.yavi.input.ParameterTree;
import com.example.shiori.book.Book;
import com.example.shiori.book.BookDetails;
import com.example.shiori.book.BookRepository;
import com.example.shiori.book.Status;
import com.example.shiori.web.ErrorPage;
import com.example.shiori.web.Nav;
import com.example.shiori.web.Page;
import com.example.shiori.web.Redirect;
import com.example.shiori.web.Requests;
import com.example.shiori.web.Templates;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class BookFormHandler {

    // What a form sends is text, trimmed.
    private static final ValueValidator<Map<String, ?>, BookDetails> SENT = BookDetails.SCHEMA
        .readingParameters(Requests.FORM);

    private final BookRepository books;

    public BookFormHandler(BookRepository books) {
        this.books = books;
    }

    record StatusOption(String value, String label, boolean checked) {
    }

    record RatingOption(int n, boolean checked) {
    }

    // book-form.mustache: the fields' text by their names, the errors by the fields they
    // are of, and the spine the preview starts from.
    record FormPage(String heading, String action, String cancel, boolean editing, Map<String, Object> form,
            Map<String, List<String>> errors, List<String> errorList, String spine, String spineJson,
            List<StatusOption> statusOptions, List<RatingOption> ratingOptions) {
    }

    // The form a book is being written into: new, or the stored one's (its id), the fields
    // holding the text it shows.
    private record Filled(Long id, ParameterTree fields) {
    }

    // GET /books/new
    public ServerResponse newBook(ServerRequest request) {
        return form(new Filled(null, ParameterTree.of(Map.of("status", Status.WANT.value()))),
                new ConstraintViolations())
            .response(request);
    }

    // POST /books
    public ServerResponse create(ServerRequest request) throws Exception {
        return switch (SENT.validate(request.params())) {
            case Validated.Valid<BookDetails>(BookDetails book) -> {
                long id = this.books.create(book);
                yield Redirect.to("/books/" + id).flash("『" + book.title() + "』を本棚に加えました").response(request);
            }
            // 422 with the form again, as it was sent; htmx 4 swaps a 422 in like a 200
            case Validated.Invalid<BookDetails>(ConstraintViolations violations) ->
                form(new Filled(null, ParameterTree.of(request.params())), violations).status(422).response(request);
        };
    }

    // GET /books/{id}/edit: the stored book, written back as the text of its form's fields.
    public ServerResponse edit(ServerRequest request) throws Exception {
        Optional<Long> id = Requests.id(request);
        Optional<Book> book = id.isEmpty() ? Optional.empty() : this.books.find(id.get());
        if (book.isEmpty()) {
            return ErrorPage.notFound(request);
        }
        ParameterTree fields = ParameterTree.ofValues(BookDetails.SCHEMA.toMap(BookDetails.of(book.get())));
        return form(new Filled(book.get().id(), fields), new ConstraintViolations()).response(request);
    }

    // POST /books/{id}
    public ServerResponse update(ServerRequest request) throws Exception {
        Optional<Long> id = Requests.id(request);
        if (id.isEmpty() || this.books.find(id.get()).isEmpty()) {
            return ErrorPage.notFound(request);
        }
        return switch (SENT.validate(request.params())) {
            case Validated.Valid<BookDetails>(BookDetails book) -> {
                this.books.update(id.get(), book);
                yield Redirect.to("/books/" + id.get()).flash("変更を保存しました").response(request);
            }
            case Validated.Invalid<BookDetails>(ConstraintViolations violations) ->
                form(new Filled(id.get(), ParameterTree.of(request.params())), violations).status(422)
                    .response(request);
        };
    }

    // POST /books/{id}/delete
    public ServerResponse delete(ServerRequest request) throws Exception {
        Optional<Long> id = Requests.id(request);
        Optional<String> title = id.isEmpty() ? Optional.empty() : this.books.delete(id.get());
        if (title.isEmpty()) {
            return ErrorPage.notFound(request);
        }
        return Redirect.to("/books").flash("『" + title.get() + "』を本棚から外しました").response(request);
    }

    // The form, filled with its fields' text, the errors beside the fields they are of.
    private static Page form(Filled filled, ConstraintViolations violations) {
        Map<String, Object> form = filled.fields().toMap(BookDetails.SCHEMA.description());
        List<String> messages = new ArrayList<>();
        for (ConstraintViolation violation : violations) {
            messages.add(violation.message());
        }
        boolean editing = filled.id() != null;
        String heading = editing ? "本を編集" : "本を登録";
        String back = editing ? "/books/" + filled.id() : "/books";
        String sentStatus = String.valueOf(form.get("status"));
        List<StatusOption> statuses = new ArrayList<>();
        for (Status status : Status.values()) {
            statuses.add(new StatusOption(status.value(), BookCard.label(status), status.value().equals(sentStatus)));
        }
        String sentRating = String.valueOf(form.get("rating"));
        List<RatingOption> ratings = new ArrayList<>();
        for (int n = 5; n >= 1; n--) {
            ratings.add(new RatingOption(n, Integer.toString(n).equals(sentRating)));
        }
        String spine = Spines.style(String.valueOf(form.get("title")), pages(form.get("pages")));
        FormPage page = new FormPage(heading, back, back, editing, form,
                violations.isEmpty() ? null : violations.messagesByName(), messages, spine, Spines.JSON, statuses,
                ratings);
        return Page.of(Templates.BOOK_FORM, page).title(heading).nav(editing ? Nav.LIBRARY : Nav.NEW);
    }

    // The page count the preview draws, if the field holds one.
    private static Integer pages(Object text) {
        try {
            int pages = Integer.parseInt(String.valueOf(text));
            return (pages > 0) ? pages : null;
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

}
