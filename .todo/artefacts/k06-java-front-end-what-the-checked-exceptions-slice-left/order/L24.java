class L24 {
    static void io() throws java.io.IOException { }
    void a() {
        io();
    }
    L24() { }
    Object o = new Object() {
        void m() {
            io();
        }
    };
    public static void main(String[] args) { }
}
