public class K {
    static class Point { }
    static class Pixel extends Point { }
    static final class Sealed { }
    interface Shape { }
    public static void main(String[] args) {
        Point p = new Point();
        Pixel x = new Pixel();
        Shape s = null;
        String t = "";
        Integer i = 1;
        Sealed f = new Sealed();
        boolean b;
        b = p.getClass() == Object.class;       // 14
        b = p.getClass() == String.class;       // 15
        b = s.getClass() == Point.class;        // 16
        b = p.getClass() == x.getClass();       // 17
        b = t.getClass() == i.getClass();       // 18
        b = p.getClass() == Pixel.class;        // 19
        b = x.getClass() == Point.class;        // 20
        b = f.getClass() == Shape.class;        // 21
        b = s.getClass() == f.getClass();       // 22
        b = p.getClass() == Shape.class;        // 23
        b = Point.class == Point.class;         // 24
        b = Point.class == Object.class;        // 25
        b = p.getClass().equals(String.class);  // 26
        b = t.getClass() == Shape.class;        // 27
        b = s.getClass() == String.class;       // 28
        b = s.getClass() == Sealed.class;       // 29
    }
}
