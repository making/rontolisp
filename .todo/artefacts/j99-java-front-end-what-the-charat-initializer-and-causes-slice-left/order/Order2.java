import java.io.IOException;
class A2 {
    void a() {
        throw new IOException("a");
    }
    int b() {
        int x;
        return x;
    }
    void c() {
        int y = 0;
        y = 1;
        Runnable r = () -> System.out.println(y);
    }
    void d2() {
        while (true) { }
        int z = 0;
    }
    void e() {
        throw new IOException("e");
    }
}
class B2 {
    void f() {
        throw new IOException("f");
    }
    void d2() {
        while (true) { }
        int z = 0;
    }
}
