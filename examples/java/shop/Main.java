// An order book in the experimental subset of the Java language, over five packages:
// a catalog, orders, their pricing and a ledger the orders are priced into on threads
// of their own. It prints the same on the interpreter, the JVM, WASM and --component,
// and under `java shop/Main.java`.

package shop;

import static shop.util.Money.money;

import java.util.ArrayList;
import java.util.List;

import shop.catalog.Bundle;
import shop.catalog.Category;
import shop.catalog.Item;
import shop.catalog.Product;
import shop.order.Coupon;
import shop.order.Order;
import shop.order.OrderException;
import shop.pricing.Charge;
import shop.pricing.Checkout;
import shop.pricing.Discounts;
import shop.pricing.Receipt;
import shop.report.Ledger;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Item pen = new Product("pen", Category.OFFICE, 120);
        Item pad = new Product("pad", Category.OFFICE, 349);
        Item lamp = new Product("lamp", Category.HOME, 2599);
        Item kit = new Bundle("desk kit").with(pen).with(pad).with(lamp);

        List<Order> orders = new ArrayList<>();
        orders.add(new Order("ann").add(pen, 12).add(pad, 3).redeem(new Coupon.Fixed(500, 2000)));
        orders.add(new Order("bob").add(lamp, 1).add(kit, 2).redeem(new Coupon.Percent(5)));
        orders.add(new Order("cyd").add(pad, 20).redeem(new Coupon.Fixed(500, 10000)));
        orders.add(new Order("dee").add(pen, 4).add(lamp, 2));
        try {
            orders.getLast().add(kit, 0);
        } catch (OrderException e) {
            System.out.println("rejected: " + e.getMessage() + " (" + e.quantity() + ")");
        }

        Checkout checkout = new Checkout(Discounts.bulk(10, 15).orBetter(Discounts.on(Category.HOME, 10)));
        Ledger ledger = new Ledger();
        List<Thread> workers = new ArrayList<>();
        for (Order order : orders) {
            Thread worker = new Thread(() -> ledger.add(checkout.price(order)), order.customer());
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        for (Receipt receipt : ledger.receipts()) {
            System.out.printf("%-4s list %7s  pays %7s  saved %6s%n", receipt.customer(), money(receipt.listPrice()),
                    money(receipt.toPay()), money(receipt.saved()));
            for (Charge charge : receipt.charges()) {
                System.out.println("     " + charge.line().quantity() + " x " + charge.line().item() + " = "
                        + money(charge.cents()));
            }
        }
        for (Category category : Category.values()) {
            Integer cents = ledger.total(category);
            System.out.println(category.label() + ": " + (cents == null ? "-" : money(cents)));
        }
        System.out.println("revenue " + money(ledger.revenue()) + ", a year of it " + money(ledger.revenue() * 12));
    }
}
