package shop.pricing;

import shop.order.Line;

public record Charge(Line line, int cents) {
}
