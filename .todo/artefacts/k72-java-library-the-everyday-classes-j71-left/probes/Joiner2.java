import java.util.StringJoiner;

// StringJoiner: which null is refused first, and what of a CharSequence is joined.
public class Joiner2 {
    static void tryIt(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ok");
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static class Seq implements CharSequence {
        public int length() { return 2; }
        public char charAt(int i) { return "cs".charAt(i); }
        public CharSequence subSequence(int s, int e) { return "cs".substring(s, e); }
        public String toString() { return "TS"; }
    }

    public static void main(String[] args) {
        tryIt("null [ null", () -> new StringJoiner(null, "[", null));
        tryIt("null null ]", () -> new StringJoiner(null, null, "]"));
        tryIt(", null null", () -> new StringJoiner(",", null, null));
        StringJoiner j = new StringJoiner(new Seq(), new Seq(), new Seq());
        j.setEmptyValue(new Seq());
        System.out.println(j + " " + j.length());
        j.add(new Seq()).add(new Seq());
        System.out.println(j + " " + j.length());
        StringJoiner k = new StringJoiner(",");
        k.add("a");
        StringJoiner m = new StringJoiner(";");
        m.add(new Seq());
        k.merge(m);
        System.out.println(k);
    }
}
