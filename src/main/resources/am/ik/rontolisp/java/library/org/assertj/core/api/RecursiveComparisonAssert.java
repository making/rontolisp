// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.List;

import rontolisp.internal.Recursion;
import rontolisp.internal.Shown;

// A value compared with another field by field (rontolisp.internal.Recursion), and how.
public class RecursiveComparisonAssert<SELF extends RecursiveComparisonAssert<SELF>> extends AbstractAssert<SELF, Object> {

    private final Recursion recursion = new Recursion();

    RecursiveComparisonAssert(Object actual) {
        super(actual);
    }

    @Override
    public SELF isEqualTo(Object expected) {
        if (expected == null) {
            return super.isEqualTo(null);
        }
        isNotNull();
        List<String> differences = recursion.differences(actual, expected);
        if (!differences.isEmpty()) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nto be equal to:\n" + Shown.under(expected)
                    + "\nwhen recursively comparing field by field, but found the following "
                    + (differences.size() == 1 ? "difference" : differences.size() + " differences") + ":\n\n"
                    + String.join("\n\n", differences) + "\n\n" + recursion.configuration());
        }
        return myself;
    }

    @Override
    public SELF isNotEqualTo(Object other) {
        if (actual == null ? other == null : other != null && recursion.differences(actual, other).isEmpty()) {
            throw failure("\nExpecting actual:\n" + Shown.under(actual) + "\nnot to be equal to:\n" + Shown.under(other)
                    + "\nwhen recursively comparing field by field\n" + recursion.configuration());
        }
        return myself;
    }

    public SELF ignoringFields(String... fieldsToIgnore) {
        recursion.ignored.addAll(List.of(fieldsToIgnore));
        return myself;
    }

    public SELF comparingOnlyFields(String... fieldNamesToCompare) {
        recursion.compared.addAll(List.of(fieldNamesToCompare));
        return myself;
    }

    public SELF ignoringActualNullFields() {
        recursion.ignoringActualNulls = true;
        return myself;
    }

    public SELF ignoringExpectedNullFields() {
        recursion.ignoringExpectedNulls = true;
        return myself;
    }

    public SELF ignoringCollectionOrder() {
        recursion.ignoringOrder = true;
        return myself;
    }

    public SELF withStrictTypeChecking() {
        recursion.strict = true;
        return myself;
    }
}
