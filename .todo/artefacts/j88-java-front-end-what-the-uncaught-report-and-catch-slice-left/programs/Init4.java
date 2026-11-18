public class Init4 {
    static int n = start();
    static int start() {
        System.out.println("starting");
        if (n == 0) throw new IllegalStateException("never starts");
        return 1;
    }
    public static void main(String[] args) {
        System.out.println("main " + n);
    }
}
