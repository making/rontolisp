// An order book in the experimental subset of the Java language: classes and an
// interface, a functional interface called through lambdas, overloads, exceptions,
// ArrayList and HashMap. It prints the same on the interpreter, the JVM, WASM and
// --component, and under `java Orders.java`.

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

interface Priced {
    int cents();
}

interface Discount {
    int apply(int cents, int quantity);
}

abstract class Item implements Priced {
    protected final String name;
    protected final String category;

    Item(String name, String category) {
        this.name = name;
        this.category = category;
    }

    String category() {
        return category;
    }

    @Override
    public String toString() {
        return name + " @ " + Orders.money(cents());
    }
}

class Product extends Item {
    private final int cents;

    Product(String name, String category, int cents) {
        super(name, category);
        this.cents = cents;
    }

    public int cents() {
        return cents;
    }
}

class Bundle extends Item {
    private final List<Item> parts = new ArrayList<>();

    Bundle(String name) {
        super(name, "bundle");
    }

    Bundle with(Item part) {
        parts.add(part);
        return this;
    }

    // A bundle costs nine tenths of its parts.
    public int cents() {
        int sum = 0;
        for (Item part : parts) {
            sum += part.cents();
        }
        return sum * 9 / 10;
    }
}

class OrderException extends RuntimeException {
    final int quantity;

    OrderException(String message, int quantity) {
        super(message);
        this.quantity = quantity;
    }
}

class Line {
    final Item item;
    final int quantity;

    Line(Item item, int quantity) {
        if (quantity <= 0) {
            throw new OrderException("quantity must be positive", quantity);
        }
        this.item = item;
        this.quantity = quantity;
    }

    int cents(Discount discount) {
        return discount.apply(item.cents(), quantity);
    }
}

public class Orders {
    static String money(int cents) {
        int rest = cents % 100;
        return cents / 100 + "." + (rest < 10 ? "0" : "") + rest;
    }

    static String money(long cents) {
        return money((int) (cents / 100)) + " hundred";
    }

    static int total(List<Line> lines, Discount discount) {
        int sum = 0;
        for (Line line : lines) {
            sum += line.cents(discount);
        }
        return sum;
    }

    public static void main(String[] args) {
        Item pen = new Product("pen", "office", 120);
        Item pad = new Product("pad", "office", 349);
        Item lamp = new Product("lamp", "home", 2599);
        Item kit = new Bundle("desk kit").with(pen).with(pad).with(lamp);

        List<Line> lines = new ArrayList<>();
        lines.add(new Line(pen, 12));
        lines.add(new Line(pad, 3));
        lines.add(new Line(lamp, 1));
        lines.add(new Line(kit, 2));
        try {
            lines.add(new Line(pad, 0));
        } catch (OrderException e) {
            System.out.println("rejected: " + e.getMessage() + " (" + e.quantity + ")");
        }

        Discount none = (cents, quantity) -> cents * quantity;
        int threshold = 10;
        Discount bulk = (cents, quantity) -> {
            if (quantity >= threshold) {
                return cents * quantity * 85 / 100;
            }
            return cents * quantity;
        };

        Map<String, Integer> byCategory = new HashMap<>();
        for (Line line : lines) {
            String category = line.item.category();
            byCategory.put(category, byCategory.getOrDefault(category, 0) + line.cents(bulk));
            System.out.println(line.quantity + " x " + line.item + " = " + money(line.cents(bulk)));
        }

        String[] categories = {"office", "home", "bundle", "garden"};
        for (String category : categories) {
            Integer cents = byCategory.get(category);
            System.out.println(category + ": " + (cents == null ? "-" : money(cents)));
        }
        System.out.println("list price " + money(total(lines, none)) + ", to pay " + money(total(lines, bulk)));
        System.out.println("a year of it: " + money(total(lines, bulk) * 12L));
    }
}
