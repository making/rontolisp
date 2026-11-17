package shop.order;

// A coupon is one of two kinds, and a switch over it needs no default.
public sealed interface Coupon permits Coupon.Percent, Coupon.Fixed {
    record Percent(int percent) implements Coupon {
    }

    record Fixed(int cents, int minimum) implements Coupon {
    }
}
