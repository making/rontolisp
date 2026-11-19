import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

// A class under Number: the atomics, the adders, the accumulators and a program's own.
public class Num {

    static class Fraction extends Number implements Comparable<Fraction> {

        final int over;

        final int under;

        Fraction(int over, int under) {
            this.over = over;
            this.under = under;
        }

        public int intValue() {
            return this.over / this.under;
        }

        public long longValue() {
            return (long) this.over / this.under;
        }

        public float floatValue() {
            return (float) this.over / this.under;
        }

        public double doubleValue() {
            return (double) this.over / this.under;
        }

        public int compareTo(Fraction other) {
            return Long.compare((long) this.over * other.under, (long) other.over * this.under);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Fraction f && f.over == this.over && f.under == this.under;
        }

        @Override
        public int hashCode() {
            return this.over * 31 + this.under;
        }

        @Override
        public String toString() {
            return this.over + "/" + this.under;
        }

    }

    // overrides the two members Number has a body for
    static final class Wide extends Fraction {

        Wide() {
            super(70000, 1);
        }

        @Override
        public byte byteValue() {
            return 7;
        }

        @Override
        public short shortValue() {
            return (short) (super.shortValue() + 1);
        }

        byte narrow() {
            return super.byteValue();
        }

    }

    static abstract class Half extends Number {

        public int intValue() {
            return (int) doubleValue();
        }

        public long longValue() {
            return (long) doubleValue();
        }

        public float floatValue() {
            return (float) doubleValue();
        }

    }

    static double sum(List<? extends Number> numbers) {
        double sum = 0;
        for (Number number : numbers) {
            sum += number.doubleValue();
        }
        return sum;
    }

    static <T extends Number> long total(T first, T second) {
        return first.longValue() + second.longValue();
    }

    static String kind(Object value) {
        if (value instanceof Integer i) {
            return "Integer " + i;
        }
        if (value instanceof AtomicInteger a) {
            return "AtomicInteger " + a.get();
        }
        if (value instanceof Number n) {
            return "Number " + n.intValue();
        }
        return "no number";
    }

    static String picked(Number value) {
        return switch (value) {
            case Integer i -> "int " + i;
            case Long l -> "long " + l;
            case AtomicLong a -> "atomic long " + a.get();
            case Fraction f -> "fraction " + f.over;
            default -> "another " + value;
        };
    }

