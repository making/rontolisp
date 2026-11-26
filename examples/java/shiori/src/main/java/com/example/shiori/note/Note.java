// Shiori: a quote or a note written down about a book.
package com.example.shiori.note;

import java.time.LocalDate;

// Its page if it has one; the title is its book's, where it is asked for.
public record Note(long id, Kind kind, Integer page, String body, long bookId, String bookTitle,
        LocalDate writtenOn) {

    public enum Kind {

        QUOTE("quote"), NOTE("note");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        // What a form and the database say.
        public String value() {
            return this.value;
        }

        // Anything but a quote is a note.
        public static Kind of(String value) {
            return QUOTE.value.equals(value) ? QUOTE : NOTE;
        }

    }

}
