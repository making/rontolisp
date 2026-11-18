import java.io.*;
import java.util.*;
import java.util.concurrent.*;

class ExceptionsApp extends Exception {
    final int code;

    ExceptionsApp(String message, int code) {
        super(message);
        this.code = code;
    }
}

public class Checked {
    static String log = "";

    static int risky(int n) throws ExceptionsApp {
        if (n == 1) throw new ExceptionsApp("one", 11);
        if (n == 2) throw new IllegalStateException("two");
        return n;
    }

    // what a compiler lets a body throw: what a clause takes or the method declares; a
    // catch parameter thrown again throws what its try left it, a type parameter of a
    // throws clause is found from what a lambda's body throws, a resource's close()
    // throws where the resource is declared, and a finally that never completes drops
    // what its try threw
    interface Task<X extends Exception> {
        int run() throws X;
    }

    static class Resource implements AutoCloseable {
        final int code;

        Resource(int code) throws ExceptionsApp {
            this.code = risky(code);
        }

        public void close() throws ExceptionsApp {
            throw new ExceptionsApp("closed", code);
        }
    }

    static <X extends Exception> int call(Task<X> task) throws X {
        return task.run();
    }

    static int again(int n) throws ExceptionsApp {
        try {
            return risky(n);
        } catch (Exception e) {
            log += "R";
            throw e;
        }
    }

    static int dropped() {
        for (;;) {
            try {
                again(1);
            } finally {
                break;
            }
        }
        try {
            return again(1);
        } finally {
            return 4;
        }
    }

    static String checked() {
        String out = "" + call(() -> 7) + call(Checked::dropped);
        try {
            out += call(() -> again(5));
            out += call(() -> again(1));
        } catch (ExceptionsApp e) {
            out += " " + e.getMessage() + e.code;
        }
        try (Resource first = new Resource(3); Resource second = new Resource(1)) {
            out += " in" + first.code + second.code;
        } catch (ExceptionsApp e) {
            out += " " + e.getMessage() + e.code + e.getSuppressed().length;
        }
        try (Resource only = new Resource(6)) {
            out += " in" + only.code;
        } catch (ExceptionsApp e) {
            out += " " + e.getMessage() + e.code;
        }
        try {
            out += " " + new String("ab".getBytes("UTF-8"), "ISO-8859-1").length();
            Callable<Integer> task = () -> again(2);
            out += task.call();
        } catch (UnsupportedEncodingException | ExceptionsApp e) {
            out += " never";
        } catch (Exception e) {
            out += " " + e.getMessage();
        }
        Object made = new Resource[0];
        try {
            made = new Resource(1) {
                int more = again(0);
            };
        } catch (ExceptionsApp e) {
            out += " " + (made instanceof Resource[]) + e.code;
        }
        return out + " " + log;
    }

    public static void main(String[] args) {
        System.out.println(checked());
    }
}
