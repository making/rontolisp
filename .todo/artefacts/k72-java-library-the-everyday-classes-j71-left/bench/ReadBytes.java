public class ReadBytes {
    public static void main(String[] args) throws Exception {
        int b, n = 0;
        while ((b = System.in.read()) >= 0) {
            n += b;
        }
        System.out.println(n);
    }
}
