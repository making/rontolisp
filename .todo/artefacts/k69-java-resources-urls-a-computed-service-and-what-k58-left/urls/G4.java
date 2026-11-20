import java.net.*;
public class G4 {
    public static void main(String[] a) throws Exception {
        StringBuilder bad = new StringBuilder();
        for (int c = 0; c < 0x80; c++) {
            if (c == '/' || c == '?' || c == '#' || c == '@' || c == ':' || c == '[') continue;
            try { new URL("http://h" + (char) c + "x/"); } catch (Exception e) { bad.append(c).append(' '); }
        }
        System.out.println(bad);
        try { new URL("http://hé/"); System.out.println("e ok"); } catch (Exception e) { System.out.println(e); }
    }
}
