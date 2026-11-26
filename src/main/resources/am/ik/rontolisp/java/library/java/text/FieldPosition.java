// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A field of a formatted text, by its number or its attribute, and where its text
// begins and ends. The hash code is a JDK 25's answer: the field in the top byte, the
// begin index or'ed in sixteen bits up, the end index or'ed in; the attribute is not in it.
package java.text;

import java.util.Objects;

public class FieldPosition {

    private final int field;

    private final Format.Field attribute;

    private int beginIndex;

    private int endIndex;

    public FieldPosition(int field) {
        this(null, field);
    }

    public FieldPosition(Format.Field attribute) {
        this(attribute, -1);
    }

    public FieldPosition(Format.Field attribute, int fieldID) {
        this.attribute = attribute;
        this.field = fieldID;
    }

    public Format.Field getFieldAttribute() {
        return attribute;
    }

    public int getField() {
        return field;
    }

    public int getBeginIndex() {
        return beginIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public void setBeginIndex(int bi) {
        beginIndex = bi;
    }

    public void setEndIndex(int ei) {
        endIndex = ei;
    }

    public boolean equals(Object other) {
        return other instanceof FieldPosition that && that.field == field && that.beginIndex == beginIndex
                && that.endIndex == endIndex && Objects.equals(that.attribute, attribute);
    }

    public int hashCode() {
        return (field << 24) | (beginIndex << 16) | endIndex;
    }

    public String toString() {
        return getClass().getName() + "[field=" + field + ",attribute=" + attribute + ",beginIndex=" + beginIndex
                + ",endIndex=" + endIndex + "]";
    }
}
