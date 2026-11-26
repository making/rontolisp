// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.groups;

import java.util.ArrayList;
import java.util.List;

import rontolisp.internal.Shown;

// Values taken together: what the properties of one element extracted by their names
// are, compared value by value.
public final class Tuple {

    private final List<Object> values;

    public Tuple(Object... values) {
        this.values = Shown.elementsOf(values);
    }

    public static Tuple tuple(Object... values) {
        return new Tuple(values);
    }

    public Object[] toArray() {
        return values.toArray();
    }

    public List<Object> toList() {
        return new ArrayList<>(values);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Tuple tuple) || tuple.values.size() != values.size()) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            if (!Shown.same(values.get(i), tuple.values.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (Object value : values) {
            hash = 31 * hash + (value == null ? 0 : value.hashCode());
        }
        return hash;
    }

    @Override
    public String toString() {
        String listed = Shown.ofAll(values);
        return "(" + listed.substring(1, listed.length() - 1) + ")";
    }
}
