// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

// The checks of a character.
public abstract class AbstractCharacterAssert<SELF extends AbstractCharacterAssert<SELF>>
        extends AbstractComparableAssert<SELF, Character> {

    protected AbstractCharacterAssert(Character actual) {
        super(actual);
    }

    public SELF isEqualTo(char expected) {
        return isEqualTo((Object) expected);
    }

    public SELF isNotEqualTo(char other) {
        return isNotEqualTo((Object) other);
    }

    public SELF isGreaterThan(char other) {
        return isGreaterThan((Character) other);
    }

    public SELF isLessThan(char other) {
        return isLessThan((Character) other);
    }
}
