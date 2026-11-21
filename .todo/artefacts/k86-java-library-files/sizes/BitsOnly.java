import java.util.BitSet;
public class BitsOnly {
    public static void main(String[] args) {
        BitSet sieve = new BitSet(100);
        sieve.set(2, 100);
        for (int i = 2; i * i < 100; i = sieve.nextSetBit(i + 1)) {
            for (int j = i * i; j < 100; j += i) {
                sieve.clear(j);
            }
        }
        System.out.println(sieve.cardinality() + " " + sieve);
    }
}
