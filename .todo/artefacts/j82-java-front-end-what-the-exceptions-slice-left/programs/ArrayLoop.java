public class ArrayLoop {
    public static void main(String[] args) {
        int n = 5000000;
        int[] composite = new int[n + 1];
        int count = 0;
        for (int round = 0; round < 6; round++) {
            count = 0;
            for (int i = 0; i <= n; i++) composite[i] = 0;
            for (int i = 2; i <= n; i++) {
                if (composite[i] == 0) {
                    count++;
                    for (int j = i + i; j <= n; j += i) composite[j] = 1;
                }
            }
        }
        System.out.println(count);
    }
}
