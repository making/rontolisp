// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.util.List;

public class ListAssert<ELEMENT> extends AbstractIterableAssert<ListAssert<ELEMENT>, List<? extends ELEMENT>, ELEMENT> {

    public ListAssert(List<? extends ELEMENT> actual) {
        super(actual);
    }

    @Override
    protected String navigated() {
        return "List";
    }
}
