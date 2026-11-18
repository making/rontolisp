public class Cause2 {
    public static void main(String[] args) {
        RuntimeException top = new RuntimeException("top", new IllegalArgumentException("middle", new Error("root")));
        top.addSuppressed(new IllegalStateException("aside"));
        System.out.println("before");
        throw top;
    }
}
