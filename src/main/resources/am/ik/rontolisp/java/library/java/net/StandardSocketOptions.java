// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The standard options by name and type. An option is equal to itself only and
// prints its name, as a JDK 25's answered. IP_MULTICAST_IF
// is not here: its type, NetworkInterface, is not in the subset.
package java.net;

public final class StandardSocketOptions {

    private StandardSocketOptions() {
    }

    public static final SocketOption<Boolean> IP_MULTICAST_LOOP = new Named<>("IP_MULTICAST_LOOP", Boolean.class);

    public static final SocketOption<Integer> IP_MULTICAST_TTL = new Named<>("IP_MULTICAST_TTL", Integer.class);

    public static final SocketOption<Integer> IP_TOS = new Named<>("IP_TOS", Integer.class);

    public static final SocketOption<Boolean> SO_BROADCAST = new Named<>("SO_BROADCAST", Boolean.class);

    public static final SocketOption<Boolean> SO_KEEPALIVE = new Named<>("SO_KEEPALIVE", Boolean.class);

    public static final SocketOption<Integer> SO_LINGER = new Named<>("SO_LINGER", Integer.class);

    public static final SocketOption<Integer> SO_RCVBUF = new Named<>("SO_RCVBUF", Integer.class);

    public static final SocketOption<Boolean> SO_REUSEADDR = new Named<>("SO_REUSEADDR", Boolean.class);

    public static final SocketOption<Boolean> SO_REUSEPORT = new Named<>("SO_REUSEPORT", Boolean.class);

    public static final SocketOption<Integer> SO_SNDBUF = new Named<>("SO_SNDBUF", Integer.class);

    public static final SocketOption<Boolean> TCP_NODELAY = new Named<>("TCP_NODELAY", Boolean.class);

    private static final class Named<T> implements SocketOption<T> {

        private final String name;

        private final Class<T> type;

        Named(String name, Class<T> type) {
            this.name = name;
            this.type = type;
        }

        public String name() {
            return name;
        }

        public Class<T> type() {
            return type;
        }

        public String toString() {
            return name;
        }
    }
}
