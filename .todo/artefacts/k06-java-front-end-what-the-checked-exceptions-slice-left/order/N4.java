class N4 {
    void b() {
        int p = 0;
        class L { void c() { System.out.println(p); } }
        p = 1;
        int q = 0; q = 1; Runnable r = () -> System.out.println(q);
    }
    public static void main(String[] args) { }
}
