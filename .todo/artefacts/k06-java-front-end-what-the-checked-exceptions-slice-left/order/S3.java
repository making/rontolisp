class S3 {
    void a() { while (true) { } int x = 0; }
    S3() { }
    { Runnable r = () -> { while (true) { } int y = 0; }; }
    static { Runnable r = () -> { while (true) { } int z = 0; }; }
    public static void main(String[] args) { }
}
