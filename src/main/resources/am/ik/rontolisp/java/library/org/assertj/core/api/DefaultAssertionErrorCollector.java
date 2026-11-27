// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// The failures in the order they came, or those of the collector this one hands them to.
public class DefaultAssertionErrorCollector implements AssertionErrorCollector {

    // Whether the check that ran last passed.
    private boolean wasSuccess = true;

    private final List<AssertionError> collected = new ArrayList<>();

    // What is told of each failure: nothing until the program sets something.
    private List<AfterAssertionErrorCollected> told = new ArrayList<>();

    private AssertionErrorCollector delegate;

    public DefaultAssertionErrorCollector() {
    }

    @Override
    public void setDelegate(AssertionErrorCollector delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<AssertionErrorCollector> getDelegate() {
        return Optional.ofNullable(delegate);
    }

    @Override
    public void collectAssertionError(AssertionError error) {
        if (delegate != null) {
            delegate.collectAssertionError(error);
            return;
        }
        collected.add(error);
        wasSuccess = false;
        for (AfterAssertionErrorCollected each : told) {
            each.onAssertionErrorCollected(error);
        }
    }

    @Override
    public List<AssertionError> assertionErrorsCollected() {
        return delegate != null ? delegate.assertionErrorsCollected() : decorateErrorsCollected(collected);
    }

    public void setAfterAssertionErrorCollected(AfterAssertionErrorCollected afterAssertionErrorCollected) {
        told = new ArrayList<>();
        told.add(afterAssertionErrorCollected);
    }

    public void addAfterAssertionErrorCollected(AfterAssertionErrorCollected afterAssertionErrorCollected) {
        told.add(afterAssertionErrorCollected);
    }

    @Override
    public void succeeded() {
        if (delegate != null) {
            delegate.succeeded();
            return;
        }
        wasSuccess = true;
    }

    @Override
    public boolean wasSuccess() {
        return delegate != null ? delegate.wasSuccess() : wasSuccess;
    }

    // The failures as a list of the caller's own.
    protected <T extends Throwable> List<T> decorateErrorsCollected(List<? extends T> errors) {
        return new ArrayList<>(errors);
    }
}
