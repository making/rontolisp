import java.io.IOException;
class L4 {
    static void io() throws IOException { }
    void a() {
        int x;
        x++;
    }
    void b() {
        Runnable r = new Runnable() {
            public void run() {
                while (true) { }
                int z = 0;
            }
        };
    }
    public static void main(String[] args) { }
}
