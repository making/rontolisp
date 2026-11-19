class N3 {
    void a() { int z = 0; z = 1; Runnable r = () -> System.out.println(z); }
    static { int s = 0; s = 1; Runnable r = () -> System.out.println(s); }
    public static void main(String[] args) { }
}
