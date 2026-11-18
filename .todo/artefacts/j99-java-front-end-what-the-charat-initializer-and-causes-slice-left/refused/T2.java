import java.io.*;
class T2 {
    static void io() throws IOException { }
    int f = make();
    static int s = make();
    static int make() throws IOException { return 1; }
    { io(); }
    static { io(); }
}
class T2b {
    T2b() throws IOException { }
}
class T2c extends T2b { }
class T2d extends T2b { T2d() { } }
class T2e extends T2b { T2e() { super(); } }
class T2f extends T2b { T2f() throws Exception { } int g = T2.make(); { T2.io(); } }
class T2g extends T2b { T2g() throws IOException { } T2g(int x) { this(); } }
class T2h { int g = T2.make(); T2h() throws IOException { } T2h(int x) { } }
class T2i { int g = T2.make(); }
