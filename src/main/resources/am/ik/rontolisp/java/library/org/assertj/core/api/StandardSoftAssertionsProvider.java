// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import rontolisp.internal.Soft;

public interface StandardSoftAssertionsProvider extends Java6StandardSoftAssertionsProvider {

    default PathAssert assertThat(Path actual) {
        return Soft.soft(new PathAssert(actual), this);
    }

    default <VALUE> OptionalAssert<VALUE> assertThat(Optional<VALUE> actual) {
        return Soft.soft(new OptionalAssert<>(actual), this);
    }

    default LocalDateAssert assertThat(LocalDate actual) {
        return Soft.soft(new LocalDateAssert(actual), this);
    }

    default LocalDateTimeAssert assertThat(LocalDateTime actual) {
        return Soft.soft(new LocalDateTimeAssert(actual), this);
    }

    default LocalTimeAssert assertThat(LocalTime actual) {
        return Soft.soft(new LocalTimeAssert(actual), this);
    }

    default InstantAssert assertThat(Instant actual) {
        return Soft.soft(new InstantAssert(actual), this);
    }

    default DurationAssert assertThat(Duration actual) {
        return Soft.soft(new DurationAssert(actual), this);
    }

    default <RESULT> CompletableFutureAssert<RESULT> assertThat(CompletableFuture<RESULT> actual) {
        return Soft.soft(new CompletableFutureAssert<>(actual), this);
    }
}
