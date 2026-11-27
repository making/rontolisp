// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

// Marks a method of an assertion that is no check: one answering another assertion, or
// setting what the next failure says. Of a soft assertion it throws what fails in it,
// where a check hands its failure to the collector, and an assertion it answers is a
// soft one too (JavaLowering.softCheck).
public @interface Uncollected {
}
