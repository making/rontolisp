import java.util.*;
class K1 {
    static class P implements Cloneable {
        int x;
        int[] a = {1};
        public P clone() {
            try {
                return (P) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }
    static class Q {
        Object copy() throws CloneNotSupportedException { return clone(); }
    }
    static class R extends P { int y = 5; }
    record Pt(int x, int y) implements Cloneable {
        Pt copy() throws CloneNotSupportedException { return (Pt) clone(); }
    }
    enum E { X; Object c() throws CloneNotSupportedException { return clone(); } }
    static class F { protected void finalize() throws Throwable { super.finalize(); System.out.println("fin"); } }
    public static void main(String[] args) throws Throwable {
        P p = new P(); p.x = 3; P c = p.clone(); c.x = 4; c.a[0] = 9;
        System.out.println(p.x + " " + c.x + " " + p.a[0] + " " + (p != c) + " " + (c.getClass() == P.class));
        R r = new R(); r.y = 7; P rc = r.clone(); System.out.println((rc instanceof R) + " " + ((R) rc).y);
        try { new Q().copy(); } catch (CloneNotSupportedException e) { System.out.println(e); }
        System.out.println(new Pt(1, 2).copy());
        try { E.X.c(); } catch (CloneNotSupportedException e) { System.out.println(e); }
        new F().finalize();
        System.out.println(p.hashCode() == c.hashCode());
    }
}
