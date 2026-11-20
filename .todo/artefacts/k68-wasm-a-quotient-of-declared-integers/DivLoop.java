public class DivLoop {
    public static void main(String[] args) {
        int s = 0;
        long t = 0;
        for (int i = 1; i < 30000000; i++) {
            s += (i * 7) / (i % 13 + 1);
            t += (long) i * 1000003L / (i | 1);
        }
        System.out.println(s + " " + t);
    }
}
