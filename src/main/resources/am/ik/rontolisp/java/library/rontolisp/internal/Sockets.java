// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The program's own TCP sockets (rontolisp:tcp-*) as java.net's Socket and
// ServerSocket reach them: a socket is the handle the host answered, and a call that
// fails answers the text of why, of which failure() makes the exception a JDK throws
// with the host's words. Where the threads of a program take turns on one stack, a
// connect, an accept and a read are where a thread waits among the others.
package rontolisp.internal;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class Sockets {

    private Sockets() {
    }

    // What a failed call was doing, which decides the exception of its failure.
    public static final int USING = 0;

    public static final int CONNECTING = 1;

    public static final int BINDING = 2;

    // The options option() gives the host's socket: TCP_NODELAY and SO_KEEPALIVE (1 on,
    // 0 off), SO_LINGER (seconds, -1 off), SO_OOBINLINE (1 on, 0 off), IP_TOS (0 to
    // 255), and URGENT, a byte of urgent data sent.
    public static final int NO_DELAY = 0;

    public static final int KEEP_ALIVE = 1;

    public static final int LINGER = 2;

    public static final int OOB_INLINE = 3;

    public static final int TRAFFIC_CLASS = 4;

    public static final int URGENT = 5;

    // A socket not connected yet, bound to host:port -- every interface for a null
    // host, a port of the host's choosing for 0 -- or for a port of -1 not bound; or the
    // text of why there is none.
    public static native Object socket(String host, int port);

    // SOCKET connected to host:port -- a name the host looks up -- within millis, 0 for
    // as long as the host's connect waits: the socket, or the text of why not. A close
    // ends the wait.
    public static native Object connect(Object socket, String host, int port, int millis);

    // A listener on host:port, on every interface for a null host, or the text of why
    // there is none.
    public static native Object listen(String host, int port);

    // The next connection a listener takes, or the text of why there is none.
    public static native Object accept(Object listener);

    // At least one byte into b[off, off + len) once one has come, then what else has
    // come, up to len: the count, -1 at the end, or the text of what failed. len is
    // above 0.
    public static native Object read(Object socket, byte[] b, int off, int len);

    // What has come into b[off, off + len) with no wait: the count, 0 for none, or the
    // text of what failed.
    public static native Object take(Object socket, byte[] b, int off, int len);

    // b[off, off + len) sent before it answers: null, or the text of what failed.
    public static native Object write(Object socket, byte[] b, int off, int len);

    // The read deadline, or a listener's accept deadline, in milliseconds, 0 for none:
    // null, or the text of what failed.
    public static native Object deadline(Object socket, int millis);

    // Half of a connected socket ended -- its output, what is buffered sent first, or
    // its input: null, or the text of what failed.
    public static native Object shutdown(Object socket, boolean output);

    // An option of a connected socket given to the host, or for URGENT a byte of urgent
    // data sent after what is buffered: null, or the text of what failed. A host with no
    // knob for an option answers null; one that sends no urgent data says so.
    public static native Object option(Object socket, int option, int value);

    public static native void close(Object handle);

    public static native int localPort(Object handle);

    // An address in numbers; null where the host does not say.
    public static native String localAddress(Object handle);

    public static native String peerAddress(Object handle);

    public static native int peerPort(Object handle);

    // Whether a call answered the text of a failure.
    public static native boolean failed(Object answer);

    public static native String text(Object answer);

    // The exception of a failure: the host's words after what the operator said, in
    // the class a JDK throws them with. HOST is the name a connect, a listen or a bind
    // was given, whose lookup failed where the host's words are the name or say so;
    // DOING what the call was doing: a bind's failure is a BindException, a refused
    // connect a ConnectException.
    public static IOException failure(Object answer, String host, int doing) {
        String text = text(answer);
        if (text.contains("--component or --native")) {
            // the words of what a plain Preview 1 module lacks
            return new SocketException("a socket needs the host's network: compile with --component or with"
                    + " --native; a Preview 1 .wasm has none");
        }
        String why = words(text);
        String lower = why.toLowerCase();
        if (host != null && (why.equals(host) || lower.contains("lookup address") || lower.contains("not known")
                || lower.contains("nodename nor servname"))) {
            return new UnknownHostException(host);
        }
        if (lower.startsWith("read timed out") || lower.startsWith("accept timed out")
                || lower.startsWith("connect timed out")) {
            return new SocketTimeoutException(why);
        }
        // what a JDK says of the address in use, of one no interface has, of the host no
        // route reaches and of a connection its peer reset, which the C library under a
        // --native executable words otherwise
        if (lower.contains("address already in use") || lower.contains("address in use")) {
            return new BindException("Address already in use");
        }
        if (lower.equals("address not available")) {
            why = "Cannot assign requested address";
        }
        if (lower.equals("connection reset by peer")) {
            why = "Connection reset";
        }
        if (doing == BINDING) {
            return new BindException(why);
        }
        if (lower.contains("no route to host") || lower.contains("host is unreachable")) {
            return new NoRouteToHostException("No route to host");
        }
        // "cannot connect" are a Cloudflare Worker's words for a connect nothing took,
        // whatever the reason
        if (doing == CONNECTING && (lower.contains("refused") || lower.contains("timed out")
                || lower.contains("unreachable") || lower.contains("cannot connect"))) {
            return new ConnectException(why);
        }
        return new SocketException(why);
    }

    // What the host said: the text after the operator's own words, `OP: cannot connect
    // to H:P: `, `OP: cannot listen on H:P: `, `OP: cannot bind to H:P: ` or `OP: `.
    private static String words(String text) {
        String[] said = { "cannot connect to ", "cannot listen on ", "cannot bind to " };
        for (String words : said) {
            int at = text.indexOf(words);
            if (at >= 0) {
                int port = text.indexOf(':', at + words.length());
                int after = port < 0 ? -1 : text.indexOf(": ", port + 1);
                return after < 0 ? "" : text.substring(after + 2);
            }
        }
        int colon = text.indexOf(": ");
        int space = text.indexOf(' ');
        return colon >= 0 && space > colon ? text.substring(colon + 2) : text;
    }
}
