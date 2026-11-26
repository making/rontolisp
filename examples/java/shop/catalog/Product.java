package shop.catalog;

public final class Product extends Item {
    private final int cents;

    public Product(String name, Category category, int cents) {
        super(name, category);
        this.cents = cents;
    }

    @Override
    public int cents() {
        return cents;
    }
}
