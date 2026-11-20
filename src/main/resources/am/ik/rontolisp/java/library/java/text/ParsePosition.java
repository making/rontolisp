// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Where a reading of a text starts and ends, and where it failed (-1 while it has not).
// The hash code is a JDK 25's answer: the error index in the upper half, the index or'ed
// into it.
package java.text;

public class ParsePosition {

    private int index;

    private int errorIndex = -1;

    public ParsePosition(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getErrorIndex() {
        return errorIndex;
    }

    public void setErrorIndex(int errorIndex) {
        this.errorIndex = errorIndex;
    }

    public boolean equals(Object other) {
        return other instanceof ParsePosition that && that.index == index && that.errorIndex == errorIndex;
    }

    public int hashCode() {
        return (errorIndex << 16) | index;
    }

    public String toString() {
        return getClass().getName() + "[index=" + index + ",errorIndex=" + errorIndex + "]";
    }
}
