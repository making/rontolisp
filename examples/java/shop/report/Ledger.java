package shop.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import shop.catalog.Category;
import shop.pricing.Charge;
import shop.pricing.Receipt;

// Receipts arrive from several threads, and no member takes a lock: each total is kept in
// a structure that takes concurrent updates of its own.
public final class Ledger {
    private final List<Receipt> receipts = new CopyOnWriteArrayList<>();
    private final Map<Category, Integer> byCategory = new ConcurrentHashMap<>();
    private final AtomicLong revenue = new AtomicLong();

    public void add(Receipt receipt) {
        receipts.add(receipt);
        for (Charge charge : receipt.charges()) {
            byCategory.merge(charge.line().item().category(), charge.cents(), Integer::sum);
        }
        revenue.addAndGet(receipt.toPay());
    }

    // The largest receipt first, and by customer where two are equal.
    public List<Receipt> receipts() {
        List<Receipt> sorted = new ArrayList<>(receipts);
        sorted.sort(Comparator.comparingInt(Receipt::toPay).reversed().thenComparing(Receipt::customer));
        return sorted;
    }

    public Integer total(Category category) {
        return byCategory.get(category);
    }

    public long revenue() {
        return revenue.get();
    }
}
