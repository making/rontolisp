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
 * form uses ~16 KB of combined static data (well under the module budget) and depth-10
 * binary search per call.
 *
 * <p>
 * Widens the previous ASCII-only case fold (a-z {@literal <->} A-Z with fixed {@code +/-
 * 32}) so a Latin-1 supplement letter like {@code (code-char 233)} folds to {@code #\É},
 * a Greek letter to its uppercase counterpart, and so on, matching the interpreter and
 * the JVM compile path across the whole Unicode plane.
 */
final class WasmCaseFoldRuntimeBuilder {

	// Byte width of one (from, to, delta) triple in the data segment.
	static final int TRIPLE_BYTES = 12;

	// Cached compressed range tables. Built once per JVM run; the survey walks all
	// 0x110000 code points, so materializing them once and reusing across every WASM
	// compile in the same process matters.
	private static final byte[] UPPER_TABLE_BYTES;

	private static final byte[] LOWER_TABLE_BYTES;

	private static final int UPPER_TABLE_COUNT;

	private static final int LOWER_TABLE_COUNT;

	static {
		List<Range> upper = buildRanges(true);
		List<Range> lower = buildRanges(false);
		UPPER_TABLE_COUNT = upper.size();
		LOWER_TABLE_COUNT = lower.size();
		UPPER_TABLE_BYTES = serialize(upper);
		LOWER_TABLE_BYTES = serialize(lower);
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
	 * Builds {@code _char_upcase} (FUNC_CHAR_UPCASE) or {@code _char_downcase}
	 * (FUNC_CHAR_DOWNCASE): a single code point in, the case-folded code point out. Both
	 * helpers share this body shape with different (offset, count) parameters.
	 * @param tableOffset absolute offset in linear memory where the sorted
	 * {@code (from, to, delta)} triples begin (returned by
	 * {@link WasmLispCompiler.StringTable#appendBlob(byte[])})
	 * @param rangeCount number of triples in the table (upper 690, lower 674 at Unicode
	 * 15)
	 * @return the function body (signature {@code (i32) -> i32}, {@code TYPE_LOOKUP})
	 */
	static byte[] buildBody(int tableOffset, int rangeCount) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: cp = 0. locals: lo = 1, hi = 2, mid = 3, entry = 4, from = 5, to = 6.
		w.write(1);
		w.writeUnsignedLeb128(6);
		w.write(Type.I32);
		int cp = 0, lo = 1, hi = 2, mid = 3, entry = 4, from = 5, to = 6;
		// Empty table: return cp unchanged. Guards against any future table becoming
		// empty for a JVM baseline that has no upcase/downcase entries (never today).
		if (rangeCount == 0) {
			get(w, cp);
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
		// entry = tableOffset + mid * TRIPLE_BYTES
		i32(w, tableOffset);
		get(w, mid);
		i32(w, TRIPLE_BYTES);
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
		// hit: return cp + delta (load signed at entry + 8)
		get(w, cp);
		get(w, entry);
		w.write(Instruction.I32_LOAD, 0x02, 0x08);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// Miss: identity mapping.
		get(w, cp);
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
