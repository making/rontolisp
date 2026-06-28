package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Language-independent dead-code eliminator (tree shaker) for a core WebAssembly module.
 * <p>
 * The rontolisp code generators emit every runtime helper unconditionally and hold
 * function indices fixed (see the index-stability invariant in {@code CLAUDE.md}), so a
 * compiled module embeds the whole runtime even when the program uses almost none of it.
 * This pass removes that bloat: it builds a call graph from the actual {@code call} (and
 * {@code ref.func}) immediates in every function body, computes the set of functions
 * reachable from the module's roots (its exported functions and an optional start
 * function), drops the rest, and renumbers every surviving function reference so the
 * module stays valid.
 * <p>
 * The pass is purely additive and opt-in ({@code --optimize}); it never runs on the
 * default deterministic output. It only renumbers <strong>function</strong> indices:
 * type, memory, global and data sections are copied verbatim, so type indices stay
 * stable. The module's section order is preserved.
 * <p>
 * Correctness rests on two properties of the rontolisp output that this class verifies by
 * construction: the only function references are {@code call} immediates (the backend
 * uses dispatch functions with direct calls rather than {@code call_indirect}/element
 * segments), and the instruction encoding is the finite subset enumerated in
 * {@link #scanInstr}. An unrecognized opcode makes the pass throw rather than silently
 * emit a corrupt module.
 */
public final class WasmTreeShaker {

	private WasmTreeShaker() {
	}

	// Section ids.
	private static final int SEC_IMPORT = 2;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_EXPORT = 7;

	private static final int SEC_START = 8;

	private static final int SEC_CODE = 10;

	// Import / export descriptor kinds.
	private static final int KIND_FUNC = 0x00;

	private static final int KIND_TABLE = 0x01;

	private static final int KIND_MEM = 0x02;

	private static final int KIND_GLOBAL = 0x03;

	private record Section(int id, byte[] payload) {
	}

	/**
	 * A {@code call}/{@code ref.func} site within a function body: the operand byte range
	 * and its old target.
	 */
	private record CallSite(int operandStart, int operandEnd, int target) {
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return an equivalent module with dead functions removed; the input is returned
	 * unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module) {
		List<Section> sections = parseSections(module);

		@Nullable Section importSec = find(sections, SEC_IMPORT);
		@Nullable Section functionSec = find(sections, SEC_FUNCTION);
		@Nullable Section codeSec = find(sections, SEC_CODE);
		@Nullable Section exportSec = find(sections, SEC_EXPORT);
		@Nullable Section startSec = find(sections, SEC_START);

		// Imports: record each entry's raw span and, for function imports, their order.
		List<ImportEntry> imports = importSec == null ? List.of() : parseImports(importSec.payload());
		int numImportedFuncs = 0;
		for (ImportEntry e : imports) {
			if (e.kind == KIND_FUNC) {
				numImportedFuncs++;
			}
		}

		// Defined functions: type indices (function section) aligned with bodies (code
		// section).
		int[] defTypeIdx = functionSec == null ? new int[0] : parseFunctionSection(functionSec.payload());
		List<byte[]> codeEntries = codeSec == null ? new ArrayList<>() : parseCodeEntries(codeSec.payload());
		int numDefined = codeEntries.size();
		int totalFuncs = numImportedFuncs + numDefined;

		// Call graph: outgoing edges per defined function (by global function index).
		List<List<CallSite>> callSites = new ArrayList<>(numDefined);
		for (byte[] entry : codeEntries) {
			callSites.add(scanCallSites(entry));
		}

		// Roots: exported functions plus an optional start function.
		boolean[] reachable = new boolean[totalFuncs];
		Deque<Integer> work = new ArrayDeque<>();
		for (int root : exportFuncRoots(exportSec)) {
			if (root >= 0 && root < totalFuncs && !reachable[root]) {
				reachable[root] = true;
				work.push(root);
			}
		}
		if (startSec != null) {
			int[] p = { 0 };
			int root = readU(startSec.payload(), p);
			if (root >= 0 && root < totalFuncs && !reachable[root]) {
				reachable[root] = true;
				work.push(root);
			}
		}
		while (!work.isEmpty()) {
			int fn = work.pop();
			int defIndex = fn - numImportedFuncs;
			if (defIndex < 0) {
				continue; // imported function: no body, no out-edges
			}
			for (CallSite cs : callSites.get(defIndex)) {
				if (cs.target >= 0 && cs.target < totalFuncs && !reachable[cs.target]) {
					reachable[cs.target] = true;
					work.push(cs.target);
				}
			}
		}

		// Old global function index -> new global function index (kept functions only),
		// preserving order (kept imports first, then kept defined functions).
		int[] remap = new int[totalFuncs];
		int next = 0;
		for (int i = 0; i < totalFuncs; i++) {
			remap[i] = reachable[i] ? next++ : -1;
		}
		if (next == totalFuncs) {
			return module; // nothing to drop
		}

		// Rebuild the affected sections in place, preserving section order.
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			switch (s.id()) {
				case SEC_IMPORT ->
					rebuilt.add(new Section(SEC_IMPORT, rebuildImports(imports, numImportedFuncs, reachable)));
				case SEC_FUNCTION -> rebuilt
					.add(new Section(SEC_FUNCTION, rebuildFunctionSection(defTypeIdx, numImportedFuncs, reachable)));
				case SEC_CODE -> rebuilt.add(new Section(SEC_CODE,
						rebuildCodeSection(codeEntries, callSites, numImportedFuncs, reachable, remap)));
				case SEC_EXPORT -> rebuilt.add(new Section(SEC_EXPORT, rebuildExportSection(s.payload(), remap)));
				case SEC_START -> rebuilt.add(new Section(SEC_START, rebuildStartSection(s.payload(), remap)));
				default -> rebuilt.add(s);
			}
		}
		return assemble(rebuilt);
	}

	// --- Section framing ---

	private static List<Section> parseSections(byte[] module) {
		List<Section> sections = new ArrayList<>();
		int[] p = { 8 }; // skip "\0asm" + version
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = readU(module, p);
			byte[] payload = slice(module, p[0], p[0] + size);
			p[0] += size;
			sections.add(new Section(id, payload));
		}
		return sections;
	}

	private static byte[] assemble(List<Section> sections) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('\0');
		writeRaw(out, "asm".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		writeRaw(out, new byte[] { 1, 0, 0, 0 });
		for (Section s : sections) {
			out.write(s.id());
			writeU(out, s.payload().length);
			writeRaw(out, s.payload());
		}
		return out.toByteArray();
	}

	private static @Nullable Section find(List<Section> sections, int id) {
		for (Section s : sections) {
			if (s.id() == id) {
				return s;
			}
		}
		return null;
	}

	// --- Import section ---

	private record ImportEntry(int kind, byte[] raw) {
	}

	private static List<ImportEntry> parseImports(byte[] payload) {
		List<ImportEntry> entries = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			int start = p[0];
			skipName(payload, p); // module
			skipName(payload, p); // name
			int kind = payload[p[0]++] & 0xff;
			switch (kind) {
				case KIND_FUNC -> readU(payload, p); // typeidx
				case KIND_TABLE -> {
					skipValType(payload, p); // element reftype
					skipLimits(payload, p);
				}
				case KIND_MEM -> skipLimits(payload, p);
				case KIND_GLOBAL -> {
					skipValType(payload, p);
					p[0]++; // mutability
				}
				default -> throw new IllegalStateException("Unknown import kind: " + kind);
			}
			entries.add(new ImportEntry(kind, slice(payload, start, p[0])));
		}
		return entries;
	}

	private static byte[] rebuildImports(List<ImportEntry> imports, int numImportedFuncs, boolean[] reachable) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<ImportEntry> kept = new ArrayList<>();
		int funcOrdinal = 0;
		for (ImportEntry e : imports) {
			if (e.kind == KIND_FUNC) {
				if (reachable[funcOrdinal]) {
					kept.add(e);
				}
				funcOrdinal++;
			}
			else {
				kept.add(e);
			}
		}
		writeU(body, kept.size());
		for (ImportEntry e : kept) {
			writeRaw(body, e.raw);
		}
		return body.toByteArray();
	}

	// --- Function section ---

	private static int[] parseFunctionSection(byte[] payload) {
		int[] p = { 0 };
		int count = readU(payload, p);
		int[] types = new int[count];
		for (int i = 0; i < count; i++) {
			types[i] = readU(payload, p);
		}
		return types;
	}

	private static byte[] rebuildFunctionSection(int[] defTypeIdx, int numImportedFuncs, boolean[] reachable) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<Integer> kept = new ArrayList<>();
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (reachable[numImportedFuncs + i]) {
				kept.add(defTypeIdx[i]);
			}
		}
		writeU(body, kept.size());
		for (int t : kept) {
			writeU(body, t);
		}
		return body.toByteArray();
	}

	// --- Code section ---

	private static List<byte[]> parseCodeEntries(byte[] payload) {
		List<byte[]> entries = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			int size = readU(payload, p);
			entries.add(slice(payload, p[0], p[0] + size));
			p[0] += size;
		}
		return entries;
	}

	private static byte[] rebuildCodeSection(List<byte[]> codeEntries, List<List<CallSite>> callSites,
			int numImportedFuncs, boolean[] reachable, int[] remap) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<byte[]> kept = new ArrayList<>();
		for (int i = 0; i < codeEntries.size(); i++) {
			if (reachable[numImportedFuncs + i]) {
				kept.add(rewriteBody(codeEntries.get(i), callSites.get(i), remap));
			}
		}
		writeU(body, kept.size());
		for (byte[] entry : kept) {
			writeU(body, entry.length);
			writeRaw(body, entry);
		}
		return body.toByteArray();
	}

	// Rewrites a code entry's body so each call/ref.func operand uses the remapped index.
	private static byte[] rewriteBody(byte[] entry, List<CallSite> sites, int[] remap) {
		if (sites.isEmpty()) {
			return entry;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int cursor = 0;
		for (CallSite cs : sites) {
			writeRaw(out, slice(entry, cursor, cs.operandStart));
			writeU(out, remap[cs.target]);
			cursor = cs.operandEnd;
		}
		writeRaw(out, slice(entry, cursor, entry.length));
		return out.toByteArray();
	}

	// --- Export / start sections ---

	private static int[] exportFuncRoots(@Nullable Section exportSec) {
		if (exportSec == null) {
			return new int[0];
		}
		byte[] payload = exportSec.payload();
		int[] p = { 0 };
		int count = readU(payload, p);
		List<Integer> roots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			skipName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			int index = readU(payload, p);
			if (kind == KIND_FUNC) {
				roots.add(index);
			}
		}
		int[] result = new int[roots.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = roots.get(i);
		}
		return result;
	}

	private static byte[] rebuildExportSection(byte[] payload, int[] remap) {
		int[] p = { 0 };
		int count = readU(payload, p);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeU(body, count);
		for (int i = 0; i < count; i++) {
			int nameStart = p[0];
			skipName(payload, p);
			writeRaw(body, slice(payload, nameStart, p[0]));
			int kind = payload[p[0]++] & 0xff;
			int index = readU(payload, p);
			body.write(kind);
			writeU(body, kind == KIND_FUNC ? remap[index] : index);
		}
		return body.toByteArray();
	}

	private static byte[] rebuildStartSection(byte[] payload, int[] remap) {
		int[] p = { 0 };
		int index = readU(payload, p);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeU(body, remap[index]);
		return body.toByteArray();
	}

	// --- Instruction scanning ---

	// Walks one code entry (locals + body) and returns every call/ref.func site.
	private static List<CallSite> scanCallSites(byte[] entry) {
		List<CallSite> sites = new ArrayList<>();
		int[] p = { 0 };
		// Local declarations: count, then (count, valtype) pairs.
		int localGroups = readU(entry, p);
		for (int i = 0; i < localGroups; i++) {
			readU(entry, p); // run length
			skipValType(entry, p);
		}
		// Instruction stream up to the end of the entry.
		while (p[0] < entry.length) {
			scanInstr(entry, p, sites);
		}
		return sites;
	}

	// Advances p past one instruction, recording a CallSite for call (0x10) / ref.func
	// (0xD2).
	private static void scanInstr(byte[] buf, int[] p, List<CallSite> sites) {
		int op = buf[p[0]++] & 0xff;
		if (op >= 0x45 && op <= 0xC4) {
			return; // numeric / comparison / conversion / sign-extension: no immediate
		}
		switch (op) {
			// No immediate.
			case 0x00, 0x01, 0x05, 0x0B, 0x0F, 0x1A, 0x1B, 0xD1, 0xD3 -> {
			}
			// Block types.
			case 0x02, 0x03, 0x04 -> skipBlockType(buf, p);
			// One label/index immediate.
			case 0x0C, 0x0D, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x41, 0x42, 0xD0 -> skipLeb(buf, p);
			case 0x0E -> { // br_table
				int n = readU(buf, p);
				for (int i = 0; i <= n; i++) {
					skipLeb(buf, p);
				}
			}
			case 0x10 -> recordFuncRef(buf, p, sites); // call
			case 0x11 -> { // call_indirect
				skipLeb(buf, p);
				skipLeb(buf, p);
			}
			case 0xD2 -> recordFuncRef(buf, p, sites); // ref.func
			// Memory load/store: align + offset.
			case 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
					0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E -> {
				skipLeb(buf, p);
				skipLeb(buf, p);
			}
			case 0x3F, 0x40 -> p[0]++; // memory.size / memory.grow: one memidx byte
			case 0x43 -> p[0] += 4; // f32.const
			case 0x44 -> p[0] += 8; // f64.const
			case 0xFB -> skipGc(buf, p); // wasm-GC prefix
			default -> throw new IllegalStateException(String.format("WasmTreeShaker: unhandled opcode 0x%02X", op));
		}
	}

	private static void recordFuncRef(byte[] buf, int[] p, List<CallSite> sites) {
		int start = p[0];
		int target = readU(buf, p);
		sites.add(new CallSite(start, p[0], target));
	}

	// Skips a wasm-GC instruction (after the 0xFB prefix) by its sub-opcode.
	private static void skipGc(byte[] buf, int[] p) {
		int sub = readU(buf, p);
		switch (sub) {
			// One type/heaptype immediate.
			case 0x00, 0x01, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x14, 0x15, 0x16, 0x17 -> skipLeb(buf, p);
			// Two immediates (typeidx + field/count/etc.).
			case 0x02, 0x03, 0x04, 0x05, 0x08, 0x09, 0x0A, 0x11, 0x12, 0x13 -> {
				skipLeb(buf, p);
				skipLeb(buf, p);
			}
			// No immediate.
			case 0x0F, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> {
			}
			default ->
				throw new IllegalStateException(String.format("WasmTreeShaker: unhandled GC opcode 0xFB 0x%02X", sub));
		}
	}

	// blocktype := 0x40 | valtype | s33 typeindex
	private static void skipBlockType(byte[] buf, int[] p) {
		int b = buf[p[0]] & 0xff;
		if (b == 0x40) {
			p[0]++;
		}
		else if (isValTypeStart(b)) {
			skipValType(buf, p);
		}
		else {
			skipLeb(buf, p); // s33 type index
		}
	}

	// valtype := numeric | vector | (0x63|0x64) heaptype | abstract-ref shorthand
	private static void skipValType(byte[] buf, int[] p) {
		int b = buf[p[0]++] & 0xff;
		if (b == 0x63 || b == 0x64) {
			skipLeb(buf, p); // heaptype
		}
		// otherwise a single-byte value type (0x7B-0x7F numeric/vector, 0x6F/0x70 ref
		// shorthand)
	}

	private static boolean isValTypeStart(int b) {
		return (b >= 0x7B && b <= 0x7F) || b == 0x70 || b == 0x6F || b == 0x63 || b == 0x64;
	}

	// limits := 0x00 min | 0x01 min max (also tolerates the shared/64 flag bits)
	private static void skipLimits(byte[] buf, int[] p) {
		int flag = buf[p[0]++] & 0xff;
		readU(buf, p); // min
		if ((flag & 0x01) != 0) {
			readU(buf, p); // max
		}
	}

	private static void skipName(byte[] buf, int[] p) {
		int len = readU(buf, p);
		p[0] += len;
	}

	private static void skipLeb(byte[] buf, int[] p) {
		while ((buf[p[0]] & 0x80) != 0) {
			p[0]++;
		}
		p[0]++;
	}

	// --- LEB128 + byte helpers ---

	private static int readU(byte[] buf, int[] p) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xff;
			result |= (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		return result;
	}

	private static void writeU(ByteArrayOutputStream out, int value) {
		int v = value;
		do {
			int b = v & 0x7f;
			v >>>= 7;
			if (v != 0) {
				b |= 0x80;
			}
			out.write(b);
		}
		while (v != 0);
	}

	private static byte[] slice(byte[] src, int from, int to) {
		byte[] dst = new byte[to - from];
		System.arraycopy(src, from, dst, 0, to - from);
		return dst;
	}

	private static void writeRaw(ByteArrayOutputStream out, byte[] bytes) {
		out.write(bytes, 0, bytes.length);
	}

}
