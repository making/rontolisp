class N5 {
    void a() { int z = 0; z = 1; Runnable r = () -> System.out.println(z); }
    N5() { }
    { int s = 0; s = 1; Runnable r = () -> System.out.println(s); }
    public static void main(String[] args) { }
}
