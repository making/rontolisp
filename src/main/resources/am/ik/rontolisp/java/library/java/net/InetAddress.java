// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An address a program was handed or wrote as four numbers: nothing here asks a name
// service, so the one name with an address is localhost.
package java.net;

public class InetAddress {

    private final String name;

    private final String literal;

    InetAddress(String name, String literal) {
        this.name = name;
        this.literal = literal;
    }

    // The address a text spells: four numbers, or localhost; null for any other name.
    static InetAddress spelled(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return new InetAddress(host, "127.0.0.1");
        }
        return numbers(host) == null ? null : new InetAddress(null, host);
    }

    private static int[] numbers(String text) {
        int[] parts = new int[4];
        int part = 0;
        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (digits == 0 || part == 3) {
                    return null;
                }
                part++;
                digits = 0;
            }
            else if (c >= '0' && c <= '9' && digits < 3) {
                parts[part] = parts[part] * 10 + (c - '0');
                digits++;
                if (parts[part] > 255) {
                    return null;
                }
            }
            else {
                return null;
            }
        }
        return part == 3 && digits > 0 ? parts : null;
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
