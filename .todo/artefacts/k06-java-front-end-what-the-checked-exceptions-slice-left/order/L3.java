import java.io.IOException;
class L3 {
    static void io() throws IOException { }
    void b() {
        io();
        class L {
            void m() {
                io();
            }
        }
    }
    public static void main(String[] args) { }
}
