import java.util.*;
import java.util.function.*;

// Scanner: what a hasNext of a type keeps for the next of that type, across the other
// operations.
public class Scan5 {
    static void run(String what, String input, Consumer<Scanner> before, Function<Scanner, Object> after) {
        Scanner sc = new Scanner(input);
        String v;
        try {
            before.accept(sc);
            v = String.valueOf(after.apply(sc));
        } catch (Exception e) {
            v = e.getClass().getSimpleName();
        }
        String rest = sc.hasNextLine() ? sc.nextLine() : "-";
        System.out.println(what + ": " + v + " | rest [" + rest + "]");
    }

    public static void main(String[] args) {
        run("hasNextInt next nextInt", "1 2 3", s -> { s.hasNextInt(); s.next(); }, s -> s.nextInt());
        run("hasNextInt findInLine nextInt", "1 2 x", s -> { s.hasNextInt(); s.findInLine("2"); }, s -> s.nextInt());
        run("hasNextInt skip nextInt", "1 x", s -> { s.hasNextInt(); s.skip("1"); }, s -> s.nextInt());
        run("hasNextInt skip ws nextInt", "1 x", s -> { s.hasNextInt(); s.skip("\\s*"); }, s -> s.nextInt());
        run("hasNextInt horizon nextInt", "1 x 2", s -> { s.hasNextInt(); s.findWithinHorizon("x", 0); }, s -> s.nextInt());
        run("hasNextInt nextLine nextInt", "1 a\n2", s -> { s.hasNextInt(); s.nextLine(); }, s -> s.nextInt());
        run("hasNextInt hasNextLine nextInt", "1 a\n2", s -> { s.hasNextInt(); s.hasNextLine(); }, s -> s.nextInt());
        run("hasNextInt hasNext nextInt", "1 a", s -> { s.hasNextInt(); s.hasNext(); }, s -> s.nextInt());
        run("hasNextInt hasNext(p) nextInt", "1 a", s -> { s.hasNextInt(); s.hasNext("\\d"); }, s -> s.nextInt());
        run("hasNextInt hasNextDouble nextInt", "1 a", s -> { s.hasNextInt(); s.hasNextDouble(); }, s -> s.nextInt());
        run("hasNextInt hasNextBoolean(false) nextInt", "1 a", s -> { s.hasNextInt(); s.hasNextBoolean(); }, s -> s.nextInt());
        run("hasNextInt nextLong", "1 a", s -> s.hasNextInt(), s -> s.nextLong());
        run("hasNextLong nextInt", "1 a", s -> s.hasNextLong(), s -> s.nextInt());
        run("hasNextInt next", "1 a", s -> s.hasNextInt(), s -> s.next());
        run("hasNextInt nextDouble", "1 a", s -> s.hasNextInt(), s -> s.nextDouble());
        run("hasNextInt useDelimiter next", "1 a", s -> { s.hasNextInt(); s.useDelimiter("a"); }, s -> s.next());
        run("hasNextInt useDelimiter nextInt", "1 a", s -> { s.hasNextInt(); s.useDelimiter("a"); }, s -> s.nextInt());
        run("hasNextInt reset nextInt", "1 a", s -> { s.hasNextInt(); s.useDelimiter("a"); s.reset(); s.useDelimiter("a"); }, s -> s.nextInt());
        run("hasNextInt useLocale nextInt", "1 a", s -> { s.hasNextInt(); s.useDelimiter("a"); s.useLocale(Locale.UK); }, s -> s.nextInt());
        run("hasNextInt(16) nextInt(16)", "ff a", s -> { s.hasNextInt(16); s.useDelimiter("a"); }, s -> s.nextInt(16));
        run("hasNextInt(16) useRadix nextInt", "ff a", s -> { s.hasNextInt(16); s.useDelimiter("a"); s.useRadix(16); }, s -> s.nextInt());
        run("hasNext(p) useDelimiter next(p)", "ab c", s -> { s.hasNext("a."); s.useDelimiter("c"); }, s -> s.next("a."));
        run("hasNext(p) useDelimiter next()", "ab c", s -> { s.hasNext("a."); s.useDelimiter("c"); }, s -> s.next());
        run("hasNext(p) useDelimiter next(q)", "ab c", s -> { s.hasNext("a."); s.useDelimiter("c"); }, s -> s.next("a.*"));
        run("hasNext(p) useDelimiter nextInt", "12 c", s -> { s.hasNext("\\d+"); s.useDelimiter("c"); }, s -> s.nextInt());
        run("hasNextInt useDelimiter hasNextInt", "1 a", s -> { s.hasNextInt(); s.useDelimiter("a"); }, s -> s.hasNextInt());
        run("hasNextInt useDelimiter hasNext(p)", "1 a", s -> { s.hasNextInt(); s.useDelimiter("a"); }, s -> s.hasNext("1"));
        run("hasNextBoolean useDelimiter nextBoolean", "true a", s -> { s.hasNextBoolean(); s.useDelimiter("a"); }, s -> s.nextBoolean());
        run("hasNextBigInteger useDelimiter nextBigInteger", "5 a", s -> { s.hasNextBigInteger(); s.useDelimiter("a"); }, s -> s.nextBigInteger());
        run("hasNextDouble useDelimiter nextDouble", "5 a", s -> { s.hasNextDouble(); s.useDelimiter("a"); }, s -> s.nextDouble());
        run("hasNextDouble useDelimiter nextFloat", "5 a", s -> { s.hasNextDouble(); s.useDelimiter("a"); }, s -> s.nextFloat());
        run("hasNextInt useDelimiter nextShort", "5 a", s -> { s.hasNextInt(); s.useDelimiter("a"); }, s -> s.nextShort());
        run("hasNextShort useDelimiter nextInt", "5 a", s -> { s.hasNextShort(); s.useDelimiter("a"); }, s -> s.nextInt());
        run("hasNextLine useDelimiter nextLine", "x y\nz", s -> { s.hasNextLine(); s.useDelimiter("a"); }, s -> s.nextLine());
        run("hasNextInt close", "1 a", s -> { s.hasNextInt(); s.close(); }, s -> s.nextInt());
    }
}
