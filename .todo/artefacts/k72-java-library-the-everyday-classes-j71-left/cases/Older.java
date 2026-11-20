import java.util.*;
import java.util.stream.*;

public class Older {
    public static void main(String[] args) {
        joiners();
        tokens();
        bits();
    }

          static void joiners() {
              StringJoiner csv = new StringJoiner(", ", "[", "]");
              System.out.print(csv + " " + csv.length() + " ");
              csv.setEmptyValue("none");
              System.out.print(csv + " " + csv.length() + " ");
              csv.add("a").add(null).add(new StringBuilder("sb")).add("");
              StringJoiner other = new StringJoiner("-", "{", "}");
              other.add("x").add("y");
              csv.merge(other).merge(new StringJoiner("|"));
              System.out.println(csv + " " + csv.length());
              StringJoiner self = new StringJoiner("/");
              self.add("p").add("q");
              self.merge(self);
              StringJoiner empty = new StringJoiner(",", "<", ">");
              empty.add("");
              System.out.println(self + " " + empty + " " + empty.length() + " " + String.join("+", List.of("j", "k")) + " " + Stream.of("m", "n").collect(Collectors.joining(";", "(", ")")));
              for (int attempt = 0; attempt < 4; attempt++) {
                  try {
                      switch (attempt) {
                          case 0 -> new StringJoiner(null, null, "]");
                          case 1 -> new StringJoiner(",", "[", null);
                          case 2 -> new StringJoiner(",").setEmptyValue(null);
                          default -> new StringJoiner(",").merge(null);
                      }
                  } catch (NullPointerException ex) {
                      System.out.print(ex.getMessage() + "; ");
                  }
              }
              System.out.println();
          }

          static void tokens() {
              StringTokenizer words = new StringTokenizer(" one two\tthree\nfour ");
              StringBuilder seen = new StringBuilder().append(words.countTokens());
              while (words.hasMoreTokens()) {
                  seen.append(' ').append(words.nextToken());
              }
              StringTokenizer kept = new StringTokenizer("a=1;b=;=c", "=;", true);
              seen.append(" |").append(kept.countTokens());
              while (kept.hasMoreElements()) {
                  seen.append(' ').append(kept.nextElement());
              }
              StringTokenizer changed = new StringTokenizer("k1,v1;k2,v2", ",");
              seen.append(" | ").append(changed.nextToken()).append(' ').append(changed.nextToken(";")).append(' ').append(changed.countTokens()).append(' ').append(changed.nextToken());
              System.out.println(seen + " " + Collections.list(new StringTokenizer("x y")));
              StringTokenizer done = new StringTokenizer("only");
              done.nextToken();
              try {
                  done.nextToken();
              } catch (NoSuchElementException ex) {
                  System.out.println("NoSuchElementException " + ex.getMessage() + " " + new StringTokenizer("", null).countTokens());
              }
          }

          static void bits() {
              BitSet a = new BitSet();
              a.set(1);
              a.set(3, 6);
              a.set(64);
              a.flip(0, 3);
              a.clear(4);
              BitSet b = BitSet.valueOf(new long[] {0b101010L, 1L << 63});
              System.out.println(a + " " + a.cardinality() + " " + a.length() + " " + a.size() + " " + a.hashCode() + " " + b + " " + b.length() + " " + Arrays.toString(b.toByteArray()));
              BitSet and = (BitSet) a.clone();
              and.and(b);
              BitSet or = (BitSet) a.clone();
              or.or(b);
              BitSet xor = (BitSet) a.clone();
              xor.xor(b);
              BitSet andNot = (BitSet) a.clone();
              andNot.andNot(b);
              System.out.println(and + " " + or + " " + xor + " " + andNot + " " + a.intersects(b) + " " + a.equals(a.clone()) + " " + a.get(2, 70) + " " + Arrays.toString(or.toLongArray()));
              StringBuilder walk = new StringBuilder();
              for (int i = a.nextSetBit(0); i >= 0; i = a.nextSetBit(i + 1)) {
                  walk.append(i).append(' ');
              }
              walk.append('|');
              for (int i = a.length(); (i = a.previousSetBit(i - 1)) >= 0;) {
                  walk.append(' ').append(i);
              }
              System.out.println(walk + " " + a.nextClearBit(0) + " " + a.nextClearBit(64) + " " + a.previousClearBit(3) + " " + a.stream().sum() + " " + a.stream().boxed().collect(Collectors.toList()));
              BitSet sized = new BitSet(100);
              sized.set(99);
              BitSet grown = new BitSet();
              grown.set(200);
              grown.clear(200);
              System.out.println(sized.size() + " " + ((BitSet) sized.clone()).size() + " " + grown.size() + " " + ((BitSet) grown.clone()).size() + " " + grown.isEmpty() + " " + new BitSet().equals(grown));
              for (int attempt = 0; attempt < 5; attempt++) {
                  try {
                      switch (attempt) {
                          case 0 -> new BitSet(-1);
                          case 1 -> a.set(-1);
                          case 2 -> a.set(5, 3);
                          case 3 -> a.nextSetBit(-2);
                          default -> a.previousSetBit(-2);
                      }
                  } catch (NegativeArraySizeException | IndexOutOfBoundsException ex) {
                      System.out.print(ex.getClass().getSimpleName() + " " + ex.getMessage() + "; ");
                  }
              }
              System.out.println();
          }
}
