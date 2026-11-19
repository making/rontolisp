class M1 {
    static void io() throws java.io.IOException { }
    static class M {
        void j() { int y; y++; }
        void k() { io(); }
        void c() { int q = 0; q = 1; Runnable r = () -> System.out.println(q); }
        static class N {
            void j() { int y2; y2++; }
            void k() { io(); }
            void c() { int q2 = 0; q2 = 1; Runnable r = () -> System.out.println(q2); }
        }
    }
    void a() { int x; x++; }
    void b() { io(); }
    void c() { int p = 0; p = 1; Runnable r = () -> System.out.println(p); }
    public static void main(String[] args) { }
}
