package shop.order;

import shop.catalog.Item;

public record Line(Item item, int quantity) {
    public Line {
        if (quantity <= 0) {
            throw new OrderException("quantity must be positive", quantity);
        }
    }

    public int listPrice() {
        return item.cents() * quantity;
    }
}
