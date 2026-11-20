import java.util.StringJoiner;

// StringJoiner: what each member answers and refuses.
public class Joiner {
    static void show(String what, StringJoiner j) {
        System.out.println(what + ": [" + j + "] " + j.length() + " " + j.toString().length());
    }

    static void tryIt(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ok");
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        StringJoiner a = new StringJoiner(", ");
        show("empty", a);
        a.add("x");
        show("one", a);
        a.add("y").add("z");
        show("three", a);
        a.add(null);
        show("null added", a);
        a.add("");
        show("empty added", a);

        StringJoiner b = new StringJoiner("-", "{", "}");
        show("b empty", b);
        b.setEmptyValue("EMPTY");
        show("b empty value", b);
        b.add("");
        show("b one empty", b);
        b.add("q");
        show("b two", b);

        StringJoiner c = new StringJoiner("|", "<", ">");
        c.setEmptyValue("none");
        show("c", c);
        StringJoiner d = new StringJoiner("+", "(", ")");
        d.setEmptyValue("dnone");
        c.merge(d);
        show("c merged empty d", c);
        d.add("1").add("2");
        c.merge(d);
        show("c merged d", c);
        c.merge(d);
        show("c merged d again", c);
        c.add("3");
        show("c added", c);
        show("d", d);

        StringJoiner e = new StringJoiner(",", "[", "]");
        e.add("a").add("b");
        e.merge(e);
        show("e merged itself", e);
        StringJoiner f = new StringJoiner(",", "[", "]");
        f.merge(f);
        show("f merged itself empty", f);

        StringBuilder delim = new StringBuilder("/");
        StringBuilder pre = new StringBuilder("^");
        StringJoiner g = new StringJoiner(delim, pre, "$");
        delim.append("/");
        pre.append("^");
        StringBuilder item = new StringBuilder("m");
        g.add(item);
        item.append("n");
        g.add(item);
        StringBuilder empty = new StringBuilder("ev");
        StringJoiner h = new StringJoiner(",");
        h.setEmptyValue(empty);
        empty.append("!");
        show("g", g);
        show("h", h);

        StringJoiner set = new StringJoiner(",");
        System.out.println("setEmptyValue returns this: " + (set.setEmptyValue("x") == set));
        System.out.println("add returns this: " + (set.add("y") == set));
        System.out.println("merge returns this: " + (set.merge(new StringJoiner(";")) == set));

        tryIt("new null", () -> new StringJoiner(null));
        tryIt("new null prefix", () -> new StringJoiner(",", null, "]"));
        tryIt("new null suffix", () -> new StringJoiner(",", "[", null));
        tryIt("new null delimiter 3", () -> new StringJoiner(null, "[", "]"));
        tryIt("new all null", () -> new StringJoiner(null, null, null));
        tryIt("setEmptyValue null", () -> new StringJoiner(",").setEmptyValue(null));
        tryIt("merge null", () -> new StringJoiner(",").merge(null));
        tryIt("add null", () -> new StringJoiner(",").add(null));

        StringJoiner k = new StringJoiner(",", "[", "]");
        StringJoiner other = new StringJoiner(";", "<", ">");
        other.add("p");
        other.add("q");
        k.merge(other);
        show("k merged", k);
        k.add("r");
        show("k then added", k);
        System.out.println("equals " + k.equals(k) + " " + new StringJoiner(",").equals(new StringJoiner(",")));
        StringJoiner l = new StringJoiner("");
        l.add("a").add("b").add("c");
        show("l", l);
        StringJoiner m = new StringJoiner(", ", "", "");
        m.setEmptyValue("");
        show("m", m);
        m.add("only");
        show("m one", m);
        StringJoiner n = new StringJoiner("..", "[", "]");
        n.setEmptyValue("E");
        StringJoiner n2 = new StringJoiner(",");
        n2.add("");
        n.merge(n2);
        show("n merged one empty", n);
    }
}
