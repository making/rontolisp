// Shiori: progress as a book's page records it.
package com.example.shiori.book;

import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// The page read, or the second button: "read to the end" carries no page, and the
// number field is sent either way.
public record Progress(Integer currentPage, boolean finished) {

    public static final ObjectInputValidator<Progress> SCHEMA = Yavi.schema()
        ._integer(BookDetails.CURRENT_PAGE)
        .nullable()
        ._string("finished")
        .map(pressed -> true)
        .defaultValue(false)
        .fromMap(Progress::new);

    // The page this progress reaches in a book of so many pages, if it says one.
    public Integer pageRead(Integer pages) {
        return (pages != null && this.finished) ? pages : this.currentPage;
    }

}
