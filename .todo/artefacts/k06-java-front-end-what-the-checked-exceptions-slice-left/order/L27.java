import java.io.*;
class L27 {
    static void fnf() throws FileNotFoundException { }
    void a() {
        try { fnf(); } catch (FileNotFoundException e) { } catch (IOException e) { }
    }
    void b() {
        class L {
            void m() {
                try { fnf(); } catch (FileNotFoundException e) { } catch (IOException e) { }
            }
        }
    }
    void c() {
        try { fnf(); } catch (FileNotFoundException e) { } catch (IOException e) { }
    }
    public static void main(String[] args) { }
}
