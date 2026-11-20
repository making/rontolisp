public class Grid {
    static long run(int n, int m) {
        long s = 0;
        for (int r = 0; r < 1000000; r++) {
            int[][] g = new int[n][m];
            g[r % n][r % m] = r;
            s += g[r % n][r % m] + g.length + g[0].length;
        }
        return s;
    }
    public static void main(String[] args) {
        System.out.println(run(4, 4));
    }
}
