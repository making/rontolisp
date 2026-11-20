import java.util.*;
import java.util.function.*;

// Scanner: which operations drop what a hasNext of a type kept (the delimiter changed
// in between, so that a value kept and one found again differ).
public class Scan6 {
    static void run(String what, String input, Consumer<Scanner> between, Function<Scanner, Object> after) {
        Scanner sc = new Scanner(input);
        String v;
        try {
            sc.hasNextInt();
            sc.useDelimiter("a");
            between.accept(sc);
            v = String.valueOf(after.apply(sc));
        } catch (Exception e) {
            v = e.getClass().getSimpleName();
        }
        String rest;
        try {
            rest = sc.hasNextLine() ? sc.nextLine() : "-";
        } catch (Exception e) {
            rest = e.getClass().getSimpleName();
        }
        System.out.println(what + ": " + v + " | rest [" + rest + "]");
    }

    public static void main(String[] args) {
        run("nothing", "1 a", s -> { }, s -> s.nextInt());
        run("hasNext", "1 a", s -> s.hasNext(), s -> s.nextInt());
        run("hasNextLine", "1 a", s -> s.hasNextLine(), s -> s.nextInt());
        run("hasNext(1 )", "1 a", s -> s.hasNext("1 "), s -> s.nextInt());
        run("hasNext(x) false", "1 a", s -> s.hasNext("x"), s -> s.nextInt());
        run("hasNextInt false", "1 a", s -> s.hasNextInt(), s -> s.nextInt());
        run("hasNextDouble false", "1 a", s -> s.hasNextDouble(), s -> s.nextInt());
        run("hasNextBoolean false", "1 a", s -> s.hasNextBoolean(), s -> s.nextInt());
        run("useRadix", "1 a", s -> s.useRadix(8), s -> s.nextInt(10));
        run("radix after", "1 a", s -> s.useRadix(8), s -> s.nextInt());
        run("match", "1 a", s -> s.match(), s -> s.nextInt());
        run("delimiter", "1 a", s -> s.delimiter(), s -> s.nextInt());
        run("toString", "1 a", s -> s.toString(), s -> s.nextInt());
        run("nextInt twice", "1 a", s -> s.nextInt(), s -> s.nextInt());
        run("nextInt then hasNextInt", "1 a", s -> s.nextInt(), s -> s.hasNextInt());
        run("nextLong", "1 a", s -> { }, s -> s.nextLong());
        run("nextBigInteger", "1 a", s -> { }, s -> s.nextBigInteger());
        run("next(p)", "1 a", s -> { }, s -> s.next("1"));
        run("next(1 )", "1 a", s -> { }, s -> s.next("1 "));
        run("findInLine nothing", "1 a", s -> s.findInLine("z"), s -> s.nextInt());
        run("horizon nothing", "1 a", s -> s.findWithinHorizon("z", 0), s -> s.nextInt());
        run("skip empty", "1 a", s -> s.skip(""), s -> s.nextInt());
        run("useDelimiter back", "1 a", s -> s.useDelimiter("\\s"), s -> s.nextInt());
    }
}
