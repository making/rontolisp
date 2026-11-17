import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

interface Shape {
    double area();
    String name();
}

interface Scorer {
    int score(String word, int base);
}

abstract class Base implements Shape {
    static int count = 0;
    protected final String label;

    Base(String label) {
        this.label = label;
        count++;
    }

    public String name() { return label; }

    @Override
    public String toString() { return name() + "[" + area() + "]"; }
}

class Circle extends Base {
    private final double r;
    Circle(double r) { super("circle"); this.r = r; }
    public double area() { return Math.PI * r * r; }
}

class Rect extends Base {
    final double w, h;
    Rect(double w, double h) { this("rect", w, h); }
    Rect(String label, double w, double h) { super(label); this.w = w; this.h = h; }
    public double area() { return w * h; }
}

class Square extends Rect {
    Square(double s) { super("square", s, s); }
    @Override
    public String name() { return "SQUARE/" + super.name(); }
}

class InsufficientFunds extends RuntimeException {
    final int missing;
    InsufficientFunds(String message, int missing) { super(message); this.missing = missing; }
}

class Account {
    private int balance;
    private List<String> log = new ArrayList<>();
    void deposit(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        balance += amount;
        log.add("deposit " + amount);
    }
    void withdraw(int amount) {
        if (amount > balance) throw new InsufficientFunds("insufficient funds", amount - balance);
        balance -= amount;
        log.add("withdraw " + amount);
    }
    int balance() { return balance; }
    List<String> log() { return log; }
}

public class Shapes {
    static int apply(Scorer s, String w) { return s.score(w, 10); }

    static int safeDiv(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            System.out.println("caught: " + e.getMessage());
            return -1;
        } finally {
            System.out.println("finally " + a);
        }
    }

    public static void main(String[] args) {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Circle(1.5));
        shapes.add(new Rect(2, 3));
        shapes.add(new Square(4));
        double total = 0;
        for (Shape s : shapes) {
            System.out.println(s + " " + s.name() + " " + (s instanceof Rect));
            total += s.area();
        }
        System.out.println("total=" + total + " count=" + Base.count);

        Account acc = new Account();
        acc.deposit(100);
        try {
            acc.withdraw(30);
            acc.withdraw(100);
            System.out.println("not reached");
        } catch (InsufficientFunds e) {
            System.out.println(e.getMessage() + ": missing " + e.missing);
        } catch (RuntimeException e) {
            System.out.println("other");
        }
        try {
            acc.deposit(-1);
        } catch (RuntimeException e) {
            System.out.println("runtime: " + e.getMessage());
        }
        System.out.println(acc.balance() + " " + acc.log().size() + " " + acc.log().get(1));
        System.out.println(safeDiv(7, 2) + " " + safeDiv(1, 0));

        Map<String, Integer> counts = new HashMap<>();
        String[] words = {"a", "b", "a", "c", "b", "a"};
        for (String w : words) {
            counts.put(w, counts.getOrDefault(w, 0) + 1);
        }
        System.out.println(counts.get("a") + " " + counts.get("b") + " " + counts.containsKey("z") + " " + counts.size());

        int bonus = 5;
        Scorer byLength = (w, base) -> w.length() * base + bonus;
        Scorer block = (w, base) -> {
            if (w.isEmpty()) return 0;
            return base + w.charAt(0);
        };
        System.out.println(apply(byLength, "hello") + " " + apply(block, "A") + " " + apply(block, ""));
    }
}
