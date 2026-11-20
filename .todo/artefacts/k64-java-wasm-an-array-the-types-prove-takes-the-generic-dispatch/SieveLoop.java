public class SieveLoop {
    public static void main(String[] args) {
        int n = 50000000;
        int[] composite = new int[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (composite[i] == 0) {
                count++;
                for (int j = i + i; j <= n; j += i) composite[j] = 1;
            }
        }
        System.out.println(count);
    }
}
