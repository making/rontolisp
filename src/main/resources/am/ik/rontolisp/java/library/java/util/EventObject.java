// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Serializable;

public class EventObject implements Serializable {

    protected transient Object source;

    public EventObject(Object source) {
        if (source == null) {
            throw new IllegalArgumentException("null source");
        }
        this.source = source;
    }

    public Object getSource() {
        return source;
    }

    // the text a JDK 25 writes: the class's binary name and the source
    public String toString() {
        return getClass().getName() + "[source=" + source + "]";
    }
}
