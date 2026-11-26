// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

// What makes the assertion of a value.
@FunctionalInterface
public interface AssertFactory<T, ASSERT extends AbstractAssert<?, ?>> {

    ASSERT createAssert(T actual);
}
