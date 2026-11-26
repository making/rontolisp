// shapes in Java -- the world shapes.lisp implements, as a class.
//
// It implements the world `shapes` of wit/shapes.wit: the world's own function
// `summarize` is a static method of the class, and the interface `geometry` is the
// member class Geometry, whose static methods are its functions. The types are the
// WIT's, each a Java type the class declares:
//
//   record           a record                          new Point(1, 2)
//   enum             an enum                           Color.GREEN
//   flags            a Set of an enum                  EnumSet.of(Style.FILLED, Style.BOLD)
//   variant          a sealed interface of records     new Shape.Circle(2.0), new Shape.Dot()
//   list<T>          a List
//   tuple            a record of the class's own       new Corners(low, high)
//   option<T>        an Optional
//   result<T, E>     the value; the error arm is a WitException thrown
//                    (or the method answers a WitResult<T, E>)
//
// Built as a component whose exports are its entry points:
//
//   API="--java-source-path ../../../rontolisp-java-api/src/main/java"
//   rontolisp Shapes.java $API -o shapes.wasm --component
//   wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
//   wasmtime run --invoke 'summarize([1.5, 2.5])' shapes.wasm
//
// app.lisp calls the interface from another component, whichever language
// implements it -- see README.md.

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import am.ik.rontolisp.wit.WitException;
import am.ik.rontolisp.wit.WitExport;

@WitExport(value = "shapes", wit = "wit/shapes.wit")
public class Shapes {

    public record Summary(int count, double total) {
    }

    // WIT: summarize: func(areas: list<f64>) -> summary
    public static Summary summarize(List<Double> areas) {
        double total = 0;
        for (double area : areas) {
            total += area;
        }
        return new Summary(areas.size(), total);
    }

    public static class Geometry {

        public record Point(int x, int y) {
        }

        public enum Color {

            RED, GREEN, BLUE

        }

        public sealed interface Shape {

            record Circle(double value) implements Shape {
            }

            record Rect(Point value) implements Shape {
            }

            record Dot() implements Shape {
            }
        }

        public enum Style {

            FILLED, DASHED, BOLD

        }

        public record Tagged(Shape shape, Color color, Set<Style> style, Optional<String> label) {
        }

        // tuple<point, point>: a record of those components, named as the class likes
        public record Corners(Point low, Point high) {
        }

        // WIT: flip: func(p: point) -> point
        public static Point flip(Point p) {
            return new Point(p.y(), p.x());
        }

        // WIT: squares: func(n: u32) -> list<point>
        public static List<Point> squares(int n) {
            List<Point> points = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                points.add(new Point(i, i * i));
            }
            return points;
        }

        // WIT: bounds: func(ps: list<point>) -> option<tuple<point, point>>
        public static Optional<Corners> bounds(List<Point> ps) {
            if (ps.isEmpty()) {
                return Optional.empty();
            }
            Point low = ps.get(0);
            Point high = ps.get(0);
            for (Point p : ps) {
                low = new Point(Math.min(low.x(), p.x()), Math.min(low.y(), p.y()));
                high = new Point(Math.max(high.x(), p.x()), Math.max(high.y(), p.y()));
            }
            return Optional.of(new Corners(low, high));
        }

        // WIT: area: func(s: shape) -> result<f64, string>
        // The ok arm is the value returned; the error arm is the exception thrown.
        public static double area(Shape s) {
            return switch (s) {
                case Shape.Circle circle -> 3.0 * circle.value() * circle.value();
                case Shape.Rect rect -> Math.abs(rect.value().x() * rect.value().y());
                case Shape.Dot dot -> throw new WitException("a dot has no area", "a dot has no area");
            };
        }

        // WIT: caption: func(t: tagged) -> string
        public static String caption(Tagged tagged) {
            String shape = switch (tagged.shape()) {
                case Shape.Circle circle -> "circle";
                case Shape.Rect rect -> "rect";
                case Shape.Dot dot -> "dot";
            };
            String color = switch (tagged.color()) {
                case RED -> "red";
                case GREEN -> "green";
                case BLUE -> "blue";
            };
            StringBuilder styles = new StringBuilder();
            for (Style style : Style.values()) {
                if (tagged.style().contains(style)) {
                    styles.append(style.name().toLowerCase()).append(' ');
                }
            }
            return "a " + styles + color + " " + shape + tagged.label().map(label -> " called " + label).orElse("");
        }

        // WIT: embolden: func(s: style) -> style
        public static Set<Style> embolden(Set<Style> s) {
            Set<Style> bold = EnumSet.of(Style.BOLD);
            bold.addAll(s);
            return bold;
        }
    }
}
