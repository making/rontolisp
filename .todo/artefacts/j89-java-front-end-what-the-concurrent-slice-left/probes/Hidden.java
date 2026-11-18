public class Hidden {
    static class Base {
        private int count = 5;
        private String label() { return "base"; }
        int own() { return count; }
    }
    static String label() { return "outer"; }
    public static void main(String[] args) throws InterruptedException {
        String name = "a local";
        int count = 1;
        Thread thread = new Thread() {
            public void run() {
                System.out.println(name + " " + getName().isEmpty());
            }
        };
        thread.start();
        thread.join();
        Base base = new Base() {
            int own() { return count + super.own(); }
            public String toString() { return label(); }
        };
        System.out.println(base.own() + " " + base);
    }
}
