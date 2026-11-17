package shop.order;

public class OrderException extends RuntimeException {
    private final int quantity;

    OrderException(String message, int quantity) {
        super(message);
        this.quantity = quantity;
    }

    public int quantity() {
        return quantity;
    }
}
