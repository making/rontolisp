// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.naming;

import java.util.Arrays;

// The bytes are copied in and handed out as they are kept; the text shows the first 32,
// each an int in hexadecimal and a space, and " ..." and a line's end after them where
// there are 32 or more, as a JDK 25 writes them.
public class BinaryRefAddr extends RefAddr {

    private final byte[] buf;

    public BinaryRefAddr(String addrType, byte[] src) {
        this(addrType, src, 0, src.length);
    }

    public BinaryRefAddr(String addrType, byte[] src, int offset, int count) {
        super(addrType);
        buf = new byte[count];
        System.arraycopy(src, offset, buf, 0, count);
    }

    public Object getContent() {
        return buf;
    }

    public boolean equals(Object obj) {
        return obj instanceof BinaryRefAddr other && addrType.compareTo(other.addrType) == 0
                && Arrays.equals(buf, other.buf);
    }

    public int hashCode() {
        int hash = addrType.hashCode();
        for (byte b : buf) {
            hash += b;
        }
        return hash;
    }

    public String toString() {
        StringBuilder text = new StringBuilder("Address Type: ").append(addrType).append("\nAddressContents: ");
        for (int i = 0; i < buf.length && i < 32; i++) {
            text.append(Integer.toHexString(buf[i])).append(' ');
        }
        return buf.length >= 32 ? text.append(" ...\n").toString() : text.toString();
    }

}
