public class OneTry {
    static int step(int acc, int i) {
        if (i < 0) throw new IllegalStateException("negative");
        return acc + i;
    }
    public static void main(String[] args) {
        int acc = 0;
        for (int i = 0; i < 100000000; i++) {
            try {
                acc = step(acc, i);
            } catch (IllegalStateException e) {
                acc = -1;
            }
        }
        System.out.println(acc);
    }
}
