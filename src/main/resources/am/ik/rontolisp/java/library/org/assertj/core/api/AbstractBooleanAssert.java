// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

public abstract class AbstractBooleanAssert<SELF extends AbstractBooleanAssert<SELF>> extends AbstractAssert<SELF, Boolean> {

    protected AbstractBooleanAssert(Boolean actual) {
        super(actual);
    }

    public SELF isTrue() {
        isNotNull();
        if (!actual) {
            throw unequal("\nExpecting value to be true but was false", true, false);
        }
        return myself;
    }

    public SELF isFalse() {
        isNotNull();
        if (actual) {
            throw unequal("\nExpecting value to be false but was true", false, true);
        }
        return myself;
    }

    public SELF isEqualTo(boolean expected) {
        return isEqualTo((Object) expected);
    }

    public SELF isNotEqualTo(boolean other) {
        return isNotEqualTo((Object) other);
    }
}
