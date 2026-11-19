import java.io.IOException;
class L7 {
    static void io() throws IOException { }
    void a() {
        io();
    }
    Runnable r = () -> {
        class L {
            void m() {
                int x;
                x++;
            }
        }
    };
    public static void main(String[] args) { }
}
