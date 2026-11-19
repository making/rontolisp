import java.io.IOException;
class L1 {
    void a() {
        throw new IOException("a");
    }
    void b() {
        class L {
            void m() {
                while (true) { }
                int z = 0;
            }
        }
    }
    public static void main(String[] args) { }
}
