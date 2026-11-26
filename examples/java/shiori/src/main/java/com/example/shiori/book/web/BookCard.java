// Shiori: a book as the templates show it -- its spine, its stars, its tags, its dates.
package com.example.shiori.book.web;

import java.util.ArrayList;
import java.util.List;

import com.example.shiori.book.Book;
import com.example.shiori.book.Status;
import com.example.shiori.web.Formats;

// spine.mustache draws one on a shelf; a book's page, its pieces and the dashboard show
// the rest.
public record BookCard(long id, String title, String author, Integer pages, int currentPage, int progress,
        String summary, String statusLabel, boolean reading, boolean want, boolean finished, boolean paused,
        boolean latin, String spine, List<Star> stars, List<TagLink> tagList, String startedLabel,
        String finishedLabel, String finishedStamp) {

    public record Star(int n, boolean on) {
    }

    public record TagLink(String name, String href) {
    }

    public static BookCard of(Book book) {
        List<Star> stars = new ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            stars.add(new Star(n, book.rating() != null && n <= book.rating()));
        }
        List<TagLink> tags = new ArrayList<>();
        for (String tag : book.tags()) {
            tags.add(new TagLink(tag, tagHref(tag)));
        }
        Status status = book.status();
        return new BookCard(book.id(), book.title(), book.author(), book.pages(), book.currentPage(),
                book.progress(), book.summary(), label(status), status == Status.READING, status == Status.WANT,
                status == Status.FINISHED, status == Status.PAUSED, !Formats.japanese(book.title()),
                Spines.style(book.title(), book.pages()), stars, tags, Formats.date(book.startedOn()),
                Formats.date(book.finishedOn()), Formats.dotted(book.finishedOn()));
    }

    public static List<BookCard> of(List<Book> books) {
        List<BookCard> cards = new ArrayList<>();
        for (Book book : books) {
            cards.add(of(book));
        }
        return cards;
    }

    public static String label(Status status) {
        return switch (status) {
            case READING -> "読書中";
            case WANT -> "読みたい";
            case FINISHED -> "読了";
            case PAUSED -> "中断";
        };
    }

    // The library narrowed to a tag.
    public static String tagHref(String name) {
        return "/books?tag=" + Formats.queryValue(name);
    }

}
