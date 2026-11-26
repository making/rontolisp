// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An address a program was handed or wrote in numbers: nothing here asks a name
// service, so the one name with an address is localhost. How a JDK 25 reads numbers
// -- one to four of them, the last filling the octets the others leave -- is its
// answers to a probe.
package java.net;

public class InetAddress {

    private final String name;

    private final String literal;

    InetAddress(String name, String literal) {
        this.name = name;
        this.literal = literal;
    }

    // The address a text spells: numbers, or localhost; null for any other name.
    static InetAddress spelled(String host) {
        if (host.isEmpty() || host.equalsIgnoreCase("localhost")) {
            return new InetAddress(host.isEmpty() ? "localhost" : host, "127.0.0.1");
        }
        int[] octets = numbers(host);
        return octets == null ? null : new InetAddress(null, dotted(octets));
    }

    private static String dotted(int[] octets) {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    // The four octets of one to four numbers with dots between: each but the last is an
    // octet, and the last fills the octets left.
    private static int[] numbers(String text) {
        long[] parts = new long[4];
        int last = 0;
        boolean digits = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (!digits || last == 3 || parts[last] > 255) {
                    return null;
                }
                last++;
                digits = false;
            }
            else if (c >= '0' && c <= '9') {
                parts[last] = parts[last] * 10 + (c - '0');
                digits = true;
                if (parts[last] > 0xFFFFFFFFL) {
                    return null;
                }
            }
            else {
                return null;
            }
        }
        if (!digits || parts[last] >= 1L << (8 * (4 - last))) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < last; i++) {
            octets[i] = (int) parts[i];
        }
        long rest = parts[last];
        for (int i = 3; i >= last; i--) {
            octets[i] = (int) (rest & 0xff);
            rest >>= 8;
        }
        return octets;
    }

    public static InetAddress getByName(String host) throws UnknownHostException {
        InetAddress found = host == null ? getLoopbackAddress() : spelled(host);
        if (found == null) {
            throw new UnknownHostException(host + ": no name is looked up by the experimental Java subset");
        }
        return found;
    }

    public static InetAddress[] getAllByName(String host) throws UnknownHostException {
        return new InetAddress[] { getByName(host) };
    }

    public static InetAddress getByAddress(byte[] addr) throws UnknownHostException {
        return getByAddress(null, addr);
    }

    public static InetAddress getByAddress(String host, byte[] addr) throws UnknownHostException {
        if (addr == null || addr.length != 4) {
            throw new UnknownHostException("addr is of illegal length");
        }
        return new InetAddress(host,
                (addr[0] & 0xff) + "." + (addr[1] & 0xff) + "." + (addr[2] & 0xff) + "." + (addr[3] & 0xff));
    }

    public byte[] getAddress() {
        int[] octets = numbers(literal);
        byte[] made = new byte[4];
        for (int i = 0; octets != null && i < 4; i++) {
            made[i] = (byte) octets[i];
        }
        return made;
    }

    public static InetAddress getLoopbackAddress() {
        return new InetAddress("localhost", "127.0.0.1");
    }

    public String getHostAddress() {
        return literal;
    }

    public String getHostName() {
        return name == null ? literal : name;
    }

    public boolean isLoopbackAddress() {
        return literal.startsWith("127.") || literal.equals("::1") || literal.equals("0:0:0:0:0:0:0:1");
    }

    public boolean isAnyLocalAddress() {
        return literal.equals("0.0.0.0") || literal.equals("::") || literal.equals("0:0:0:0:0:0:0:0");
    }

    public boolean equals(Object o) {
        return o instanceof InetAddress && ((InetAddress) o).literal.equals(literal);
    }

    public int hashCode() {
        int[] parts = numbers(literal);
        if (parts == null) {
            return literal.hashCode();
        }
        return (parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3];
    }

    public String toString() {
        return (name == null ? "" : name) + "/" + literal;
    }
}
