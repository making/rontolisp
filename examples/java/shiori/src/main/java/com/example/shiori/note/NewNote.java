// Shiori: a quote or a note as a book's page writes it down.
package com.example.shiori.note;

import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// Its kind, its page if it has one, and its text.
public record NewNote(Note.Kind kind, Integer page, String body) {

    public static final ObjectInputValidator<NewNote> SCHEMA = Yavi.schema()
        ._string("kind")
        .map(Note.Kind::of)
        .defaultValue(Note.Kind.NOTE)
        ._integer("page", c -> c.greaterThan(0))
        .nullable()
        ._string("body", c -> c.notBlank().lessThanOrEqual(2000))
        .fromMap(NewNote::new);

}
