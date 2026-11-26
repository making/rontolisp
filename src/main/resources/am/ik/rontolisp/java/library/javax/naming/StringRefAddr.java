// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

public class StringRefAddr extends RefAddr {

    private final String contents;

    public StringRefAddr(String addrType, String addr) {
        super(addrType);
        this.contents = addr;
    }

    public Object getContent() {
        return contents;
    }

}
