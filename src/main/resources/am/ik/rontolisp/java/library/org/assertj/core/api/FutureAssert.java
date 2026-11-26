// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.concurrent.Future;

public class FutureAssert<RESULT> extends AbstractFutureAssert<FutureAssert<RESULT>, Future<RESULT>, RESULT> {

    protected FutureAssert(Future<RESULT> actual) {
        super(actual);
    }
}
