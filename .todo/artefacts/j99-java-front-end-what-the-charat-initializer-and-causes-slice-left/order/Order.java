import java.io.IOException;
class A {
    void a() {
        throw new IOException("a");          // flow: unreported, class A method 1
    }
    int b() {
        int x;
        return x;                            // assign
    }
    void c() {
        int y = 0;
        y = 1;
        Runnable r = () -> System.out.println(y);   // capture
    }
    void d() {
        return;
        ;
    }
    void d2() {
        while (true) { }
        int z = 0;                              // unreachable
    }
    void e() {
        throw new IOException("e");
    }
}
class B {
    void t() {
        String s = 1;                        // typing error in a later class
    }
}
