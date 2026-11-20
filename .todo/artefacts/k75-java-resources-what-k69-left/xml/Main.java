import java.io.*;
import java.nio.charset.*;
import java.util.*;

public class Main {
    static void load(String xml) {
        Properties p = new Properties();
        try {
            String d = "<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">";
            if (!xml.contains("DOCTYPE")) { int at = xml.startsWith("<?xml") ? xml.indexOf("?>") + 2 : 0; xml = xml.substring(0, at) + d + xml.substring(at); }
            p.loadFromXML(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            System.out.println("OK " + new TreeMap<>(p).toString().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").replace("\u0000", "\\0"));
        } catch (Exception e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }
    public static void main(String[] args) throws Exception {
        String h = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">\n";
        load(h + "<properties><entry key=\"a\">1</entry></properties>");
        load("<properties><entry key=\"a\">1</entry></properties>");
        load("<properties><entry key='a'>x&lt;&#65;&#x42;<![CDATA[<c>&amp;]]><!-- c --></entry><entry key=\"e\"/></properties>");
        load("<properties><entry>1</entry></properties>");
        load("<properties><foo/></properties>");
        load("<props/>");
        load("<properties><entry key=\"a\">1</entry>");
        load("");
        load("<properties><comment>c</comment><comment>d</comment></properties>");
        load("<properties><entry key=\"a\">1</entry><comment>c</comment></properties>");
        load("<properties>text<entry key=\"a\">1</entry></properties>");
        load("<properties><entry key=\"a\">&nope;</entry></properties>");
        load("<properties><entry key=\"a\" other=\"x\">1</entry></properties>");
        load("<properties><entry key=\"a\"><b/></entry></properties>");
        load("<properties version=\"1\"/>");
        load("<?xml version=\"1.0\"?><properties>\r\n<entry key=\"a\">x\r\ny\rz</entry>\n</properties>");
        load("<properties><entry key=\"a\">1</entry><entry key=\"a\">2</entry></properties>");
        load("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><properties><entry key=\"a\">é</entry></properties>");
        load("<!DOCTYPE properties SYSTEM \"other.dtd\"><properties/>");
        load("<!DOCTYPE foo SYSTEM \"http://java.sun.com/dtd/properties.dtd\"><properties/>");
        load("<properties><entry key=\"a\">1</entry></properties><x/>");
        load("<properties><entry key=\"a\">1</entry></properties>junk");
        load("  <properties/>  ");
        load("<?xml version=\"1.1\"?><properties/>");
        load("<properties><entry key=\"a\">&#0;</entry></properties>");
        load("<properties><entry key=\"a\" key=\"b\">1</entry></properties>");
        load("<properties><entry key=\"<\">1</entry></properties>");
        load("<properties><entry key=\"a\">a]]>b</entry></properties>");
        load("<properties><entry key=\"a&quot;&apos;\">1</entry></properties>");
        load("<properties><entry key=\"a\"><entry key=\"b\"/></entry></properties>");
        load("<properties><entry key=\"a\">x&nope;y</entry></properties>");
        load("<properties><entry key=\"a&nope;b\">x</entry></properties>");
        load("<properties><entry key=\"a\">x&#xD;y&#13;z&#x9;</entry></properties>");
        load("<properties><properties/></properties>");
        load("<properties><comment>c<b/></comment></properties>");
        load("<properties><entry key=\"a\">1</entry><comment>c</comment><comment>d</comment></properties>");
        load("<properties><entry key = 'a' >1</entry ></properties >");
        load("<properties><entry key=\"a\">\t x \n</entry></properties>");
        load("<properties><entry key=\" a\tb\nc \">1</entry></properties>");
        load("<?xml version=\"1.0\" encoding=\"nope\"?><properties/>");
        load("<?xml version=\"1.0\" standalone=\"yes\"?><properties/>");
        load("<?xml version='1.0' encoding='utf-8' ?><properties/>");
        load("<?xml version=\"1.0\"?><?pi data?><!-- c --><!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\"><!-- d --><properties/><!-- e --><?pi?>");
        load("<!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'><properties/>");
        load("<!DOCTYPE properties PUBLIC \"x\" \"http://java.sun.com/dtd/properties.dtd\"><properties/>");
        load("<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\" [ <!ENTITY e \"v\"> ]><properties><entry key=\"a\">&e;</entry></properties>");
        load("<!DOCTYPE properties><properties/>");
        load("<!DOCTYPE  properties  SYSTEM  \"http://java.sun.com/dtd/properties.dtd\" ><properties/>");
        load("<properties><entry key=\"a\">1</entry><Entry key=\"b\">2</Entry></properties>");
        load("<properties><entry key=\"a\">a</b></entry></properties>");
        load("<properties><entry key=\"a\">a<</entry></properties>");
        load("<properties><entry key=\"a\">a&</entry></properties>");
        load("<properties><entry key=\"a\">a>b</entry></properties>");
        load("<properties><entry key=\"a\">&#xZZ;</entry></properties>");
        load("<properties><entry key=\"a\">&#x110000;</entry></properties>");
        load("<properties><entry key=\"a\">&#x1F600;</entry></properties>");
        load("<properties><entry key=a>1</entry></properties>");
        load("<properties><entry key=\"a\">1</entry></properties>\n\n");
        load("﻿<properties/>");
        
        Properties p = new Properties();
        p.setProperty("b", "two & <three> \"q\" 'a'");
        p.setProperty("a", "one\ttab\nline\r\u0001x\u00e9\ud83d\ude00\u3042");
        p.setProperty("", "");
        p.setProperty("k]]>", "]]>");
        p.setProperty("q\"'<&", "\u3042");
        for (String enc : new String[] { "UTF-8", "ISO-8859-1", "US-ASCII", "UTF-16", "utf-16le" }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.storeToXML(out, enc.equals("UTF-8") ? "a comment -- with <tags> & \u00e9" : enc.equals("US-ASCII") ? "" : null, enc);
            System.out.println(enc + " " + out.size() + " " + Arrays.toString(Arrays.copyOf(out.toByteArray(), 4)) + " " + Arrays.hashCode(out.toByteArray()));
            Properties back = new Properties();
            back.loadFromXML(new ByteArrayInputStream(out.toByteArray()));
            System.out.println(new TreeMap<>(back).toString().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").replace("\u0001", "\\1"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.storeToXML(out, "c");
        System.out.println(out.toString(StandardCharsets.UTF_8).replace("\r", "\\r").replace("\u0001", "\\1"));
        out = new ByteArrayOutputStream();
        p.storeToXML(out, null, StandardCharsets.ISO_8859_1);
        System.out.println(out.toString(StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\u0001", "\\1"));
        p.put("n", 1);
        out = new ByteArrayOutputStream();
        try { p.storeToXML(out, null); } catch (Exception e) { System.out.println(e + " " + out.size()); }
        try { p.storeToXML(new ByteArrayOutputStream(), null, "nope"); } catch (Exception e) { System.out.println(e); }
        try { p.storeToXML(null, null); } catch (Exception e) { System.out.println(e); }
        try { new Properties().loadFromXML(null); } catch (Exception e) { System.out.println(e); }
        try { new Properties().loadFromXML(new ByteArrayInputStream("<?xml version=\"1.0\" encoding=\"nope\"?><properties/>".getBytes())); } catch (Exception e) { System.out.println(e); }
    }
}
