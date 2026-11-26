// Shiori: the dashboard (GET /).
package com.example.shiori.home.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.shiori.book.Book;
import com.example.shiori.book.BookRepository;
import com.example.shiori.book.ReadingStats;
import com.example.shiori.book.TagCount;
import com.example.shiori.book.web.BookCard;
import com.example.shiori.book.web.Spines;
import com.example.shiori.note.NoteRepository;
import com.example.shiori.note.web.NoteView;
import com.example.shiori.web.Formats;
import com.example.shiori.web.Nav;
import com.example.shiori.web.Page;
import com.example.shiori.web.Templates;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class HomeHandler {

    // The months the dashboard piles finished books by, this one the last.
    private static final int MONTHS = 12;

    private final BookRepository books;

    private final NoteRepository notes;

    public HomeHandler(BookRepository books, NoteRepository notes) {
        this.books = books;
        this.notes = notes;
    }

    record Stats(int total, int reading, int finishedThisYear, String pagesRead, String today) {
    }

    record TagLink(String name, int n, String href) {
    }

    // A finished book lying in its month's pile.
    record PileBook(long id, String title, String style) {
    }

    // A month with the books finished in it.
    record Month(String label, String fullLabel, boolean current, String count, List<PileBook> books) {
    }

    // home.mustache: the notes show which book they are of.
    record Dashboard(Stats stats, String weekday, boolean hasReading, List<Month> months, List<TagLink> tags,
            List<NoteView> notes, boolean showSource, List<BookCard> readingNow, List<BookCard> recentlyFinished) {
    }

    public ServerResponse dashboard(ServerRequest request) throws Exception {
        ReadingStats stats = this.books.stats();
        List<TagLink> tags = new ArrayList<>();
        for (TagCount tag : this.books.tagCounts()) {
            tags.add(new TagLink(tag.name(), tag.books(), BookCard.tagHref(tag.name())));
        }
        Dashboard dashboard = new Dashboard(
                new Stats(stats.total(), stats.reading(), stats.finishedThisYear(), Formats.grouped(stats.pagesRead()),
                        Formats.monthAndDay(stats.today())),
                Formats.weekday(stats.today()), stats.reading() > 0, months(stats.today()), tags,
                NoteView.of(this.notes.recent(5)), true, BookCard.of(this.books.readingNow(5)),
                BookCard.of(this.books.recentlyFinished(12)));
        return Page.of(Templates.HOME, dashboard).title("ホーム").nav(Nav.HOME).response(request);
    }

    // The last months, oldest first, each with the books finished in it: the dashboard
    // draws a month as the pile of those books.
    private List<Month> months(LocalDate today) throws Exception {
        LocalDate first = today.withDayOfMonth(1).minusMonths(MONTHS - 1);
        List<Book> finished = this.books.finishedSince(first);
        List<Month> months = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            LocalDate month = first.plusMonths(i);
            List<PileBook> pile = new ArrayList<>();
            for (Book book : finished) {
                if (book.finishedOn().getYear() == month.getYear()
                        && book.finishedOn().getMonthValue() == month.getMonthValue()) {
                    pile.add(new PileBook(book.id(), book.title(), Spines.pileStyle(book.title(), book.pages())));
                }
            }
            months.add(new Month(month.getMonthValue() + "月", Formats.yearAndMonth(month), i == MONTHS - 1,
                    pile.isEmpty() ? "" : Integer.toString(pile.size()), pile));
        }
        return months;
    }

}
