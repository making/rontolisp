import java.io.IOException;
class L2 {
    static void io() throws IOException { }
    void a() {
        io();
    }
    void b() {
        class L {
            void m() {
                io();
            }
        }
    }
    public static void main(String[] args) { }
}
