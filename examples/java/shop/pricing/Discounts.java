package shop.pricing;

import shop.catalog.Category;
import shop.order.Line;

public final class Discounts {
    public static final Discount NONE = Line::listPrice;

    private Discounts() {
    }

    public static Discount bulk(int quantity, int percent) {
        return line -> line.quantity() >= quantity ? off(line.listPrice(), percent) : line.listPrice();
    }

    public static Discount on(Category category, int percent) {
        return line -> line.item().category() == category ? off(line.listPrice(), percent) : line.listPrice();
    }

    static int off(int cents, int percent) {
        return cents - cents * percent / 100;
    }
}
