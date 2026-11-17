package shop.catalog;

import static shop.util.Money.money;

public abstract class Item {
    protected final String name;
    private final Category category;

    protected Item(String name, Category category) {
        this.name = name;
        this.category = category;
    }

    public abstract int cents();

    public Category category() {
        return category;
    }

    @Override
    public String toString() {
        return name + " @ " + money(cents());
    }
}
