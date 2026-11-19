// The size of the tables under four encodings, each number in base-32 digits: v1 absolute
// code points, v2 distances from the code point mapped, v3 the first code point as a
// distance from the mapping before, v4 that with the runs of compatibility mappings.
// canonPair keeps a composite as its pair, canonFull as its whole decomposition.
// Run: java Sizes.java
import java.text.Normalizer;
import java.util.*;

public class Sizes {
	static String s(int... cps) { return new String(cps, 0, cps.length); }
	static int[] cps(String s) { return s.codePoints().toArray(); }
	static String nf(String s, Normalizer.Form f) { return Normalizer.normalize(s, f); }
	static boolean hangul(int c) { return c >= 0xAC00 && c <= 0xD7A3; }
	static int digits(int n) { int d = 1; while ((n >>>= 5) != 0) d++; return d; }
	static int zz(int n) { return n >= 0 ? 2 * n : -2 * n - 1; }

	record Entry(int key, int[] seq, boolean flagged) {}

	static int[] pair(int c, int[] d) {
		// (P, Q): Q one element of the full decomposition, P the composition of the rest
		for (int j = d.length - 1; j >= 1; j--) {
			int[] rest = new int[d.length - 1];
			for (int i = 0, k = 0; i < d.length; i++) if (i != j) rest[k++] = d[i];
			int[] p = cps(nf(s(rest), Normalizer.Form.NFC));
			if (p.length == 1 && nf(s(p[0], d[j]), Normalizer.Form.NFC).equals(s(c))) return new int[] { p[0], d[j] };
		}
		throw new IllegalStateException(Integer.toHexString(c));
	}

	static int size(List<Entry> es, int variant) {
		int total = 0; int prevKey = 0; int prevFirst = 0;
		for (int i = 0; i < es.size(); i++) {
			Entry e = es.get(i);
			int kd = e.key - prevKey;
			if (variant == 4 && e.seq.length == 1 && kd == 1 && e.seq[0] - prevFirst == 1 && i > 0 && es.get(i-1).seq.length == 1) {
				// in a run: count the run once
				int j = i; int pk = e.key, pf = e.seq[0];
				while (j + 1 < es.size() && es.get(j+1).seq.length == 1 && es.get(j+1).key == pk + 1 && es.get(j+1).seq[0] == pf + 1) { j++; pk++; pf++; }
				total += 1 + digits(j - i + 1); // marker + count
				prevKey = pk; prevFirst = pf; i = j; continue;
			}
			total += digits(kd * 2 + (e.flagged ? 1 : 0)); // key delta with flag (canon only)
			total += 1; // len
			for (int k = 0; k < e.seq.length; k++) {
				if (k == 0 && variant >= 3) total += digits(zz(e.seq[0] - prevFirst));
				else if (variant == 2) total += digits(zz(e.seq[k] - e.key));
				else total += digits(e.seq[k]);
			}
			prevKey = e.key; prevFirst = e.seq[0];
		}
		return total;
	}

	public static void main(String[] a) {
		List<Entry> canon = new ArrayList<>(), canonFull = new ArrayList<>(), compat = new ArrayList<>();
		for (int c = 0; c <= 0x10FFFF; c++) {
			if (c >= 0xD800 && c <= 0xDFFF || hangul(c)) continue;
			String str = s(c);
			String nfd = nf(str, Normalizer.Form.NFD), nfkd = nf(str, Normalizer.Form.NFKD);
			if (!nfd.equals(str)) {
				boolean flagged = nf(str, Normalizer.Form.NFC).equals(str);
				canonFull.add(new Entry(c, cps(nfd), flagged));
				canon.add(new Entry(c, flagged ? pair(c, cps(nfd)) : cps(nfd), flagged));
			}
			if (!nfkd.equals(nfd)) compat.add(new Entry(c, cps(nfkd), false));
		}
		for (int v = 1; v <= 4; v++)
			System.out.println("v" + v + " canonPair=" + size(canon, v) + " canonFull=" + size(canonFull, v) + " compat=" + size(compat, v));
		// pair stats: Q never decomposable, Q last?
		int notLast = 0; Set<Integer> qs = new TreeSet<>(); 
		for (int i = 0; i < canon.size(); i++) { Entry e = canon.get(i); if (e.flagged) { qs.add(e.seq[1]); int[] f = canonFull.get(i).seq; if (f[f.length-1] != e.seq[1]) notLast++; } }
		System.out.println("distinct Q=" + qs.size() + " notLast=" + notLast);
		// compat seq where compat is 1-level different? count compat entries that also have canonical
		// singles
		long singles = canon.stream().filter(e -> e.seq.length == 1).count();
		System.out.println("canon singles=" + singles);
		// how many compat entries have a seq that is ASCII only
		long ascii = compat.stream().filter(e -> Arrays.stream(e.seq).allMatch(x -> x < 128)).count();
		System.out.println("compat ascii-only=" + ascii);
	}
}
