// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.lang.reflect.Type;

// The assertion of a value of a type, made where the value is one.
public class InstanceOfAssertFactory<T, ASSERT extends AbstractAssert<?, ?>> implements AssertFactory<Object, ASSERT> {

    private final Class<T> type;

    private final Type[] typeArguments;

    private final AssertFactory<T, ASSERT> delegate;

    public InstanceOfAssertFactory(Class<T> type, AssertFactory<T, ASSERT> delegate) {
        this(type, new Type[0], delegate);
    }

    public InstanceOfAssertFactory(Class<T> type, Type[] typeArguments, AssertFactory<T, ASSERT> delegate) {
        this.type = java.util.Objects.requireNonNull(type, "type should not be null");
        this.typeArguments = java.util.Objects.requireNonNull(typeArguments, "typeArguments should not be null");
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate should not be null");
    }

    Class<T> getRawClass() {
        return type;
    }

    @Override
    public ASSERT createAssert(Object actual) {
        return delegate.createAssert(type.cast(actual));
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("InstanceOfAssertFactory for ").append(type.getName());
        if (typeArguments.length > 0) {
            text.append('<');
            for (int i = 0; i < typeArguments.length; i++) {
                text.append(i == 0 ? "" : ", ").append(typeArguments[i].getTypeName());
            }
            text.append('>');
        }
        return text.toString();
    }
}
