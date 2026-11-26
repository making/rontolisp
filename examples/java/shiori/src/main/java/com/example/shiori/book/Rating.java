// Shiori: the stars a book's page gives it.
package com.example.shiori.book;

import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

public record Rating(int stars) {

    public static final ObjectInputValidator<Rating> SCHEMA = Yavi.schema()
        ._integer(BookDetails.RATING)
        .fromMap(Rating::new);

}
