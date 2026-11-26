package shop.pricing;

import shop.order.Line;

@FunctionalInterface
public interface Discount {
    // The cents a line costs.
    int price(Line line);

    // Whichever of the two is cheaper for the line.
    default Discount orBetter(Discount other) {
        return line -> Math.min(price(line), other.price(line));
    }
}
