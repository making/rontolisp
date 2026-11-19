import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Literals {
    interface Shape {
    }

    record Circle(double r) implements Shape {
    }

    record Square(double side) implements Shape {
    }

    public static void main(String[] args) {
        List<Shape> shapes = List.of(new Circle(1), new Square(2), new Circle(0.5));
        System.out.println(shapes.stream().filter(Circle.class::isInstance).count());
        List<Circle> circles = shapes.stream().filter(Circle.class::isInstance).map(Circle.class::cast).collect(Collectors.toList());
        System.out.println(circles.get(1).r());
        System.out.println(shapes.stream().filter(Circle.class::isInstance).map(Circle.class::cast).mapToDouble(Circle::r).sum());
        List<Object> mixed = List.of(1, "two", 3.0, "four", 5L);
        System.out.println(mixed.stream().filter(String.class::isInstance).map(String.class::cast).map(String::length).toList());
        System.out.println(mixed.stream().filter(Integer.class::isInstance).count() + " " + mixed.stream().filter(Number.class::isInstance).count() + " " + Stream.of("a").filter(Integer.class::isInstance).count());
        Predicate<Object> isText = CharSequence.class::isInstance;
        Function<Object, String> asText = String.class::cast;
        System.out.println(isText.test("x") + " " + isText.test(1) + " " + asText.apply("y"));
        try {
            System.out.println(asText.apply(1));
        }
        catch (ClassCastException e) {
            System.out.println("not a string");
        }
    }
}
