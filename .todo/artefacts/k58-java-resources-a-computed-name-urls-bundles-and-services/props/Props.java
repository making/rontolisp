import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Props {
    static String show(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }
    static void load(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
            List<String> out = new ArrayList<>();
            for (String k : new TreeSet<>(p.stringPropertyNames())) out.add("[" + show(k) + "]=[" + show(p.getProperty(k)) + "]");
            System.out.println(show(text) + " -> " + out);
        } catch (Exception ex) {
            System.out.println(show(text) + " -> " + ex.getClass().getName() + ": " + ex.getMessage());
        }
    }
    public static void main(String[] args) throws Exception {
        load("a=b");
        load("a = b ");
        load("  a:b");
        load("a b c");
        load("a\tb");
        load("a\fb");
        load("a=");
        load("a");
        load("=b");
        load(":b");
        load(" ");
        load("a==b");
        load("a=:b");
        load("a :=b");
        load("a  =  = b");
        load("a\\ b=c");
        load("a\\=b=c");
        load("a\\:b:c");
        load("a\\tb=c\\n");
        load("a=\\t\\r\\n\\f\\b\\q\\\\");
        load("a=\\u0041\\u00e9");
        load("a=\\u004");
        load("a=\\u00G1");
        load("a=\\U0041");
        load("# comment\na=b");
        load("! comment\na=b");
        load("  # comment\na=b");
        load("a=b # not a comment");
        load("a=b\\\n  c");
        load("a=b\\\n  c\\\n\td");
        load("a=b\\\r\n  c");
        load("a=b\\\r  c");
        load("a=b\\");
        load("a=b\\\\");
        load("a=b\\\\\\\nc");
        load("a\\\n b=c");
        load("# comment \\\na=b");
        load("a=1\na=2");
        load("a=1\r\nb=2\rc=3\n\nd=4");
        load("a=b\\\n\n c=d");
        load("a=b\\\n# x\nc=d");
        load("\\#a=b");
        load("\\!a=b");
        load("a\\ =b");
        load("a=\\ b");
        load("a=é∑");
        load("\\u0061=\\u0062");
        load("a=\\\n");
        load("a\\");
        load("a\\u0020b=c");
        load("\u0000a=b");
        load("a=\\u");
        // streams: ISO-8859-1
        Properties s = new Properties();
        s.load(new ByteArrayInputStream("k=é\\u00e9".getBytes(StandardCharsets.ISO_8859_1)));
        System.out.println("stream " + show(s.getProperty("k")));
        Properties u = new Properties();
        u.load(new ByteArrayInputStream("k=é".getBytes(StandardCharsets.UTF_8)));
        System.out.println("stream utf8 bytes " + show(u.getProperty("k")));
    }
}
