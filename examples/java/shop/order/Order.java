package shop.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import shop.catalog.Item;

public final class Order {
    private final String customer;
    private final List<Line> lines = new ArrayList<>();
    private Coupon coupon;

    public Order(String customer) {
        this.customer = customer;
    }

    public Order add(Item item, int quantity) {
        lines.add(new Line(item, quantity));
        return this;
    }

    public Order redeem(Coupon coupon) {
        this.coupon = coupon;
        return this;
    }

    public String customer() {
        return customer;
    }

    public List<Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    public Optional<Coupon> coupon() {
        return Optional.ofNullable(coupon);
    }
}
