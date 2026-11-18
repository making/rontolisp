import java.util.*;

public class Sb {
    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder("hello world hello");
        System.out.println(sb.lastIndexOf("hello"));
        System.out.println(sb.lastIndexOf("hello", 5));
        System.out.println(sb.lastIndexOf("hello", -1));
        System.out.println(sb.lastIndexOf("", 100));
        System.out.println(sb.indexOf("hello", 1));
        System.out.println(sb.indexOf("hello", -5));
        System.out.println(sb.indexOf("hello", 50));
        System.out.println(sb.indexOf("", 50));
        System.out.println(sb.compareTo(new StringBuilder("hello")));
        System.out.println(sb.compareTo(new StringBuilder("hello world hello")));
        System.out.println(new StringBuilder("a").compareTo(new StringBuilder("b")));
        sb.appendCodePoint(65).appendCodePoint(0x1F600);
        System.out.println(sb);
        try { sb.appendCodePoint(-1); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        try { sb.appendCodePoint(0x110000); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        StringBuilder r = new StringBuilder();
        r.repeat("ab", 3).repeat('x', 2).repeat(0x1F600, 2);
        System.out.println(r);
        try { r.repeat("ab", -1); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        try { r.repeat('c', -1); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        try { r.repeat(-1, 1); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        r.repeat((CharSequence) null, 2);
        System.out.println(r);
    }
}
