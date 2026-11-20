import java.util.*;
import java.util.regex.*;

// Scanner: the state its text shows after each operation, what hasNext keeps for next.
public class Scan3 {
    static String state(Scanner sc) {
        String t = sc.toString();
        int cut = t.indexOf("[group separator");
        return t.substring("java.util.Scanner".length(), cut);
    }

    static void step(Scanner sc, String what, java.util.concurrent.Callable<Object> r) {
        String v;
        try {
            v = String.valueOf(r.call());
        } catch (Exception e) {
            v = e.getClass().getSimpleName();
        }
        String m;
        try {
            MatchResult mr = sc.match();
            m = mr.start() + "-" + mr.end();
        } catch (IllegalStateException e) {
            m = "none";
        }
        System.out.println(what + " -> " + v.replace("\n", "\\n") + " match " + m + " " + state(sc));
    }

    public static void main(String[] args) {
        Scanner a = new Scanner("  abc def");
        step(a, "new", () -> "");
        step(a, "hasNext", () -> a.hasNext());
        step(a, "next", () -> a.next());
        step(a, "hasNext", () -> a.hasNext());
        step(a, "next", () -> a.next());
        step(a, "hasNext", () -> a.hasNext());
        Scanner b = new Scanner("12 34");
        step(b, "hasNextInt", () -> b.hasNextInt());
        step(b, "nextInt", () -> b.nextInt());
        step(b, "nextInt", () -> b.nextInt());
        step(b, "hasNextInt", () -> b.hasNextInt());
        Scanner c = new Scanner("x y\n");
        step(c, "next", () -> c.next());
        step(c, "nextLine", () -> c.nextLine());
        step(c, "hasNextLine", () -> c.hasNextLine());
        Scanner d = new Scanner("x y\nz");
        step(d, "nextLine", () -> d.nextLine());
        step(d, "hasNextLine", () -> d.hasNextLine());
        step(d, "nextLine", () -> d.nextLine());
        step(d, "hasNextLine", () -> d.hasNextLine());
        Scanner e = new Scanner("abc");
        step(e, "skip a", () -> e.skip("a"));
        step(e, "skip x", () -> e.skip("x"));
        step(e, "skip bc", () -> e.skip("bc"));
        Scanner f = new Scanner("abc");
        step(f, "skip abc", () -> f.skip("abc"));
        Scanner g = new Scanner("a b");
        step(g, "findInLine b", () -> g.findInLine("b"));
        Scanner h = new Scanner("a b");
        step(h, "findWithinHorizon b 0", () -> h.findWithinHorizon("b", 0));
        Scanner i = new Scanner("a b");
        step(i, "findWithinHorizon a 1", () -> i.findWithinHorizon("a", 1));
        step(i, "findWithinHorizon b 2", () -> i.findWithinHorizon("b", 2));
        Scanner j = new Scanner("one two");
        step(j, "hasNext", () -> j.hasNext());
        step(j, "useDelimiter o", () -> j.useDelimiter("o") == j);
        step(j, "next", () -> j.next());
        step(j, "next", () -> j.next());
        Scanner k = new Scanner("one two");
        step(k, "hasNext(o.e)", () -> k.hasNext("o.e"));
        step(k, "next", () -> k.next());
        Scanner l = new Scanner("5 6");
        step(l, "hasNextInt", () -> l.hasNextInt());
        step(l, "next", () -> l.next());
        step(l, "hasNextInt", () -> l.hasNextInt());
        step(l, "nextLine", () -> l.nextLine());
        Scanner n = new Scanner("7 8");
        step(n, "hasNextInt", () -> n.hasNextInt());
        step(n, "hasNextLine", () -> n.hasNextLine());
        step(n, "nextInt", () -> n.nextInt());
        Scanner o = new Scanner("7 8");
        step(o, "hasNextInt", () -> o.hasNextInt());
        step(o, "useRadix 16", () -> o.useRadix(16) == o);
        step(o, "nextInt", () -> o.nextInt());
        Scanner p = new Scanner("a,b");
        step(p, "hasNext", () -> p.hasNext());
        step(p, "useDelimiter ,", () -> p.useDelimiter(","));
        step(p, "next", () -> p.next());
        step(p, "next", () -> p.next());
        Scanner q = new Scanner("w1 w2 w3");
        step(q, "hasNext(w2)", () -> q.hasNext("w2"));
        step(q, "hasNext(w1)", () -> q.hasNext("w1"));
        step(q, "next(w\\d)", () -> q.next("w\\d"));
        Scanner r = new Scanner("");
        step(r, "hasNext", () -> r.hasNext());
        step(r, "hasNextLine", () -> r.hasNextLine());
        Scanner s = new Scanner("abc");
        step(s, "hasNext(a)", () -> s.hasNext("a"));
        step(s, "next", () -> s.next());
        Scanner t = new Scanner("12 x");
        step(t, "hasNextInt", () -> t.hasNextInt());
        step(t, "hasNext(\\d+)", () -> t.hasNext("\\d+"));
        step(t, "nextInt", () -> t.nextInt());
        step(t, "hasNextDouble", () -> t.hasNextDouble());
        step(t, "nextInt", () -> t.nextInt());
        step(t, "nextDouble", () -> t.nextDouble());
    }
}
