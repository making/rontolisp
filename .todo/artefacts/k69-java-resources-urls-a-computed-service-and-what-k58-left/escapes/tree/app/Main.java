package app;
import java.net.*;
public class Main {
    public static void main(String[] a) throws Exception {
        StringBuilder t = new StringBuilder(), d = new StringBuilder(), j = new StringBuilder();
        String[] names = new String[0x7f - 0x20 + 1];
        for (int c = 0x20; c <= 0x7f; c++) {
            String n = c == 0x7f ? "nÿ€😀n" : "n" + (char) c + "n";
            if (c == '/' || c == '.') continue;
            URL u = Main.class.getResource(n);
            URL s = ClassLoader.getSystemResource(n);
            URL s2 = ClassLoader.getSystemResource("x" + n); 
            System.out.println((c == 0x7f ? "U" : String.valueOf((char) c)) + " T=" + (u == null ? null : u.toString().substring(u.toString().lastIndexOf('/') + 1))
              + " S=" + (s == null ? null : s.toString().substring(s.toString().lastIndexOf('/') + 1) + " " + s.getProtocol()));
        }
        java.util.Enumeration<URL> e = ClassLoader.getSystemResources("n n");
        while (e.hasMoreElements()) System.out.println(e.nextElement().toString().replace(System.getProperty("user.dir").replace("/tree",""), "P"));
    }
}
