// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.error;

import java.util.List;

import org.opentest4j.MultipleFailuresError;

// The failures soft assertions collected, or those of a group of requirements, each
// under its number. Measured of AssertJ 3.27 under a JDK: each failure's message is
// followed by a line naming the test's file and line, which takes the place of a line
// break the message ends with; here no line is known, and the line break is left out so
// that the text is AssertJ's without those lines. A message that starts with a
// description starts on a line of its own.
public class AssertJMultipleFailuresError extends MultipleFailuresError {

    private final String heading;

    public AssertJMultipleFailuresError(String heading, List<? extends Throwable> failures) {
        super(heading, failures);
        this.heading = heading == null || heading.isBlank() ? "Multiple Failures" : heading.trim();
    }

    @Override
    public String getMessage() {
        List<Throwable> failures = getFailures();
        int count = failures.size();
        if (count == 0) {
            return super.getMessage();
        }
        StringBuilder text = new StringBuilder("\n").append(heading)
            .append(" (")
            .append(count)
            .append(count == 1 ? " failure)" : " failures)");
        for (int i = 0; i < count; i++) {
            String message = String.valueOf(failures.get(i).getMessage());
            text.append("\n-- failure ")
                .append(i + 1)
                .append(message.startsWith("[") ? " --\n" : " --")
                .append(message.endsWith("\n") ? message.substring(0, message.length() - 1) : message);
        }
        return text.toString();
    }
}
