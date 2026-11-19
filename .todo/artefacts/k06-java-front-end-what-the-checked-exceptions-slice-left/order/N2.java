class N2 {
    void b() {
        class L { void c() { int q = 0; q = 1; Runnable r = () -> System.out.println(q); } }
        int p = 0; p = 1; Runnable r = () -> System.out.println(p);
    }
    public static void main(String[] args) { }
}
