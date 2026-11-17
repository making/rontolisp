package shop.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import shop.catalog.Category;
import shop.pricing.Charge;
import shop.pricing.Receipt;

// Receipts arrive from several threads; every member that touches them holds the lock.
public final class Ledger {
    private final List<Receipt> receipts = new ArrayList<>();
    private final Map<Category, Integer> byCategory = new HashMap<>();

    public synchronized void add(Receipt receipt) {
        receipts.add(receipt);
        for (Charge charge : receipt.charges()) {
            byCategory.merge(charge.line().item().category(), charge.cents(), Integer::sum);
        }
    }

    // The largest receipt first, and by customer where two are equal.
    public synchronized List<Receipt> receipts() {
        List<Receipt> sorted = new ArrayList<>(receipts);
        sorted.sort(Comparator.comparingInt(Receipt::toPay).reversed().thenComparing(Receipt::customer));
        return sorted;
    }

    public synchronized Integer total(Category category) {
        return byCategory.get(category);
    }

    public synchronized long revenue() {
        long sum = 0;
        for (Receipt receipt : receipts) {
            sum += receipt.toPay();
        }
        return sum;
    }
}
