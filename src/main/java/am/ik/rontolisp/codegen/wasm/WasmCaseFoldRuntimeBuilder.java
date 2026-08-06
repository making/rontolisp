package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the {@code _char_upcase(cp) -> cp} / {@code _char_downcase(cp) -> cp} runtime
 * helpers (type {@code TYPE_LOOKUP} = {@code (i32) -> i32}). Each helper binary-searches
 * a sorted table of {@code (from:u32, to:u32, delta:i32)} range triples baked into the
 * static data segment; when the queried code point lies within a range the helper returns
 * {@code cp + delta}, else it returns {@code cp} unchanged. The tables are generated from
 * {@link Character#toUpperCase(int)} / {@link Character#toLowerCase(int)} at compile time
 * so the helper's behavior is byte-for-byte identical to the interpreter's
 * {@code Character.toUpperCase(int)} on the same JVM Unicode baseline. The compressed
 * form uses ~16 KB of combined static data and depth-10 binary search per call. Each
 * table is emitted as its OWN active data segment owned by its sole reader
 * ({@code WasmTreeShaker.OwnedDataSegment}), so {@code --optimize} drops the ~16 KB
 * together with the helpers when the program never case-folds.
 *
 * <p>
 * Widens the previous ASCII-only case fold (a-z {@literal <->} A-Z with fixed {@code +/-
 * 32}) so a Latin-1 supplement letter like {@code (code-char 233)} folds to {@code #\É},
 * a Greek letter to its uppercase counterpart, and so on, matching the interpreter and
 * the JVM compile path across the whole Unicode plane.
 *
 * <p>
 * A third table in the same shape backs {@code _char_alnum_p} (FUNC_CHAR_ALNUM_P), the
 * full-Unicode {@code Character.isLetterOrDigit(int)} membership test
 * {@code string-capitalize} uses to find its word boundaries. It carries no delta, so its
 * entries are {@code (from, to)} PAIRS and it shares the binary search with a different
 * stride.
 */
final class WasmCaseFoldRuntimeBuilder {

	// Byte width of one (from, to, delta) triple in a case-fold data segment.
	static final int TRIPLE_BYTES = 12;

	// Byte width of one (from, to) pair in the character-class data segment.
	static final int PAIR_BYTES = 8;

	// Cached compressed range tables. Built once per JVM run; the survey walks all
	// 0x110000 code points, so materializing them once and reusing across every WASM
	// compile in the same process matters.
	private static final byte[] UPPER_TABLE_BYTES;

	private static final byte[] LOWER_TABLE_BYTES;

	private static final byte[] ALNUM_TABLE_BYTES;

	private static final int UPPER_TABLE_COUNT;

	private static final int LOWER_TABLE_COUNT;

	private static final int ALNUM_TABLE_COUNT;

	private static final int MAX_UTF8_GROWTH;

	static {
		List<Range> upper = buildRanges(true);
		List<Range> lower = buildRanges(false);
		List<Range> alnum = buildAlnumRanges();
		UPPER_TABLE_COUNT = upper.size();
		LOWER_TABLE_COUNT = lower.size();
		ALNUM_TABLE_COUNT = alnum.size();
		UPPER_TABLE_BYTES = serialize(upper);
		LOWER_TABLE_BYTES = serialize(lower);
		ALNUM_TABLE_BYTES = serializePairs(alnum);
		MAX_UTF8_GROWTH = Math.max(maxUtf8Growth(upper), maxUtf8Growth(lower));
	}

	private WasmCaseFoldRuntimeBuilder() {
	}

	/** The compressed {@code Character.toUpperCase(int)} range table, ready to bake. */
	static byte[] upperTableBytes() {
		return UPPER_TABLE_BYTES;
	}

	/** The compressed {@code Character.toLowerCase(int)} range table, ready to bake. */
	static byte[] lowerTableBytes() {
		return LOWER_TABLE_BYTES;
	}

	static int upperTableCount() {
		return UPPER_TABLE_COUNT;
	}

	static int lowerTableCount() {
		return LOWER_TABLE_COUNT;
	}

	/**
	 * The compressed {@code Character.isLetterOrDigit(int)} range table, ready to bake.
	 * Membership only, so the entries are {@code (from, to)} PAIRS rather than triples.
	 */
	static byte[] alnumTableBytes() {
		return ALNUM_TABLE_BYTES;
	}

	static int alnumTableCount() {
		return ALNUM_TABLE_COUNT;
	}

	/**
	 * The largest number of EXTRA UTF-8 bytes a single case fold can produce, over every
	 * mapping in either table. Currently 1 (e.g. {@code U+0250} upcases to
	 * {@code U+2C6F}: two bytes in, three out). {@code string-upcase} /
	 * {@code string-downcase} size their output scratch as
	 * {@code inputBytes * (1 + this)}, which is a valid bound because a code point
	 * occupies at least one input byte -- derived from the tables rather than assumed, so
	 * a future JDK Unicode baseline that folds more widely widens the bound with it.
	 * @return the maximum per-code-point UTF-8 byte growth
	 */
	static int maxUtf8Growth() {
		return MAX_UTF8_GROWTH;
	}

	/**
	 * Builds {@code _char_upcase} (FUNC_CHAR_UPCASE) or {@code _char_downcase}
	 * (FUNC_CHAR_DOWNCASE): a single code point in, the case-folded code point out. Both
	 * helpers share this body shape with different (offset, count) parameters.
	 * @param tableOffset absolute offset in linear memory where the sorted
	 * {@code (from, to, delta)} triples begin (the table's own data segment offset,
	 * placed right after the string data in {@code WasmLispCompiler.compile})
	 * @param rangeCount number of triples in the table (upper 690, lower 674 at Unicode
	 * 15)
	 * @return the function body (signature {@code (i32) -> i32}, {@code TYPE_LOOKUP})
	 */
	static byte[] buildBody(int tableOffset, int rangeCount) {
		return buildSearchBody(tableOffset, rangeCount, TRIPLE_BYTES, true);
	}

	/**
	 * Builds {@code _char_alnum_p} (FUNC_CHAR_ALNUM_P): 1 when the code point is a letter
	 * or a digit, else 0. Same binary search as {@link #buildBody}, over a table of
	 * {@code (from, to)} pairs and answering membership instead of a delta.
	 * {@code string-capitalize} tests its word boundaries with it, so the WASM word
	 * constituents are the full-Unicode {@code Character.isLetterOrDigit(int)} set the
	 * interpreter and the JVM compile path already use.
	 * @param tableOffset absolute offset in linear memory where the sorted
	 * {@code (from, to)} pairs begin
	 * @param rangeCount number of pairs in the table (728 at Unicode 16)
	 * @return the function body (signature {@code (i32) -> i32}, {@code TYPE_LOOKUP})
	 */
	static byte[] buildAlnumBody(int tableOffset, int rangeCount) {
		return buildSearchBody(tableOffset, rangeCount, PAIR_BYTES, false);
	}

	// The shared binary search over a sorted (from, to[, delta]) range table. On a hit it
	// returns cp + delta (foldOnHit) or 1 (a membership test); on a miss cp unchanged, or
	// 0 respectively.
	private static byte[] buildSearchBody(int tableOffset, int rangeCount, int stride, boolean foldOnHit) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: cp = 0. locals: lo = 1, hi = 2, mid = 3, entry = 4, from = 5, to = 6.
		w.write(1);
		w.writeUnsignedLeb128(6);
		w.write(Type.I32);
		int cp = 0, lo = 1, hi = 2, mid = 3, entry = 4, from = 5, to = 6;
		// Empty table: nothing can match. Guards against any future table becoming
		// empty for a JVM baseline that has no entries at all (never today).
		if (rangeCount == 0) {
			if (foldOnHit) {
				get(w, cp);
			}
			else {
				i32(w, 0);
			}
			w.write(Instruction.END);
			return body.toByteArray();
		}
		// lo = 0; hi = rangeCount - 1
		i32(w, 0);
		set(w, lo);
		i32(w, rangeCount - 1);
		set(w, hi);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// exit when lo > hi -> break out; the identity fallback is emitted after the
		// block.
		get(w, lo);
		get(w, hi);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.BR_IF, 1);
		// mid = (lo + hi) >>> 1
		get(w, lo);
		get(w, hi);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, mid);
		// entry = tableOffset + mid * stride
		i32(w, tableOffset);
		get(w, mid);
		i32(w, stride);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		set(w, entry);
		// from = load i32(entry + 0); to = load i32(entry + 4)
		get(w, entry);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, from);
		get(w, entry);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		set(w, to);
		// if cp < from: hi = mid - 1; continue
		get(w, cp);
		get(w, from);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		get(w, mid);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, hi);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// if cp > to: lo = mid + 1; continue
		get(w, cp);
		get(w, to);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		get(w, mid);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, lo);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// hit: return cp + delta (load signed at entry + 8), or 1 for a membership test
		if (foldOnHit) {
			get(w, cp);
			get(w, entry);
			w.write(Instruction.I32_LOAD, 0x02, 0x08);
			w.write(Instruction.I32_ADD);
		}
		else {
			i32(w, 1);
		}
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// Miss: identity mapping, or "not a member".
		if (foldOnHit) {
			get(w, cp);
		}
		else {
			i32(w, 0);
		}
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Builds the compressed contiguous-delta range table for Character.toUpperCase(int)
	// (upperCase=true) or Character.toLowerCase(int). Two code points enter the same
	// range only when they are contiguous AND share the same signed delta -- an
	// alternating "Aa Bb ..." block therefore yields a stream of singleton ranges
	// (~43% of entries), which is fine at the ~16 KB combined budget.
	private static List<Range> buildRanges(boolean upperCase) {
		List<Range> out = new ArrayList<>();
		int rangeStart = -1;
		int rangeEnd = -1;
		int rangeDelta = 0;
		for (int cp = Character.MIN_CODE_POINT; cp <= Character.MAX_CODE_POINT; cp++) {
			int mapped = upperCase ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
			if (mapped == cp) {
				if (rangeStart >= 0) {
					out.add(new Range(rangeStart, rangeEnd, rangeDelta));
					rangeStart = -1;
				}
				continue;
			}
			int delta = mapped - cp;
			if (rangeStart >= 0 && cp == rangeEnd + 1 && delta == rangeDelta) {
				rangeEnd = cp;
			}
			else {
				if (rangeStart >= 0) {
					out.add(new Range(rangeStart, rangeEnd, rangeDelta));
				}
				rangeStart = cp;
				rangeEnd = cp;
				rangeDelta = delta;
			}
		}
		if (rangeStart >= 0) {
			out.add(new Range(rangeStart, rangeEnd, rangeDelta));
		}
		return out;
	}

	// Builds the compressed membership range table for Character.isLetterOrDigit(int).
	// Contiguous members merge into one range regardless of delta (there is none), which
	// is why 0x110000 code points compress to a few hundred entries.
	private static List<Range> buildAlnumRanges() {
		List<Range> out = new ArrayList<>();
		int rangeStart = -1;
		int rangeEnd = -1;
		for (int cp = Character.MIN_CODE_POINT; cp <= Character.MAX_CODE_POINT; cp++) {
			if (Character.isLetterOrDigit(cp)) {
				if (rangeStart < 0) {
					rangeStart = cp;
				}
				rangeEnd = cp;
			}
			else if (rangeStart >= 0) {
				out.add(new Range(rangeStart, rangeEnd, 0));
				rangeStart = -1;
			}
		}
		if (rangeStart >= 0) {
			out.add(new Range(rangeStart, rangeEnd, 0));
		}
		return out;
	}

	// The largest UTF-8 byte growth any single mapping in the given table produces.
	private static int maxUtf8Growth(List<Range> ranges) {
		int max = 0;
		for (Range r : ranges) {
			for (int cp = r.from; cp <= r.to; cp++) {
				max = Math.max(max, utf8Length(cp + r.delta) - utf8Length(cp));
			}
		}
		return max;
	}

	private static int utf8Length(int cp) {
		if (cp < 0x80) {
			return 1;
		}
		if (cp < 0x800) {
			return 2;
		}
		return cp < 0x10000 ? 3 : 4;
	}

	// Serializes the ranges as little-endian (from:u32, to:u32, delta:i32) triples.
	// The runtime helper's i32.load matches this ordering (WASM linear memory is LE).
	private static byte[] serialize(List<Range> ranges) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(ranges.size() * TRIPLE_BYTES);
		for (Range r : ranges) {
			writeLE32(out, r.from);
			writeLE32(out, r.to);
			writeLE32(out, r.delta);
		}
		return out.toByteArray();
	}

	// Serializes the ranges as little-endian (from:u32, to:u32) pairs -- a membership
	// table carries no delta.
	private static byte[] serializePairs(List<Range> ranges) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(ranges.size() * PAIR_BYTES);
		for (Range r : ranges) {
			writeLE32(out, r.from);
			writeLE32(out, r.to);
		}
		return out.toByteArray();
	}

	private static void writeLE32(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF);
		out.write((v >>> 8) & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>> 24) & 0xFF);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private record Range(int from, int to, int delta) {
	}

}
