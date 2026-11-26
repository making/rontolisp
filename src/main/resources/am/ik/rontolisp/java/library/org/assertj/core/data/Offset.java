// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.data;

import java.util.Objects;

// How far a number may be from the one expected: a difference of exactly the value is
// close unless the offset is strict.
public final class Offset<T extends Number> {

    public final T value;

    public final boolean strict;

    private Offset(T value, boolean strict) {
        this.value = value;
        this.strict = strict;
    }

    public static <T extends Number> Offset<T> offset(T value) {
        Objects.requireNonNull(value);
        if (value.doubleValue() < 0) {
            throw new IllegalArgumentException("An offset value should be greater than or equal to zero");
        }
        return new Offset<>(value, false);
    }

    public static <T extends Number> Offset<T> strictOffset(T value) {
        Objects.requireNonNull(value);
        if (value.doubleValue() <= 0) {
            throw new IllegalArgumentException("A strict offset value should be greater than zero");
        }
        return new Offset<>(value, true);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Offset<?> offset && strict == offset.strict && value.equals(offset.value);
    }

    @Override
    public int hashCode() {
        return 31 * value.hashCode() + (strict ? 1 : 0);
    }

    @Override
    public String toString() {
        return (strict ? "strict " : "") + "Offset[value=" + value + "]";
    }
}
