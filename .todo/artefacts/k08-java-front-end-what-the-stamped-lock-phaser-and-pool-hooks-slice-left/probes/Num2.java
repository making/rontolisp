import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

// A class under Number where a program meets one: a format, a key of a map, an element
// of a sorted list, the type two branches share, an array, and threads reading atomics
// through Number.
public class Num2 {

    record Ratio(int over, int under) implements Comparable<Ratio> {

        public int compareTo(Ratio other) {
            return Integer.compare(this.over * other.under, other.over * this.under);
        }

    }

    static final class Money extends Number implements Comparable<Money> {

        final long cents;

        Money(long cents) {
            this.cents = cents;
        }

        public int intValue() {
            return (int) (this.cents / 100);
        }

        public long longValue() {
            return this.cents / 100;
        }

        public float floatValue() {
            return this.cents / 100f;
        }

        public double doubleValue() {
            return this.cents / 100.0;
        }

        public int compareTo(Money other) {
            return Long.compare(this.cents, other.cents);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Money money && money.cents == this.cents;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(this.cents);
        }

        @Override
        public String toString() {
            return this.cents / 100 + "." + (this.cents % 100 < 10 ? "0" : "") + this.cents % 100;
        }

    }

    static void attempt(String what, Supplier<Object> made) {
        try {
            System.out.println(what + ": " + made.get());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getName());
        }
    }

    static Number pick(boolean atomic) {
        return atomic ? new AtomicInteger(7) : 2.5;
    }

    static String told(Object value) {
        return switch (value) {
            case Money money when money.cents > 1000 -> "much " + money;
            case Money money -> "money " + money;
            case AtomicInteger atomic -> "atomic " + atomic.get();
            case Integer whole -> "int " + whole;
            case Number number -> "number " + number.doubleValue();
            case null -> "null";
            default -> "other " + value;
        };
    }

    public static void main(String[] args) throws InterruptedException {
        Money price = new Money(1999);
        AtomicInteger atomic = new AtomicInteger(42);
        AtomicLong longer = new AtomicLong(7);
        // text
        System.out.println(String.format("%s %s %s|%8s|%-8s|", price, atomic, longer, price, atomic) + " "
                + String.valueOf((Object) price) + " " + price + atomic + longer + " " + new StringBuilder().append(price)
                    .append(atomic)
                    .append((Number) longer));
        attempt("%d of an atomic", () -> String.format("%d", atomic));
        attempt("%d of a class of the program", () -> String.format("%d", price));
        attempt("%f of an atomic", () -> String.format("%.2f", longer));
        attempt("%x of an atomic", () -> String.format("%x", atomic));
        // keys and elements
        Map<Number, String> names = new HashMap<>();
        names.put(new Money(500), "five");
        names.put(new Money(500), "five again");
        names.put(5, "int");
        names.put(5L, "long");
        names.put(5.0, "double");
        names.put(atomic, "atomic");
        System.out.println(names.size() + " " + names.get(new Money(500)) + " " + names.get(5) + " " + names.get(5L)
                + " " + names.get(atomic) + " " + names.get(new AtomicInteger(42)) + " "
                + names.containsKey(new Money(501)));
        Set<Number> set = new HashSet<>(List.of(new Money(1), new Money(2), 1, 2L));
        System.out.println(set.contains(new Money(2)) + " " + set.contains(2) + " " + set.contains(2L) + " "
                + set.contains(new Money(3)) + " " + Objects.hash(new Money(7), 7) + " "
                + Objects.equals(new Money(7), new Money(7)) + " " + new Money(7).hashCode());
        List<Money> sorted = new ArrayList<>(List.of(new Money(300), new Money(100), new Money(200)));
        Collections.sort(sorted);
        List<Number> numbers = new ArrayList<>(sorted);
        numbers.add(1);
        System.out.println(sorted + " " + Collections.max(sorted) + " " + numbers.contains(new Money(100)) + " "
                + numbers.indexOf(1) + " " + numbers.indexOf(new Money(200)) + " " + numbers);
        // the type two branches share, and a switch over what a value is
        Number shared = pick(true);
        var either = args.length == 0 ? new Money(250) : Integer.valueOf(3);
        System.out.println(shared.intValue() + " " + pick(false).intValue() + " " + either.doubleValue() + " " + either
                + " " + told(price) + ", " + told(new Money(5)) + ", " + told(atomic) + ", " + told(3) + ", " + told(2.5f)
                + ", " + told(longer) + ", " + told(null) + ", " + told("text"));
        // arrays
        Number[] mixed = { price, atomic, 3, 4.5 };
        Object[] objects = mixed;
        Money[] moneys = { price };
        Number[] narrow = moneys;
        Object held = moneys;
        System.out.println(Arrays.toString(mixed) + " " + (objects instanceof Number[]) + " " + (held instanceof Number[])
                + " " + (held instanceof Money[]) + " " + (objects instanceof Money[]) + " " + narrow[0].longValue()
                + " " + objects.getClass().getSimpleName() + " " + held.getClass().getName());
        double sum = 0;
        for (Number number : mixed) {
            sum += number.doubleValue();
        }
        System.out.println(sum);
        // threads reading through Number
        Number[] counters = { new AtomicInteger(), new AtomicLong(), new LongAdder() };
        CountDownLatch done = new CountDownLatch(3);
        long[] read = new long[3];
        for (int i = 0; i < 3; i++) {
            int n = i;
            new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    switch (counters[n]) {
                        case AtomicInteger a -> a.incrementAndGet();
                        case AtomicLong a -> a.addAndGet(2);
                        case LongAdder a -> a.add(3);
                        default -> throw new IllegalStateException();
                    }
                    read[n] += counters[n].longValue() > 0 ? 1 : 0;
                }
                done.countDown();
            }).start();
        }
        done.await();
        System.out.println(counters[0].intValue() + " " + counters[1].longValue() + " " + counters[2].doubleValue() + " "
                + read[0] + " " + read[1] + " " + read[2] + " " + Arrays.toString(counters));
    }

}
