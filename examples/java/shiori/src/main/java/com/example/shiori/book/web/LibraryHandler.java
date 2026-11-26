// Shiori: the library (GET /books) -- search, filters, sorting and pages.
package com.example.shiori.book.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.yavi.core.ValueValidator;
import com.example.shiori.book.BookRepository;
import com.example.shiori.book.BookSearch;
import com.example.shiori.book.BookSearch.Sort;
import com.example.shiori.book.Status;
import com.example.shiori.web.Formats;
import com.example.shiori.web.Fragment;
import com.example.shiori.web.Nav;
import com.example.shiori.web.Page;
import com.example.shiori.web.Requests;
import com.example.shiori.web.Templates;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class LibraryHandler {

    // A query string is text, trimmed.
    private static final ValueValidator<Map<String, ?>, BookSearch> SEARCH = BookSearch.SCHEMA
        .readingParameters(Requests.FORM);

    private final BookRepository books;

    public LibraryHandler(BookRepository books) {
        this.books = books;
    }

    record Tab(String value, String label, int n, boolean checked) {
    }

    record SortOption(String value, String label, boolean selected) {
    }

    record PageLink(boolean gap, int n, boolean current, String href) {
    }

    record Pagination(List<PageLink> links, String prev, String next) {
    }

    // library.mustache, and shelf.mustache within it.
    record Library(List<BookCard> books, int total, String q, String tag, String clearTag, boolean filtered,
            Pagination pagination, List<Tab> tabs, List<SortOption> sorts) {
    }

    public ServerResponse library(ServerRequest request) throws Exception {
        // a search recovers from anything sent, so it is always valid
        BookSearch asked = SEARCH.validated(request.params());
        int total = this.books.count(asked);
        BookSearch search = asked.within(total);
        List<BookCard> found = BookCard.of(this.books.find(search));
        Library library = new Library(found, total, search.q(), search.tag(), url(search.withoutTag()),
                search.filtered(), (BookSearch.pages(total) > 1) ? pagination(search, total) : null,
                tabs(search, this.books.countByStatus()), sorts(search));
        // The search form targets #shelf: answer the shelf alone. Anything else -- a
        // boosted link, a bookmark, a reload -- gets the whole page.
        if ("shelf".equals(Requests.htmxTargetId(request))) {
            return Fragment.of(Templates.SHELF, library).response();
        }
        return Page.of(Templates.LIBRARY, library).title("本棚").nav(Nav.LIBRARY).response(request);
    }

    // Every book, then each status, the search's checked.
    private static List<Tab> tabs(BookSearch search, Map<Status, Integer> counts) {
        int all = 0;
        for (int n : counts.values()) {
            all += n;
        }
        List<Tab> tabs = new ArrayList<>();
        tabs.add(new Tab("", "すべて", all, search.status() == null));
        for (Status status : Status.values()) {
            tabs.add(new Tab(status.value(), BookCard.label(status), counts.get(status), status == search.status()));
        }
        return tabs;
    }

    private static List<SortOption> sorts(BookSearch search) {
        List<SortOption> sorts = new ArrayList<>();
        for (Sort sort : Sort.values()) {
            String label = switch (sort) {
                case UPDATED -> "最近更新した順";
                case TITLE -> "タイトル順";
                case AUTHOR -> "著者順";
                case RATING -> "評価の高い順";
            };
            sorts.add(new SortOption(sort.value(), label, sort == search.sort()));
        }
        return sorts;
    }

    // Links around the current page: the first, the last, two either side, and a gap
    // wherever pages are skipped.
    private static Pagination pagination(BookSearch search, int total) {
        int current = search.page();
        int last = BookSearch.pages(total);
        List<PageLink> links = new ArrayList<>();
        int lastShown = 0;
        for (int n = 1; n <= last; n++) {
            if (n == 1 || n == last || Math.abs(n - current) <= 2) {
                if (n > lastShown + 1) {
                    links.add(new PageLink(true, 0, false, null));
                }
                links.add(new PageLink(false, n, n == current, url(search.on(n))));
                lastShown = n;
            }
        }
        return new Pagination(links, (current > 1) ? url(search.on(current - 1)) : null,
                (current < last) ? url(search.on(current + 1)) : null);
    }

    // /books with the search as its query string; a default is left out, so the URL
    // stays short.
    private static String url(BookSearch search) {
        List<String> pairs = new ArrayList<>();
        if (!search.q().isEmpty()) {
            pairs.add("q=" + Formats.queryValue(search.q()));
        }
        if (search.status() != null) {
            pairs.add("status=" + Formats.queryValue(search.status().value()));
        }
        if (search.tag() != null) {
            pairs.add("tag=" + Formats.queryValue(search.tag()));
        }
        if (search.sort() != Sort.UPDATED) {
            pairs.add("sort=" + search.sort().value());
        }
        if (search.page() != 1) {
            pairs.add("page=" + search.page());
        }
        return pairs.isEmpty() ? "/books" : "/books?" + String.join("&", pairs);
    }

}
