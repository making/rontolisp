import java.util.ArrayList;
import java.util.List;

public class Raised {
    sealed interface Shape permits Circle, Square {}
    record Circle(int r) implements Shape {}
    record Square(int s) implements Shape {}

    static int div(int a, int b) { return a / b; }

    static String show(Throwable t) {
        return t.getClass().getName() + " | " + t.getMessage() + " | " + t.getCause() + " | " + t;
    }

    static void run(String name, Runnable body) {
        try {
            body.run();
            System.out.println(name + ": nothing");
        } catch (RuntimeException e) {
            System.out.println(name + ": " + show(e));
        }
    }

    public static void main(String[] args) {
        int[] xs = new int[2];
        String s = "abc";
        Object o = "text";
        String none = args.length > 5 ? "x" : null;
        run("div", () -> System.out.println(div(1, args.length)));
        run("rem", () -> System.out.println(7 % args.length));
        run("index", () -> System.out.println(xs[2 + args.length]));
        run("store", () -> xs[-1] = 3);
        run("null", () -> System.out.println(none.length()));
        run("negative", () -> System.out.println(new int[args.length - 1].length));
        run("charAt", () -> System.out.println(s.charAt(3)));
        run("substring", () -> System.out.println(s.substring(2, 1)));
        run("cast", () -> System.out.println((Integer) o));
        run("parse", () -> System.out.println(Integer.parseInt("x1")));
        run("format", () -> System.out.println(String.format("%d", "x")));
        run("list", () -> System.out.println(new ArrayList<String>().get(0)));
        run("iterator", () -> System.out.println(List.of().iterator().next()));
        run("unbox", () -> { Integer boxed = args.length > 5 ? 1 : null; int i = boxed; System.out.println(i); });
        // the same exception through two clauses, a finally and a rethrow
        try {
            try {
                try {
                    System.out.println(div(1, args.length));
                } catch (IllegalStateException e) {
                    System.out.println("not this one");
                } finally {
                    System.out.println("finally");
                }
            } catch (ArithmeticException e) {
                System.out.println("inner " + show(e));
                throw e;
            }
        } catch (RuntimeException e) {
            System.out.println("outer " + show(e) + " " + (e instanceof ArithmeticException));
        }
        // an exception of the run time kept and thrown again later is the same object
        RuntimeException kept = null;
        try {
            System.out.println(xs[5]);
        } catch (RuntimeException e) {
            kept = e;
            e.addSuppressed(new IllegalStateException("s"));
        }
        try {
            throw kept;
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println((e == kept) + " " + e.getSuppressed().length + " " + show(e));
        }
        try {
            throw new IllegalStateException("wrapped", kept);
        } catch (Exception e) {
            System.out.println(show(e));
        }
        try {
            System.out.println(div(2, args.length));
        } catch (Throwable t) {
            System.out.println("throwable " + show(t) + " " + (t instanceof Exception) + " " + (t instanceof Error));
        }
        try {
            System.out.println(div(2, args.length));
        } catch (Exception e) {
            System.out.println("exception " + show(e));
        }
    }
}
