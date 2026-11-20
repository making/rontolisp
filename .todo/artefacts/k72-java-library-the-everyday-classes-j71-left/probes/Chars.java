import java.nio.*;
import java.util.*;

// CharBuffer: the members over a buffer of its own array, a wrapped array and a wrapped
// text, what each refuses and with which words (order() is the platform's, so not asked).
public class Chars {
    static String state(CharBuffer b) {
        return "[pos=" + b.position() + " lim=" + b.limit() + " cap=" + b.capacity() + " rem=" + b.remaining() + " ro=" + b.isReadOnly() + " arr=" + b.hasArray() + "]";
    }

    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            Object v = r.call();
            System.out.println(what + ": " + (v instanceof CharBuffer cb ? "<" + cb + "> " + state(cb) : String.valueOf(v)));
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        CharBuffer a = CharBuffer.allocate(8);
        System.out.println(state(a) + " <" + a + "> " + a.length() + " " + a.arrayOffset() + " " + a.isDirect());
        a.put('x').put("yz").put(new char[] {'1', '2'});
        System.out.println(state(a) + " <" + a + ">");
        a.flip();
        System.out.println(state(a) + " <" + a + "> " + a.length() + " " + a.charAt(1) + " " + a.get() + " " + a.get(3) + " " + state(a));
        System.out.println(a.subSequence(1, 3) + " " + state(a.subSequence(1, 3)));
        a.compact();
        System.out.println(state(a) + " <" + Arrays.toString(a.array()) + ">");
        a.clear();
        System.out.println(state(a));
        a.position(2).limit(5).mark();
        a.position(4);
        a.reset();
        System.out.println(state(a));
        tryIt("allocate -1", () -> CharBuffer.allocate(-1));
        tryIt("position 9", () -> CharBuffer.allocate(8).position(9));
        tryIt("position -1", () -> CharBuffer.allocate(8).position(-1));
        tryIt("limit 9", () -> CharBuffer.allocate(8).limit(9));
        tryIt("limit -1", () -> CharBuffer.allocate(8).limit(-1));
        tryIt("position past limit", () -> CharBuffer.allocate(8).limit(3).position(5));
        tryIt("limit below position", () -> CharBuffer.allocate(8).position(5).limit(3));
        tryIt("reset unmarked", () -> CharBuffer.allocate(8).reset());
        tryIt("mark dropped", () -> { CharBuffer b = CharBuffer.allocate(8); b.position(5).mark(); b.position(2); return b.reset(); });
        tryIt("mark dropped by limit", () -> { CharBuffer b = CharBuffer.allocate(8); b.position(5).mark(); b.limit(3); return b.reset(); });
        tryIt("get empty", () -> CharBuffer.allocate(0).get());
        tryIt("put full", () -> CharBuffer.allocate(1).put("ab"));
        tryIt("put full char", () -> CharBuffer.allocate(0).put('a'));
        tryIt("put full array", () -> CharBuffer.allocate(1).put(new char[2]));
        tryIt("get(int) 8", () -> CharBuffer.allocate(8).get(8));
        tryIt("get(int) -1", () -> CharBuffer.allocate(8).get(-1));
        tryIt("put(int) 8", () -> CharBuffer.allocate(8).put(8, 'a'));
        tryIt("get array short", () -> CharBuffer.allocate(2).get(new char[3]));
        tryIt("get array range", () -> CharBuffer.allocate(8).get(new char[3], 2, 2));
        tryIt("put array range", () -> CharBuffer.allocate(8).put(new char[3], 2, 2));
        tryIt("put string range", () -> CharBuffer.allocate(8).put("abc", 2, 4));
        tryIt("put string range back", () -> CharBuffer.allocate(8).put("abc", 2, 1));
        tryIt("charAt 5", () -> CharBuffer.wrap("abc").charAt(5));
        tryIt("subSequence", () -> CharBuffer.wrap("abcdef").subSequence(2, 9));
        tryIt("wrap range", () -> CharBuffer.wrap("abcdef", 2, 4));
        tryIt("wrap range bad", () -> CharBuffer.wrap("abcdef", 4, 2));
        tryIt("wrap array range", () -> CharBuffer.wrap(new char[] {'a', 'b', 'c', 'd'}, 1, 2));
        tryIt("wrap array range bad", () -> CharBuffer.wrap(new char[4], 3, 2));
        tryIt("wrap string put", () -> CharBuffer.wrap("abc").put('x'));
        tryIt("wrap string array", () -> CharBuffer.wrap("abc").array());
        tryIt("wrap string arrayOffset", () -> CharBuffer.wrap("abc").arrayOffset());
        tryIt("readonly put", () -> CharBuffer.allocate(4).asReadOnlyBuffer().put('x'));
        tryIt("readonly array", () -> CharBuffer.allocate(4).asReadOnlyBuffer().array());
        tryIt("readonly compact", () -> CharBuffer.allocate(4).asReadOnlyBuffer().compact());
        CharBuffer w = CharBuffer.wrap(new char[] {'h', 'e', 'l', 'l', 'o'}, 1, 3);
        System.out.println(state(w) + " <" + w + "> " + w.arrayOffset());
        CharBuffer s = w.slice();
        System.out.println(state(s) + " <" + s + "> " + s.arrayOffset() + " " + s.get(0));
        s.put(0, 'E');
        System.out.println(new String(w.array()) + " " + w.duplicate() + " " + state(w.duplicate()));
        CharBuffer sl = CharBuffer.wrap("abcdefgh").slice(2, 3);
        System.out.println(sl + " " + state(sl));
        CharBuffer x = CharBuffer.allocate(6);
        x.append("ab").append('c').append("wxyz", 1, 3);
        x.flip();
        System.out.println(x + " " + x.equals(CharBuffer.wrap("abcxy")) + " " + x.compareTo(CharBuffer.wrap("abd")) + " " + x.hashCode() + " " + CharBuffer.wrap("abcxy").hashCode() + " " + x.mismatch(CharBuffer.wrap("abz")));
        System.out.println(x.isEmpty() + " " + CharBuffer.allocate(0).isEmpty() + " " + x.chars().sum());
        CharBuffer r = CharBuffer.allocate(4);
        CharBuffer src = CharBuffer.wrap("123456");
        tryIt("read", () -> src.read(r) + " " + state(r) + " " + state(src));
        tryIt("read again", () -> src.read(r));
        CharBuffer d = CharBuffer.allocate(4);
        d.put(CharBuffer.wrap("pq"));
        tryIt("put self", () -> d.put(d));
        tryIt("put overflow", () -> d.put(CharBuffer.wrap("123")));
        System.out.println(state(d));
        tryIt("toString of wrapped", () -> { CharBuffer t = CharBuffer.wrap("hello"); t.position(1).limit(4); return t.toString() + " " + t.length() + " " + t.charAt(0) + " " + t.subSequence(0, 2); });
        StringBuilder live = new StringBuilder("abc");
        CharBuffer lv = CharBuffer.wrap(live);
        live.setCharAt(0, 'Z');
        System.out.println("live " + lv);
        tryIt("get(int, char[])", () -> { char[] out = new char[3]; CharBuffer.wrap("abcdef").get(2, out); return new String(out); });
        tryIt("put(int, char[])", () -> { CharBuffer b = CharBuffer.allocate(5); b.put(1, new char[] {'x', 'y'}); return Arrays.toString(b.array()) + " " + state(b); });
        tryIt("put(int, CharBuffer, int, int)", () -> { CharBuffer b = CharBuffer.allocate(5); b.put(1, CharBuffer.wrap("pqrs"), 1, 2); return Arrays.toString(b.array()); });
        CharBuffer pp = CharBuffer.allocate(3);
        pp.put('a');
        tryIt("position(int) returns", () -> pp.position(0) == pp);
        System.out.println(CharBuffer.wrap("a😀b").length() + " " + (int) CharBuffer.wrap("a😀b").charAt(1));
    }
}
