package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.TypeSection;
import am.ik.wasm.WasmCodeModel.ValType;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent local renumbering: in a function with more than 128 locals
 * (parameters included), the {@code local.get}/{@code local.set}/{@code local.tee}
 * immediates of every local from index 128 up cost two bytes instead of one, and an
 * emitter that hands out a fresh local per temporary and never recycles one puts its
 * most-used locals wherever they happened to be allocated. This pass gives the one-byte
 * indices to the locals with the most uses -- a permutation of the function's own locals,
 * nothing else: parameters keep their indices, every local keeps its type and its uses,
 * no liveness is asked. Within the hot set and within the cold set the locals are grouped
 * by type, so the declaration vector stays a few runs long rather than one run per local.
 * <p>
 * A function whose locals all fit in one byte already is left byte-for-byte alone, and so
 * is one whose best order is the order it has. Where it comes from and what it is worth:
 * {@code .kb/optimize-dead-code-elimination.md}, "A test is compiled as a test" and the
 * local-index row of the residue census.
 */
public final class WasmLocalOrder {

	private WasmLocalOrder() {
	}

	private static final int SEC_TYPE = 1;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_CODE = 10;

	/** The first index whose unsigned LEB128 takes two bytes. */
	private static final int TWO_BYTE_INDEX = 128;

	/**
	 * Reorders the locals of every code entry of a core module.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with its bodies renumbered; the input itself when nothing moves
	 */
	public static byte[] reorder(byte[] module) {
		List<Section> sections = WasmSections.parseSections(module);
		@Nullable Section typeSec = null;
		@Nullable Section functionSec = null;
		@Nullable Section codeSec = null;
		for (Section s : sections) {
			if (s.id() == SEC_TYPE) {
				typeSec = s;
			}
			else if (s.id() == SEC_FUNCTION) {
				functionSec = s;
			}
			else if (s.id() == SEC_CODE) {
				codeSec = s;
			}
		}
		if (typeSec == null || functionSec == null || codeSec == null) {
			return module;
		}
		TypeSection types = WasmCodeModel.parseTypeSection(typeSec.payload());
		int[] defTypeIdx = WasmSections.parseFunctionSection(functionSec.payload());
		List<byte[]> entries = WasmSections.parseCodeEntries(codeSec.payload());
		if (entries.size() != defTypeIdx.length) {
			return module;
		}
		List<byte[]> rewritten = new ArrayList<>(entries.size());
		boolean changed = false;
		for (int d = 0; d < entries.size(); d++) {
			byte[] entry = entries.get(d);
			byte[] out = reorderEntry(entry, types, types.func(defTypeIdx[d]).params().size());
			changed |= out != entry;
			rewritten.add(out);
		}
		if (!changed) {
			return module;
		}
		ByteArrayOutputStream body = new UnsynchronizedByteArrayOutputStream();
		WasmSections.writeU(body, rewritten.size());
		for (byte[] entry : rewritten) {
			WasmSections.writeU(body, entry.length);
			WasmSections.writeRaw(body, entry);
		}
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			rebuilt.add(s.id() == SEC_CODE ? new Section(SEC_CODE, body.toByteArray()) : s);
		}
		return WasmSections.assemble(rebuilt);
	}

	private static boolean isLocalOp(int op) {
		return op == Instruction.GET_LOCAL || op == Instruction.SET_LOCAL || op == Instruction.TEE_LOCAL;
	}

	private static byte[] reorderEntry(byte[] entry, TypeSection types, int params) {
		Body body = WasmCodeModel.decode(entry, types);
		List<ValType> locals = body.locals();
		int total = params + locals.size();
		if (total <= TWO_BYTE_INDEX || params >= TWO_BYTE_INDEX) {
			return entry;
		}
		List<Instr> code = body.code();
		long[] uses = new long[total];
		for (Instr in : code) {
			if (isLocalOp(in.op)) {
				uses[(int) in.a]++;
			}
		}
		// The hot set: the non-parameter locals with the most uses, as many as fit under
		// the two-byte boundary; ties keep declaration order, so the result is a
		// function of the input alone.
		Integer[] byUses = new Integer[locals.size()];
		for (int i = 0; i < byUses.length; i++) {
			byUses[i] = i;
		}
		Arrays.sort(byUses, (x, y) -> Long.compare(uses[params + y], uses[params + x]));
		int hotCount = TWO_BYTE_INDEX - params;
		boolean[] hot = new boolean[locals.size()];
		for (int i = 0; i < hotCount; i++) {
			hot[byUses[i]] = true;
		}
		// Within each set, grouped by type in the order the types first appear, so the
		// declaration vector is one run per (set, type).
		Map<ValType, Integer> typeRank = new LinkedHashMap<>();
		int[] rank = new int[locals.size()];
		for (int i = 0; i < locals.size(); i++) {
			rank[i] = typeRank.computeIfAbsent(locals.get(i), t -> typeRank.size());
		}
		List<Integer> order = new ArrayList<>(locals.size());
		for (boolean wantHot : new boolean[] { true, false }) {
			List<Integer> set = new ArrayList<>();
			for (int i = 0; i < locals.size(); i++) {
				if (hot[i] == wantHot) {
					set.add(i);
				}
			}
			set.sort((x, y) -> Integer.compare(rank[x], rank[y]));
			order.addAll(set);
		}
		int[] newIndex = new int[total];
		for (int i = 0; i < params; i++) {
			newIndex[i] = i;
		}
		boolean identity = true;
		for (int position = 0; position < order.size(); position++) {
			int old = order.get(position);
			newIndex[params + old] = params + position;
			identity &= old == position;
		}
		if (identity) {
			return entry;
		}
		List<ValType> reordered = new ArrayList<>(locals.size());
		for (int old : order) {
			reordered.add(locals.get(old));
		}
		ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream(entry.length);
		WasmCodeModel.writeLocals(out, reordered);
		for (Instr in : code) {
			if (isLocalOp(in.op)) {
				out.write(in.op);
				WasmSections.writeU(out, newIndex[(int) in.a]);
			}
			else {
				WasmSections.writeRaw(out, WasmSections.slice(entry, in.start, in.end));
			}
		}
		return out.toByteArray();
	}

}
