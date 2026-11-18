import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class Acc {
    static String tried(Supplier<Object> s) {
        try {
            return String.valueOf(s.get());
        }
        catch (Throwable t) {
            return "threw " + t;
        }
    }

    public static void main(String[] args) throws Exception {
        LongAccumulator max = new LongAccumulator(Long::max, Long.MIN_VALUE);
        System.out.println(max + " " + max.get() + " " + max.intValue());
        max.accumulate(5);
        max.accumulate(3);
        System.out.println(max + " " + max.get() + " " + max.getThenReset() + " " + max.get());
        max.accumulate(9);
        max.reset();
        System.out.println(max.longValue() + " " + max.floatValue() + " " + max.doubleValue());
        LongAccumulator sum = new LongAccumulator((x, y) -> x + 2 * y, 0);
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { for (int j = 0; j < 1000; j++) sum.accumulate(1); });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        System.out.println(sum.get() + " " + sum.intValue());
        System.out.println(tried(() -> new LongAccumulator(null, 0).get()));
        
        DoubleAdder adder = new DoubleAdder();
        System.out.println(adder + " " + adder.sum());
        adder.add(1.5);
        adder.add(2.25);
        System.out.println(adder + " " + adder.sum() + " " + adder.intValue() + " " + adder.longValue() + " " + adder.floatValue() + " " + adder.doubleValue());
        System.out.println(adder.sumThenReset() + " " + adder.sum());
        adder.add(-0.0);
        System.out.println(adder.sum());
        adder.add(Double.NaN);
        System.out.println(adder.sum());
        adder.reset();
        DoubleAdder shared = new DoubleAdder();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { for (int j = 0; j < 1000; j++) shared.add(0.5); });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        System.out.println(shared.sum());
        DoubleAccumulator product = new DoubleAccumulator((x, y) -> x * y, 1.0);
        product.accumulate(1.5);
        product.accumulate(4);
        System.out.println(product + " " + product.get() + " " + product.intValue() + " " + product.longValue() + " " + product.floatValue() + " " + product.doubleValue());
        System.out.println(product.getThenReset() + " " + product.get());
        product.accumulate(3);
        product.reset();
        System.out.println(product);
        LongAdder longs = new LongAdder();
        longs.add(Long.MAX_VALUE);
        longs.increment();
        System.out.println(longs + " " + longs.intValue() + " " + longs.floatValue());
    }
}
