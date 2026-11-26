// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// The loggers of a name, kept for as long as the program runs (a JDK's may be collected
// once nothing else holds them), and the tree of the parts of their names that gives each
// its parent. The configuration is the one a JDK reads where no file is named: the root
// at INFO with a ConsoleHandler, made where the root's handlers are first asked for, or
// the handler of the host's log where the build hands the records to it (HostHandler).
// No file is read -- no target has java.util.logging.config.file --: a program sets its
// levels and handlers itself.
public class LogManager {

    private static final LogManager manager = new LogManager();

    // the names in the order a JDK answers them: its table's, "" and "global" first
    private final ConcurrentHashMap<String, Logger> named = new ConcurrentHashMap<>();

    private final Node tree = new Node(null);

    private final Logger root;

    // whether the configuration is the default one, or was reset
    private boolean configured = true;

    // A part of a name: the logger of the name ending there, the parts below it.
    private static final class Node {

        final Node parent;

        // a map of the kind the names are in: no other kind of map to carry
        final ConcurrentHashMap<String, Node> children = new ConcurrentHashMap<>();

        Logger logger;

        Node(Node parent) {
            this.parent = parent;
        }

        Node child(String part) {
            Node found = children.get(part);
            if (found == null) {
                found = new Node(this);
                children.put(part, found);
            }
            return found;
        }

    }

    protected LogManager() {
        root = new Logger("", null);
        root.setLevel(Level.INFO);
        root.defaults = true;
        addLogger(root);
        addLogger(Logger.global);
    }

    public static LogManager getLogManager() {
        return manager;
    }

    /** The handler the root has where nothing else was asked for. */
    static Handler defaultHandler() {
        return toHost() ? new HostHandler() : new ConsoleHandler();
    }

    // whether the build hands the records to its host's log (--wasi-logging): answered
    // where the program is compiled, so the handler not made is in no program
    private static native boolean toHost();

    /** The logger of a name, made where there is none. */
    synchronized Logger demandLogger(String name) {
        Logger found = named.get(Objects.requireNonNull(name));
        if (found == null) {
            found = new Logger(name, null);
            addLogger(found);
        }
        return found;
    }

    public synchronized boolean addLogger(Logger logger) {
        String name = Objects.requireNonNull(logger.getName());
        if (named.containsKey(name)) {
            return false;
        }
        named.put(name, logger);
        // the node of the name: a part ends at a dot that is not the first character
        // left, so a name's leading dots belong to its first part, and the parts of
        // "a.b" and "a.b." are one node
        Node node = tree;
        String rest = name;
        while (!rest.isEmpty()) {
            int dot = rest.indexOf('.');
            if (dot > 0) {
                node = node.child(rest.substring(0, dot));
                rest = rest.substring(dot + 1);
            }
            else {
                node = node.child(rest);
                rest = "";
            }
        }
        node.logger = logger;
        for (Node above = node.parent; above != null; above = above.parent) {
            if (above.logger != null) {
                logger.placedUnder(above.logger);
                break;
            }
        }
        below(node, logger);
        return true;
    }

    /** The loggers nearest under a node placed under its logger. */
    private static void below(Node node, Logger logger) {
        for (Node child : node.children.values()) {
            if (child.logger != null) {
                child.logger.placedUnder(logger);
            }
            else {
                below(child, logger);
            }
        }
    }

    public Logger getLogger(String name) {
        return named.get(name);
    }

    public Enumeration<String> getLoggerNames() {
        return Collections.enumeration(new ArrayList<>(named.keySet()));
    }

    /** The property of the default configuration, or null. */
    public String getProperty(String name) {
        Objects.requireNonNull(name);
        if (!configured) {
            return null;
        }
        return switch (name) {
            case "handlers" -> toHost() ? "java.util.logging.HostHandler" : "java.util.logging.ConsoleHandler";
            case ".level", "java.util.logging.ConsoleHandler.level" -> "INFO";
            case "java.util.logging.ConsoleHandler.formatter" -> "java.util.logging.SimpleFormatter";
            default -> null;
        };
    }

    /**
     * Every logger's handlers closed and taken off, every level but the root's (INFO)
     * unset, no property left.
     */
    public void reset() {
        ArrayList<Logger> loggers;
        synchronized (this) {
            configured = false;
            loggers = new ArrayList<>(named.values());
        }
        for (Logger logger : loggers) {
            for (Handler handler : logger.removeHandlers()) {
                try {
                    handler.close();
                }
                catch (Exception e) {
                    // a handler that cannot be closed is taken off anyway
                }
            }
            logger.setLevel(logger == root ? Level.INFO : null);
        }
    }

    /** The default configuration again: reset, then the root's handler to make. */
    public void readConfiguration() throws IOException {
        reset();
        synchronized (this) {
            configured = true;
            root.defaults = true;
        }
    }

}
