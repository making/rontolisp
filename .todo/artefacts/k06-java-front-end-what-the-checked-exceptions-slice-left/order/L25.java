class L25 {
    void a() {
        int x;
        x++;
    }
    L25() { }
    { Runnable r = () -> { int y; y++; }; }
    public static void main(String[] args) { }
}
