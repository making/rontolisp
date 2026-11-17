public class WrapInt {
    public static void main(String[] args) {
        int h = 0;
        for (int i = 0; i < 30000000; i++) {
            h = 31 * h + i % 7;
        }
        System.out.println(h);
    }
}
