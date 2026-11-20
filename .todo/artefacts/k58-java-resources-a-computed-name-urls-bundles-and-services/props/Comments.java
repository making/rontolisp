import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Comments {
    static String show(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n') b.append("<n>\n"); else if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.setProperty("k", "€ÿĀ");
        String comment = "cé∑€ÿĀ\u0007\t end\n\n#x\r\n\r!y\nz";
        StringWriter w = new StringWriter();
        p.store(w, comment);
        System.out.print(show(w.toString()));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        p.store(o, comment);
        System.out.print(show(new String(o.toByteArray(), StandardCharsets.ISO_8859_1)));
        StringWriter e = new StringWriter();
        p.store(e, "");
        System.out.print(show(e.toString()));
        StringWriter t = new StringWriter();
        p.store(t, "ends\n");
        System.out.print(show(t.toString()));
    }
}
