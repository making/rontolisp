// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.stream.Stream;

// The drivers registered, in the order they were. The drivers the program's
// META-INF/services/java.sql.Driver names are made once, at the first lookup
// (getConnection of a url, getDriver, getDrivers, drivers): a driver registers itself, as
// the class's initialization of a JDBC driver does, and the first failure ends the
// making, unreported. Each lookup and registration is written to the log writer in the
// words a JDK 25 writes.
public class DriverManager {

    private static final class Registered {

        final Driver driver;

        final DriverAction action;

        Registered(Driver driver, DriverAction action) {
            this.driver = driver;
            this.action = action;
        }
    }

    private static final ArrayList<Registered> registered = new ArrayList<>();

    private static boolean loaded;

    private static volatile int loginTimeout;

    private static volatile PrintWriter logWriter;

    private static volatile PrintStream logStream;

    private DriverManager() {
    }

    public static PrintWriter getLogWriter() {
        return logWriter;
    }

    public static void setLogWriter(PrintWriter out) {
        logStream = null;
        logWriter = out;
    }

    public static Connection getConnection(String url, Properties info) throws SQLException {
        return connect(url, info);
    }

    public static Connection getConnection(String url, String user, String password) throws SQLException {
        Properties info = new Properties();
        if (user != null) {
            info.put("user", user);
        }
        if (password != null) {
            info.put("password", password);
        }
        return connect(url, info);
    }

    public static Connection getConnection(String url) throws SQLException {
        return connect(url, new Properties());
    }

    // Each driver in turn; the first that answers a connection gives it, and the first
    // refusal is thrown when none does.
    private static Connection connect(String url, Properties info) throws SQLException {
        if (url == null) {
            throw new SQLException("The url cannot be null", "08001");
        }
        println("DriverManager.getConnection(\"" + url + "\")");
        SQLException reason = null;
        for (Registered each : registeredNow()) {
            println("    trying " + each.driver.getClass().getName());
            try {
                Connection connection = each.driver.connect(url, info);
                if (connection != null) {
                    println("getConnection returning " + each.driver.getClass().getName());
                    return connection;
                }
            }
            catch (SQLException ex) {
                if (reason == null) {
                    reason = ex;
                }
            }
        }
        if (reason != null) {
            println("getConnection failed: " + reason);
            throw reason;
        }
        println("getConnection: no suitable driver found for " + url);
        throw new SQLException("No suitable driver found for " + url, "08001");
    }

    public static Driver getDriver(String url) throws SQLException {
        println("DriverManager.getDriver(\"" + url + "\")");
        for (Registered each : registeredNow()) {
            try {
                if (each.driver.acceptsURL(url)) {
                    println("getDriver returning " + each.driver.getClass().getName());
                    return each.driver;
                }
            }
            catch (SQLException ex) {
                // a driver that cannot tell is passed over
            }
        }
        println("getDriver: no suitable driver");
        throw new SQLException("No suitable driver", "08001");
    }

    public static void registerDriver(Driver driver) throws SQLException {
        registerDriver(driver, null);
    }

    // A driver registered again keeps its place and its first action.
    public static void registerDriver(Driver driver, DriverAction da) throws SQLException {
        if (driver == null) {
            throw new NullPointerException();
        }
        synchronized (registered) {
            if (find(driver) < 0) {
                registered.add(new Registered(driver, da));
            }
        }
        println("registerDriver: " + driver);
    }

    // The driver's action runs before it leaves the list: an action that throws leaves it.
    public static void deregisterDriver(Driver driver) throws SQLException {
        if (driver == null) {
            return;
        }
        println("DriverManager.deregisterDriver: " + driver);
        Registered found;
        synchronized (registered) {
            int at = find(driver);
            found = at < 0 ? null : registered.get(at);
        }
        if (found == null) {
            println("    couldn't find driver to unload");
            return;
        }
        if (found.action != null) {
            found.action.deregister();
        }
        synchronized (registered) {
            registered.remove(found);
        }
    }

    private static int find(Driver driver) {
        for (int i = 0; i < registered.size(); i++) {
            if (registered.get(i).driver == driver) {
                return i;
            }
        }
        return -1;
    }

    public static Enumeration<Driver> getDrivers() {
        return Collections.enumeration(registeredDrivers());
    }

    public static Stream<Driver> drivers() {
        return registeredDrivers().stream();
    }

    private static ArrayList<Driver> registeredDrivers() {
        ArrayList<Driver> drivers = new ArrayList<>();
        for (Registered each : registeredNow()) {
            drivers.add(each.driver);
        }
        return drivers;
    }

    // What is registered once the services' drivers are made, as a copy a driver
    // registering or leaving meanwhile does not change.
    private static ArrayList<Registered> registeredNow() {
        synchronized (DriverManager.class) {
            if (!loaded) {
                loaded = true;
                try {
                    Iterator<Driver> made = ServiceLoader.load(Driver.class).iterator();
                    while (made.hasNext()) {
                        made.next();
                    }
                }
                catch (Throwable failure) {
                    // the first driver that cannot be made ends the making, unreported
                }
                // the subset answers no jdbc.drivers: System.getProperty knows no such key
                println("DriverManager.initialize: jdbc.drivers = " + System.getProperty("jdbc.drivers"));
                println("JDBC DriverManager initialized");
            }
        }
        synchronized (registered) {
            return new ArrayList<>(registered);
        }
    }

    public static void setLoginTimeout(int seconds) {
        loginTimeout = seconds;
    }

    public static int getLoginTimeout() {
        return loginTimeout;
    }

    @Deprecated
    public static void setLogStream(PrintStream out) {
        logStream = out;
        logWriter = out == null ? null : new PrintWriter(out);
    }

    @Deprecated
    public static PrintStream getLogStream() {
        return logStream;
    }

    public static void println(String message) {
        PrintWriter writer = logWriter;
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }

}
