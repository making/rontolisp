// Shiori: where a book stands.
package com.example.shiori.book;

import java.util.List;
import java.util.stream.Stream;

import am.ik.yavi.core.ReversibleValueValidator;
import am.ik.yavi.validator.Yavi;

// In display order.
public enum Status {

    READING("reading"), WANT("want"), FINISHED("finished"), PAUSED("paused");

    private final String value;

    Status(String value) {
        this.value = value;
    }

    // What a form, a query string and the database say.
    public String value() {
        return this.value;
    }

    private static final List<String> VALUES = Stream.of(values()).map(Status::value).toList();

    // A status read from its value and written back as it.
    public static final ReversibleValueValidator<String, Status> VALIDATOR = Yavi
        ._string("status", c -> c.oneOf(VALUES).message("状態を選んでください。"))
        .map(Status::of, Status::value);

    public static Status of(String value) {
        for (Status status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("no status " + value);
    }

}