    public static void main(String[] args) {
        LongAdder adder = new LongAdder();
        adder.add(1L << 33);
        adder.increment();
        DoubleAdder doubles = new DoubleAdder();
        doubles.add(2.75);
        Number[] all = { 3, 4L, 2.5, 1.5f, (byte) 9, (short) 300, new AtomicInteger(40), new AtomicInteger(-129),
                new AtomicLong((1L << 40) + 513), adder, doubles, new LongAccumulator(Long::max, 65537),
                new DoubleAccumulator(Double::sum, -0.5), new Fraction(7, 2), new Wide(), new Half() {
                    public double doubleValue() {
                        return 1e10;
                    }

                    public String toString() {
                        return "half";
                    }
                } };
        for (Number number : all) {
            System.out.println(number + ": " + number.intValue() + " " + number.longValue() + " "
                    + number.floatValue() + " " + number.doubleValue() + " " + number.byteValue() + " "
                    + number.shortValue() + " " + kind(number) + ", " + picked(number));
        }
        // by its own static type: a member the class declares and one only Number has
        AtomicInteger atomic = new AtomicInteger(1000);
        System.out.println(atomic.intValue() + " " + atomic.byteValue() + " " + atomic.shortValue() + " "
                + atomic.floatValue() + " " + atomic.doubleValue() + " " + atomic.longValue());
        AtomicLong longer = new AtomicLong(-1L << 35 | 77);
        System.out.println(longer.intValue() + " " + longer.byteValue() + " " + longer.shortValue() + " "
                + longer.floatValue() + " " + longer.doubleValue());
        Wide wide = new Wide();
        System.out.println(wide.byteValue() + " " + wide.shortValue() + " " + wide.narrow() + " " + wide.intValue());
        Fraction third = new Fraction(1, 3);
        System.out.println(third.byteValue() + " " + third.shortValue() + " " + third.floatValue());
        // instanceof and casts
        Object[] things = { 1, "one", new AtomicInteger(1), new AtomicLong(2), new Fraction(9, 3), 'c', null,
                new Object(), new int[0] };
        for (Object thing : things) {
            System.out.println(kind(thing) + " " + (thing instanceof Number) + " " + (thing instanceof Comparable));
        }
        Number held = new Fraction(1, 2);
        try {
            Integer boxed = (Integer) held;
            System.out.println("cast " + boxed);
        }
        catch (ClassCastException e) {
            System.out.println("no Integer");
        }
        Object text = "text";
        try {
            Number number = (Number) text;
            System.out.println("cast " + number);
        }
        catch (ClassCastException e) {
            System.out.println("no Number");
        }
        Fraction back = (Fraction) held;
        Number same = (Number) (Object) atomic;
        System.out.println(back + " " + same + " " + (same == atomic));
        // Object's methods through Number
        Number one = new Fraction(2, 4);
        Number two = new Fraction(2, 4);
        Number five = 5;
        System.out.println(one.equals(two) + " " + one.equals(five) + " " + five.equals(one) + " " + five.equals(5)
                + " " + (one.hashCode() == two.hashCode()) + " " + five.hashCode() + " " + one.toString() + " "
                + String.valueOf(two) + " " + Objects.toString(five) + " " + Objects.equals(one, two));
        Number plain = new AtomicInteger(6);
        System.out.println(plain.equals(plain) + " " + plain.equals(new AtomicInteger(6)) + " " + plain.equals(6)
                + " " + plain);
        // through the type arguments
        List<Number> mixed = new ArrayList<>();
        mixed.add(1);
        mixed.add(new AtomicInteger(2));
        mixed.add(new Fraction(7, 2));
        mixed.add(4.25);
        System.out.println(sum(mixed) + " " + mixed + " " + total(new AtomicLong(5), new AtomicLong(6)) + " "
                + total(new Fraction(9, 2), new Fraction(5, 2)) + " " + total(1, 2));
        Map<String, Number> named = new HashMap<>();
        named.put("a", new AtomicInteger(1));
        named.put("b", 2L);
        named.put("c", new Fraction(1, 1));
        long whole = 0;
        for (Number number : named.values()) {
            whole += number.longValue();
        }
        System.out.println(whole + " " + named.get("a").intValue() + " " + named.get("b").intValue());
        Map<String, AtomicInteger> counts = new HashMap<>();
        counts.computeIfAbsent("x", key -> new AtomicInteger()).incrementAndGet();
        counts.computeIfAbsent("x", key -> new AtomicInteger()).incrementAndGet();
        Number count = counts.get("x");
        System.out.println(count.intValue() + " " + count.doubleValue() + " " + new Fraction(1, 2).compareTo(third));
        java.util.function.Function<Number, Integer> ints = Number::intValue;
        java.util.function.ToDoubleFunction<Number> floats = Number::doubleValue;
        System.out.println(ints.apply(new AtomicLong(12)) + " " + ints.apply(13.9) + " "
                + floats.applyAsDouble(new Fraction(1, 4)) + " " + floats.applyAsDouble(3));
        System.out.println(String.format("%s %s %s", atomic, third, plain));
        // an array of a class under Number is an array of numbers
        Fraction[] fractions = { third };
        Object array = fractions;
        Number[] numbers = fractions;
        System.out.println((array instanceof Number[]) + " " + (array instanceof Fraction[]) + " "
                + (array instanceof Integer[]) + " " + numbers[0].floatValue() + " " + array.getClass().getSimpleName());
    }

}
