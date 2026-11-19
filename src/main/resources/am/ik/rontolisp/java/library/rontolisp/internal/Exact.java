// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

// A number written by its own digits, however many it has: what a format asks of a
// number instead of its nearest double. Only a program that makes such a number holds
// the code that writes it.
public interface Exact {

    /**
     * The digits with no exponent, rounded half to even where there are more than so
     * many after the point; a negative number keeps its sign when no digit is left of it.
     */
    String written(int places);

}
