// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.List;
import java.util.Optional;

// Where the failures of soft assertions go.
public interface AssertionErrorCollector extends AfterAssertionErrorCollected {

    default void setDelegate(AssertionErrorCollector delegate) {
    }

    default Optional<AssertionErrorCollector> getDelegate() {
        return Optional.empty();
    }

    void collectAssertionError(AssertionError error);

    List<AssertionError> assertionErrorsCollected();

    @Override
    default void onAssertionErrorCollected(AssertionError assertionError) {
    }

    void succeeded();

    boolean wasSuccess();
}
