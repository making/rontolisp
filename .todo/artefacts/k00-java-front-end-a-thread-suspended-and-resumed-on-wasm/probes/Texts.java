import java.util.Arrays;
import java.util.Comparator;

public class Texts {
    static class Account {
        private int balance;
        private final String name;

        Account(String name) {
            this.name = name;
        }

        synchronized void slowDeposit(int amount) throws InterruptedException {
            Thread.sleep(10);
            balance += amount;
        }

        synchronized int balance() {
            return balance;
        }

        @Override
        public synchronized String toString() {
            return name + "=" + balance;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Account a = new Account("a");
        Account b = new Account("b");
        Thread t = new Thread(() -> {
            try {
                a.slowDeposit(5);
                b.slowDeposit(3);
            }
            catch (InterruptedException e) {
                return;
            }
        });
        t.start();
        t.join();
        System.out.println(String.format("%s and %s", a, b));
        System.out.printf("%s%n", "" + a + b);
        Account[] sorted = { a, b };
        Arrays.sort(sorted, Comparator.comparingInt(Account::balance));
        System.out.println(Arrays.toString(sorted));
    }
}
