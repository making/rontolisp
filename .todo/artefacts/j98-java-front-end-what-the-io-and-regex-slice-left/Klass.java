import java.util.ArrayList;
import java.util.List;

public class Klass {
    static class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Point other = (Point) o;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    static class Pixel extends Point {
        Pixel(int x, int y) {
            super(x, y);
        }
    }

    interface Shape { }

    enum Color { RED, GREEN }

    record Pair(int a, int b) implements Shape { }

    public static void main(String[] args) {
        Point p = new Point(1, 2);
        Object q = new Pixel(1, 2);
        System.out.println(p.equals(new Point(1, 2)) + " " + p.equals(q) + " " + q.equals(p) + " " + p.equals(null) + " " + p.equals("p") + " " + q.equals(new Pixel(1, 2)));
        System.out.println((p.getClass() == Point.class) + " " + (q.getClass() == Point.class) + " " + (q.getClass() != Pixel.class) + " " + (Point.class == Point.class));
        System.out.println(p.getClass().equals(q.getClass()) + " " + q.getClass().equals(Pixel.class) + " " + Pixel.class.equals(q.getClass()) + " " + Point.class.equals(Point.class));
        System.out.println(Point.class.getName() + " " + Point.class.getSimpleName() + " " + Klass.class.getName() + " " + Shape.class.getName() + " " + Color.class.getSimpleName() + " " + Pair.class.getName() + " " + String.class.getName() + " " + String.class.getSimpleName() + " " + java.util.ArrayList.class.getName() + " " + List.class.getSimpleName() + " " + Klass.Point.class.getName() + " " + Integer.class.getName() + " " + Object.class.getSimpleName());
        Object text = "s";
        Object number = 5;
        Object list = new ArrayList<String>();
        Object big = 5L;
        Shape shape = new Pair(1, 2);
        Object color = Color.RED;
        System.out.println((text.getClass() == String.class) + " " + (number.getClass() == Integer.class) + " " + (number.getClass() == big.getClass()) + " " + (big.getClass() == Long.class) + " " + (list.getClass() == ArrayList.class) + " " + (list.getClass() == List.class) + " " + (shape.getClass() == Pair.class) + " " + (color.getClass() == Color.class) + " " + (text.getClass() == number.getClass()) + " " + (new int[0].getClass() == new int[5].getClass()) + " " + (new StringBuilder().getClass() == StringBuilder.class));
        Object nothing = null;
        try {
            System.out.println(nothing.getClass() == String.class);
        } catch (NullPointerException e) {
            System.out.println("null has no class");
        }
    }
}
