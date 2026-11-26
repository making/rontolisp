package shop.pricing;

import java.util.ArrayList;
import java.util.List;

import shop.order.Coupon;
import shop.order.Line;
import shop.order.Order;

public final class Checkout {
    private final Discount discount;

    public Checkout(Discount discount) {
        this.discount = discount;
    }

    public Receipt price(Order order) {
        List<Charge> charges = new ArrayList<>();
        int listPrice = 0;
        int subtotal = 0;
        for (Line line : order.lines()) {
            int cents = discount.price(line);
            charges.add(new Charge(line, cents));
            listPrice += line.listPrice();
            subtotal += cents;
        }
        final int beforeCoupon = subtotal;
        int toPay = order.coupon().map(coupon -> redeem(coupon, beforeCoupon)).orElse(beforeCoupon);
        return new Receipt(order.customer(), charges, listPrice, toPay);
    }

    static int redeem(Coupon coupon, int cents) {
        return switch (coupon) {
            case Coupon.Percent(int percent) -> Discounts.off(cents, percent);
            case Coupon.Fixed(int off, int minimum) when cents >= minimum -> cents - off;
            case Coupon.Fixed fixed -> cents;
        };
    }
}
