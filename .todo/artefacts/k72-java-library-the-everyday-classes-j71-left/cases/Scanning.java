import java.io.*;
import java.nio.BufferOverflowException;
import java.nio.CharBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class Scanning {
    public static void main(String[] args) throws IOException {
              Scanning.main0(args);
    }

          // a Readable handing its text in pieces of a few characters
          static class Pieces implements Readable, Closeable {
              private final String text;
              private final int step;
              private int at;
              boolean closed;

              Pieces(String text, int step) {
                  this.text = text;
                  this.step = step;
              }

              public int read(CharBuffer cb) throws IOException {
                  if (text.equals("broken") && at > 0) {
                      throw new IOException("broken");
                  }
                  if (at >= text.length()) {
                      return -1;
                  }
                  int n = Math.min(step, Math.min(cb.remaining(), text.length() - at));
                  cb.put(text, at, at + n);
                  at += n;
                  return n;
              }

              public void close() {
                  closed = true;
              }
          }

          static String shown(String s) {
              return s == null ? "null" : "<" + s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + ">";
          }

          static String tokens(Scanner scanner) {
              StringBuilder all = new StringBuilder();
              while (scanner.hasNext()) {
                  all.append(shown(scanner.next()));
              }
              return all.toString();
          }

          static String refused(Callable<Object> call) {
              try {
                  Object value = call.call();
                  return value instanceof String s ? shown(s) : String.valueOf(value);
              } catch (Exception ex) {
                  return ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : " " + ex.getMessage());
              }
          }

          interface Callable<T> {
              T call() throws Exception;
          }

          static void main0(String[] args) {
              // the whitespace Character.isWhitespace says, the lines \r\n, \r, \n, U+0085, U+2028 and U+2029 end
              Scanner text = new Scanner("12 abc 3\n rest of line\nnext 4.5 TRUE 0x1.8p1 \u0663,\u0664\u0665\u0666\t-1,234\u2003end\u00a0x\u0085y\u2028z");
              System.out.println(text.nextInt() + " " + text.hasNextInt() + " " + text.next() + " " + text.nextInt() + " " + shown(text.nextLine()) + " " + shown(text.nextLine()) + " " + text.next() + " " + text.nextDouble() + " " + text.nextBoolean() + " " + text.nextDouble() + " " + text.nextInt() + " " + text.nextLong() + " " + text.next() + " " + shown(text.nextLine()) + " " + shown(text.nextLine()) + " " + text.hasNextLine());
              for (String[] pair : new String[][] {{"a,b,,c,", ","}, {",,a", ","}, {"a , b ,c", "\\s*,\\s*"}, {"abc", ""}, {"a1b22c", "\\d"}, {"a1b2", "(?=\\d)"}, {"x\r\ny\rz", "\\R"}}) {
                  System.out.print(shown(pair[0]) + " " + tokens(new Scanner(pair[0]).useDelimiter(pair[1])) + " | ");
              }
              System.out.println();
              // the grammar of each kind: any decimal digit is a digit, a group of three after a comma, a sign, a point and an exponent
              for (String token : new String[] {"+0", "-12", "1,000", "10,00", "0,000", "2147483648", "ff", "1e3", ".5", "5.", "-.5e+2", "1,000.25", "NaN", "-Infinity", "\u221e", "0x1.8p1", "0x.8", "1.5f", "\uff11\uff12", "128", "9", "true"}) {
                  StringBuilder line = new StringBuilder(shown(token));
                  line.append(" int ").append(refused(() -> new Scanner(token).nextInt()));
                  line.append(" int8 ").append(refused(() -> new Scanner(token).nextInt(8)));
                  line.append(" int16 ").append(refused(() -> new Scanner(token).nextInt(16)));
                  line.append(" long ").append(refused(() -> new Scanner(token).nextLong()));
                  line.append(" byte ").append(refused(() -> new Scanner(token).nextByte()));
                  line.append(" short ").append(refused(() -> new Scanner(token).nextShort()));
                  line.append(" float ").append(refused(() -> new Scanner(token).nextFloat()));
                  line.append(" double ").append(refused(() -> new Scanner(token).nextDouble()));
                  line.append(" big ").append(refused(() -> new Scanner(token).nextBigInteger()));
                  line.append(" decimal ").append(refused(() -> new Scanner(token).nextBigDecimal()));
                  line.append(" boolean ").append(refused(() -> new Scanner(token).nextBoolean()));
                  System.out.println(line);
              }
              // a refused token stays, past the delimiters; the end of the input
              Scanner rest = new Scanner("12 x\n  ");
              System.out.println(rest.nextInt() + " " + refused(() -> rest.nextInt()) + " " + shown(rest.nextLine()) + " " + refused(() -> rest.next()) + " " + refused(() -> rest.nextLine()) + " " + refused(() -> new Scanner("1").useRadix(37)));
              // finding, skipping, the horizon, and the match each operation leaves
              Scanner found = new Scanner("abc 123 def\nxyz 45 6");
              System.out.print(shown(found.findInLine("\\d+")) + " " + found.match().start() + " " + shown(found.next()) + " " + shown(found.findInLine("x")) + " " + shown(found.nextLine()) + " " + found.match().groupCount() + shown(found.match().group(1)));
              System.out.print(" " + shown(found.findWithinHorizon("4", 3)) + " " + shown(found.findWithinHorizon("4", 0)) + " " + shown(found.skip("5\\s+").next()) + " " + refused(() -> found.skip("x")) + " " + refused(() -> found.match()));
              System.out.println(" " + shown(new Scanner("one two").findWithinHorizon("\\w+", 2)) + " " + shown(new Scanner(" ab").findWithinHorizon("^ab", 0)) + " " + refused(() -> found.findWithinHorizon("a", -1)) + " " + new Scanner("x").findInLine("y") + new Scanner("x").findWithinHorizon("y", 0));
              Scanner matched = new Scanner("ab cd ef");
              System.out.println(matched.hasNext("a.") + " " + matched.match().group() + " " + matched.next(Pattern.compile("(a)(b)")) + " " + matched.match().group(2) + " " + matched.hasNext("x") + " " + refused(() -> matched.next("x")) + " " + matched.next());
              // what a hasNext of a kind keeps for its next, though the delimiter changed
              Scanner kept = new Scanner("5 x");
              kept.hasNextInt();
              kept.useDelimiter("x");
              Scanner dropped = new Scanner("5 x");
              dropped.hasNextInt();
              dropped.useDelimiter("x");
              dropped.findInLine("q");
              System.out.println(kept.nextInt() + " " + refused(() -> dropped.nextInt()) + " " + kept + " " + kept.delimiter() + " " + kept.locale() + " " + kept.radix());
              Scanner closed = new Scanner("a b");
              closed.close();
              System.out.println(refused(() -> closed.next()) + " " + refused(() -> closed.hasNextLine()) + " " + closed.delimiter() + " " + refused(() -> closed.tokens()));
              // the streams, over a scanner nothing else asks meanwhile
              Scanner streamed = new Scanner("x1 y22 z333");
              System.out.print(new Scanner("a b  c\nd").tokens().collect(Collectors.toList()) + " " + streamed.findAll("[a-z](\\d+)").map(m -> m.group(1) + "@" + m.start()).collect(Collectors.toList()) + " " + new Scanner("abc").findAll("").map(m -> m.start() + "-" + m.end()).collect(Collectors.toList()));
              Scanner busy = new Scanner("p q r");
              Iterator<String> walk = busy.tokens().iterator();
              walk.next();
              busy.next();
              System.out.println(" " + refused(() -> walk.next()));
              // over a Readable read in pieces, a stream of bytes in a charset, a source that fails
              Pieces pieces = new Pieces("3000 lines\r\nof text\rend\n\n", 3);
              Scanner readable = new Scanner(pieces);
              System.out.print(readable.nextInt() + " " + shown(readable.nextLine()) + " " + shown(readable.nextLine()) + " " + shown(readable.nextLine()) + " " + shown(readable.nextLine()) + " " + readable.hasNextLine());
              readable.close();
              Scanner bytes = new Scanner(new ByteArrayInputStream("caf\u00e9 1".getBytes(StandardCharsets.ISO_8859_1)), "ISO-8859-1");
              Pieces broken = new Pieces("broken", 4);
              Scanner failing = new Scanner(broken);
              System.out.println(" " + pieces.closed + " " + bytes.next() + bytes.nextInt() + " " + refused(() -> new Scanner(System.in, "no-such-charset")) + " " + failing.next() + " " + failing.hasNext() + " " + failing.ioException());
              // a CharBuffer, the buffer a Readable is handed
              CharBuffer buffer = CharBuffer.allocate(8);
              buffer.put('x').put("yz").append("12");
              buffer.flip();
              System.out.print(buffer + " " + buffer.position() + " " + buffer.limit() + " " + buffer.get() + buffer.get(3) + " " + buffer.subSequence(1, 3) + " " + buffer.length());
              buffer.compact();
              System.out.print(" " + buffer.position() + " " + buffer.limit() + " " + CharBuffer.wrap("abcxy").hashCode() + " " + CharBuffer.wrap("ab").compareTo(CharBuffer.wrap("ac")) + " " + CharBuffer.wrap(new char[] {'h', 'e', 'y'}, 1, 2).slice());
              System.out.println(" " + refused(() -> CharBuffer.allocate(1).put("ab")) + " " + refused(() -> CharBuffer.wrap("ro").put('x')) + " " + refused(() -> CharBuffer.allocate(4).position(5)) + " " + refused(() -> CharBuffer.allocate(4).get(new char[3], 2, 2)));
          }
}
