package shop.pricing;

import java.util.List;

public record Receipt(String customer, List<Charge> charges, int listPrice, int toPay) {
    public int saved() {
        return listPrice - toPay;
    }
}
