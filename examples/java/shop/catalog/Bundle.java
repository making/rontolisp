package shop.catalog;

import java.util.ArrayList;
import java.util.List;

public final class Bundle extends Item {
    private final List<Item> parts = new ArrayList<>();

    public Bundle(String name) {
        super(name, Category.BUNDLE);
    }

    public Bundle with(Item part) {
        parts.add(part);
        return this;
    }

    // A bundle costs nine tenths of its parts.
    @Override
    public int cents() {
        int sum = 0;
        for (Item part : parts) {
            sum += part.cents();
        }
        return sum * 9 / 10;
    }
}
