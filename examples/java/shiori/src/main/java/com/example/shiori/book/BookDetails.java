// Shiori: what is written down about a book when it is shelved or edited.
package com.example.shiori.book;

import java.time.LocalDate;

import am.ik.yavi.core.ReversibleValueValidator;
import am.ik.yavi.core.ViolationMessage;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// Its tags are the text they were written as, separated by commas.
public record BookDetails(String title, String author, Integer pages, int currentPage, Status status,
        Integer rating, LocalDate startedOn, LocalDate finishedOn, String tags, String summary) {

    // A finished book is read to its last page.
    public int pageRead() {
        return (this.pages != null && this.status == Status.FINISHED) ? this.pages : this.currentPage;
    }

    private static final String DATE = "日付は 2026-01-31 の形で入力してください。";

    private static final String CURRENT_PAGE_MESSAGE = "読んだ頁は0以上の整数で入力してください。";

    private static final String RATING_MESSAGE = "評価は1から5の星で選んでください。";

    // The fields a book's page changes in place too.
    static final ReversibleValueValidator<Integer, Integer> CURRENT_PAGE = Yavi._integer("current_page",
            c -> c.greaterThanOrEqual(0).message(CURRENT_PAGE_MESSAGE));

    static final ReversibleValueValidator<Integer, Integer> RATING = Yavi._integer("rating",
            c -> c.greaterThanOrEqual(1).message(RATING_MESSAGE).lessThanOrEqual(5).message(RATING_MESSAGE));

    // The fields by the names the book form sends them under, and the messages of what
    // each refuses. Reversible: the form shows through what the schema describes, whether
    // it holds the text that was sent (a refused form) or a stored book written back
    // (edit).
    public static final ObjectInputValidator.Reversible<BookDetails> SCHEMA = Yavi.reversibleSchema()
        ._string("title",
                c -> c.notBlank()
                    .message("書名を入力してください。")
                    .lessThanOrEqual(200)
                    .message("書名は200字以内で入力してください。"))
        ._string("author",
                c -> c.notBlank()
                    .message("著者を入力してください。")
                    .lessThanOrEqual(200)
                    .message("著者は200字以内で入力してください。"))
        ._integer("pages", c -> c.greaterThan(0).message("頁数は1以上の整数で入力してください。"))
        .typeMessage("頁数は1以上の整数で入力してください。")
        .nullable()
        ._integer(CURRENT_PAGE)
        .typeMessage(CURRENT_PAGE_MESSAGE)
        .defaultValue(0)
        ._string(Status.VALIDATOR)
        .required("状態を選んでください。")
        ._integer(RATING)
        .typeMessage(RATING_MESSAGE)
        .nullable()
        ._localDate("started_on")
        .typeMessage(DATE)
        .nullable()
        ._localDate("finished_on")
        .typeMessage(DATE)
        .nullable()
        ._string("tags")
        .defaultValue("")
        ._string("summary")
        .defaultValue("")
        .fromMapReversible(BookDetails::new, BookDetails::title, BookDetails::author, BookDetails::pages,
                BookDetails::currentPage, BookDetails::status, BookDetails::rating, BookDetails::startedOn,
                BookDetails::finishedOn, BookDetails::tags, BookDetails::summary)
        .constraintOnTarget(details -> details.pages() == null || details.currentPage() <= details.pages(),
                "current_page", ViolationMessage.of("shiori.currentPage.overPages", "読んだ頁が頁数を超えています。"));

    // What a stored book was written down as.
    public static BookDetails of(Book book) {
        return new BookDetails(book.title(), book.author(), book.pages(), book.currentPage(), book.status(),
                book.rating(), book.startedOn(), book.finishedOn(), String.join(",", book.tags()), book.summary());
    }

}
