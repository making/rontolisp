class M2 {
    void a() { int z = 0; z = 1; Runnable r = () -> System.out.println(z); }
    void b() {
        class L {
            void c() { int q = 0; q = 1; Runnable r = () -> System.out.println(q); }
            class N {
              void c() { int q2 = 0; q2 = 1; Runnable r = () -> System.out.println(q2); }
              void j() { int y2; y2++; }
            }
            void j() { int y; y++; }
        }
    }
    public static void main(String[] args) { }
}
