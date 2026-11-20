import java.io.*;
import java.nio.charset.*;
import java.util.*;

public class Encodings {
    static void show(byte[] b, Charset cs) {
        System.out.println(new String(b, cs).replace("\r", "\\r").replace("\u0001", "\\1"));
    }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.setProperty("k", "é\u3042\ud83d\ude00\ud83d<\r\u0001");
        for (String enc : new String[] { "utf-8", "UTF-8", "US-ASCII", "UTF-16", "latin1", "UTF-16LE" }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                p.storeToXML(out, "c\u3042", enc);
                System.out.println(enc + " " + out.size() + " " + Arrays.toString(Arrays.copyOf(out.toByteArray(), 6)));
                show(out.toByteArray(), Charset.forName(enc));
            } catch (Exception e) { System.out.println(enc + " " + e); }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.storeToXML(out, null, StandardCharsets.UTF_16BE);
        show(out.toByteArray(), StandardCharsets.UTF_16BE);
        Properties d = new Properties();
        d.setProperty("x", "1");
        Properties q = new Properties(d);
        q.setProperty("y", "2");
        q.put("z", 3);
        out = new ByteArrayOutputStream();
        try { q.storeToXML(out, null); } catch (Exception e) { System.out.println(e + " wrote " + out.size()); }
        q.remove("z");
        out = new ByteArrayOutputStream();
        q.storeToXML(out, "");
        show(out.toByteArray(), StandardCharsets.UTF_8);
        Properties many = new Properties();
        for (int i = 0; i < 20; i++) many.setProperty("key" + i, "v");
        out = new ByteArrayOutputStream();
        many.storeToXML(out, null);
        show(out.toByteArray(), StandardCharsets.UTF_8);
        // loading: other encodings
        for (String enc : new String[] { "UTF-16", "ISO-8859-1", "US-ASCII" }) {
            out = new ByteArrayOutputStream();
            p.storeToXML(out, null, enc);
            Properties r = new Properties();
            r.loadFromXML(new ByteArrayInputStream(out.toByteArray()));
            System.out.println(enc + " back " + r.getProperty("k").replace("\r", "\\r").replace("\n", "\\n").replace("\u0001", "\\1"));
        }
        InputStream in = new ByteArrayInputStream("<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\"><properties/>".getBytes()) {
            public void close() { System.out.println("closed"); }
        };
        new Properties().loadFromXML(in);
        OutputStream o = new ByteArrayOutputStream() {
            public void close() { System.out.println("out closed"); }
            public void flush() { System.out.println("out flushed"); }
        };
        new Properties().storeToXML(o, null);
    }
}
