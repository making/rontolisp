package shop.util;

public final class Money {
    private Money() {
    }

    // Cents as dollars and cents: 1234 is "12.34".
    public static String money(long cents) {
        long rest = cents % 100;
        return cents / 100 + "." + (rest < 10 ? "0" : "") + rest;
    }
}
