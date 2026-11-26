// Shiori: a book on the shelf.
package com.example.shiori.book;

import java.time.LocalDate;
import java.util.List;

// Its page count, rating and dates may be unknown (null); its tags are in their names'
// order.
public record Book(long id, String title, String author, Integer pages, int currentPage, Status status,
        Integer rating, String summary, LocalDate startedOn, LocalDate finishedOn, List<String> tags) {

    // How much of it is read, in percent, rounded half up: none without a page count.
    public int progress() {
        if (this.pages == null || this.pages <= 0) {
            return 0;
        }
        long percent = (this.currentPage * 200L + this.pages) / (2L * this.pages);
        return (int) Math.min(100, percent);
    }

    // Whether a page can be recorded as read: it is in the book, if its length is known.
    public boolean hasPage(int page) {
        return page >= 0 && (this.pages == null || page <= this.pages);
    }

    // Whether reading to this page finishes the book.
    public boolean endsAt(int page) {
        return this.pages != null && page == this.pages;
    }

}
