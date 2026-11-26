// Shiori: what the library is asked for -- words, a status, a tag, an order and a page.
package com.example.shiori.book;

import java.util.List;
import java.util.stream.Stream;

import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// An unknown status or sort, a page that is no positive number, or a field sent twice is
// replaced rather than trusted: a search is never refused.
public record BookSearch(String q, Status status, String tag, Sort sort, int page) {

    // The books a page of the library holds.
    public static final int PER_PAGE = 36;

    // Each order the library offers, by the value a query string names it with.
    public enum Sort {

        UPDATED("updated"), TITLE("title"), AUTHOR("author"), RATING("rating");

        private final String value;

        Sort(String value) {
            this.value = value;
        }

        public String value() {
            return this.value;
        }

        private static final List<String> VALUES = Stream.of(values()).map(Sort::value).toList();

        public static Sort of(String value) {
            for (Sort sort : values()) {
                if (sort.value.equals(value)) {
                    return sort;
                }
            }
            throw new IllegalArgumentException("no sort " + value);
        }

    }

    public static final ObjectInputValidator<BookSearch> SCHEMA = Yavi.schema()
        ._string("q")
        .recover("")
        .defaultValue("")
        ._string(Status.VALIDATOR)
        .nullable()
        .recover(null)
        ._string("tag")
        .nullable()
        .recover(null)
        ._string("sort", c -> c.oneOf(Sort.VALUES))
        .map(Sort::of)
        .recover(Sort.UPDATED)
        .defaultValue(Sort.UPDATED)
        ._integer("page", c -> c.greaterThan(0))
        .recover(1)
        .defaultValue(1)
        .fromMap(BookSearch::new);

    public BookSearch on(int page) {
        return new BookSearch(this.q, this.status, this.tag, this.sort, page);
    }

    public BookSearch withoutTag() {
        return new BookSearch(this.q, this.status, null, this.sort, 1);
    }

    // Whether anything narrows the shelf.
    public boolean filtered() {
        return this.status != null || this.tag != null || !this.q.isEmpty();
    }

    // The pages the books found fill, one at least.
    public static int pages(int found) {
        return Math.max(1, (found + PER_PAGE - 1) / PER_PAGE);
    }

    // The same search on a page that exists: the last, past it.
    public BookSearch within(int found) {
        return on(Math.min(this.page, pages(found)));
    }

    public int offset() {
        return (this.page - 1) * PER_PAGE;
    }

}
