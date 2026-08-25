package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Byte-level toolbox for a core WebAssembly module's sections: framing
 * ({@link #parseSections}/{@link #assemble}), per-section parsers, the instruction walk
 * that records every function and type index immediate ({@link #scanBody}), the
 * function-reference rewriters shared by the whole-module passes, and the LEB128
 * primitives under all of it. {@link WasmTreeShaker} (reachability) and
 * {@code WasmBodyFolder} (duplicate-body folding) are both built on this class and on
 * nothing else of each other's.
 * <p>
 * The instruction encoding understood here is the finite subset the rontolisp backends
 * emit, enumerated in {@link #scanInstr}; an unrecognized opcode makes a scan throw
 * rather than silently mis-frame a module.
 */
public final class WasmSections {

	private WasmSections() {
	}

	// The one section id this class needs by name (the walkers take ids from callers).
	private static final int SEC_IMPORT = 2;

	// Import / export descriptor kinds.
	static final int KIND_FUNC = 0x00;

	static final int KIND_TABLE = 0x01;

	static final int KIND_MEM = 0x02;

	static final int KIND_GLOBAL = 0x03;

	record Section(int id, byte[] payload) {
	}

	/** How an index immediate is encoded, hence how a rewritten one must be written. */
	enum RefKind {

		/** A function index (unsigned LEB): a {@code call} / {@code ref.func} operand. */
		FUNC,
		/** A type index encoded as an unsigned LEB (an instruction's {@code typeidx}). */
		TYPE_U,
		/** A type index encoded as a signed s33 (a {@code heaptype} or a blocktype). */
		TYPE_S

	}

	/**
	 * An index immediate to rewrite: the byte range it occupies in the buffer it was
	 * scanned from, and the old index it holds. Refs are produced by a single forward
	 * walk, so a ref list is always ascending by {@code start} -- which is what
	 * {@code WasmTreeShaker.applyRefs} relies on.
	 */
	record Ref(int start, int end, int index, RefKind kind) {
	}

	/**
	 * A {@code call}/{@code ref.func} site within a function body: the operand byte range
	 * and its old target.
	 */
	record CallSite(int operandStart, int operandEnd, int target) {
	}

	/**
	 * Counts the module's imported functions (they precede the defined functions in the
	 * function index space, so a code-section walk needs this to place its entries).
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the number of function imports
	 */
	public static int importedFunctionCount(byte[] module) {
		@Nullable Section importSec = find(parseSections(module), SEC_IMPORT);
		if (importSec == null) {
			return 0;
		}
		int count = 0;
		for (ImportEntry e : parseImports(importSec.payload())) {
			if (e.kind == KIND_FUNC) {
				count++;
			}
		}
		return count;
	}

	// The name a custom section carries, or null when its payload is not even a valid
	// name.
	static @Nullable String customSectionName(byte[] payload) {
		if (payload.length == 0) {
			return null;
		}
		int[] p = { 0 };
		int len = readU(payload, p);
		if (len < 0 || p[0] + len > payload.length) {
			return null;
		}
		return new String(payload, p[0], len, java.nio.charset.StandardCharsets.UTF_8);
	}

	static List<Section> parseSections(byte[] module) {
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

	static byte[] assemble(List<Section> sections) {
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

	static @Nullable Section find(List<Section> sections, int id) {
		for (Section s : sections) {
			if (s.id() == id) {
				return s;
			}
		}
		return null;
	}

	/**
	 * One {@code rectype} entry of the type section: its byte span within the payload,
	 * the type indices it defines ({@code typeCount} consecutive indices from
	 * {@code firstTypeIndex} -- more than one for a {@code rec} group), and every type
	 * index its own definition mentions.
	 */
	record TypeEntry(int start, int end, int firstTypeIndex, int typeCount, List<Ref> refs) {
	}

	static List<TypeEntry> parseTypeSection(byte[] payload) {
		int[] p = { 0 };
		int count = readU(payload, p);
		List<TypeEntry> entries = new ArrayList<>();
		int typeIndex = 0;
		for (int i = 0; i < count; i++) {
			int start = p[0];
			List<Ref> refs = new ArrayList<>();
			int members;
			if ((payload[p[0]] & 0xff) == 0x4E) { // rec group
				p[0]++;
				members = readU(payload, p);
				for (int k = 0; k < members; k++) {
					scanSubType(payload, p, refs);
				}
			}
			else {
				members = 1;
				scanSubType(payload, p, refs);
			}
			entries.add(new TypeEntry(start, p[0], typeIndex, members, refs));
			typeIndex += members;
		}
		return entries;
	}

	// subtype := 0x50 vec(typeidx) comptype | 0x4F vec(typeidx) comptype | comptype
	private static void scanSubType(byte[] buf, int[] p, List<Ref> refs) {
		int b = buf[p[0]] & 0xff;
		if (b == 0x50 || b == 0x4F) {
			p[0]++;
			int supertypes = readU(buf, p);
			for (int i = 0; i < supertypes; i++) {
				recordTypeIdx(buf, p, refs);
			}
		}
		scanCompType(buf, p, refs);
	}

	// comptype := 0x60 functype | 0x5E arraytype | 0x5F structtype
	private static void scanCompType(byte[] buf, int[] p, List<Ref> refs) {
		int b = buf[p[0]++] & 0xff;
		switch (b) {
			case 0x60 -> { // func
				int params = readU(buf, p);
				for (int i = 0; i < params; i++) {
					scanValType(buf, p, refs);
				}
				int results = readU(buf, p);
				for (int i = 0; i < results; i++) {
					scanValType(buf, p, refs);
				}
			}
			case 0x5E -> scanFieldType(buf, p, refs); // array
			case 0x5F -> { // struct
				int fields = readU(buf, p);
				for (int i = 0; i < fields; i++) {
					scanFieldType(buf, p, refs);
				}
			}
			default -> throw new IllegalStateException(String.format("WasmSections: unhandled comptype tag 0x%02X", b));
		}
	}

	// fieldtype := storagetype mut; storagetype := valtype | i8 (0x78) | i16 (0x77)
	private static void scanFieldType(byte[] buf, int[] p, List<Ref> refs) {
		int b = buf[p[0]] & 0xff;
		if (b == 0x78 || b == 0x77) {
			p[0]++;
		}
		else {
			scanValType(buf, p, refs);
		}
		p[0]++; // mutability
	}

	record ImportEntry(int kind, byte[] raw, List<Ref> refs) {
	}

	static List<ImportEntry> parseImports(byte[] payload) {
		List<ImportEntry> entries = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			int start = p[0];
			List<Ref> refs = new ArrayList<>();
			skipName(payload, p); // module
			skipName(payload, p); // name
			int kind = payload[p[0]++] & 0xff;
			switch (kind) {
				case KIND_FUNC -> recordTypeIdx(payload, p, refs); // typeidx
				case KIND_TABLE -> {
					scanValType(payload, p, refs); // element reftype
					skipLimits(payload, p);
				}
				case KIND_MEM -> skipLimits(payload, p);
				case KIND_GLOBAL -> {
					scanValType(payload, p, refs);
					p[0]++; // mutability
				}
				default -> throw new IllegalStateException("Unknown import kind: " + kind);
			}
			List<Ref> relative = new ArrayList<>(refs.size());
			for (Ref r : refs) {
				relative.add(new Ref(r.start() - start, r.end() - start, r.index(), r.kind()));
			}
			entries.add(new ImportEntry(kind, slice(payload, start, p[0]), relative));
		}
		return entries;
	}

	static int[] parseFunctionSection(byte[] payload) {
		int[] p = { 0 };
		int count = readU(payload, p);
		int[] types = new int[count];
		for (int i = 0; i < count; i++) {
			types[i] = readU(payload, p);
		}
		return types;
	}

	// globalsec := vec(globaltype expr); globaltype := valtype mut
	static List<Ref> scanGlobalSection(byte[] payload) {
		return scanGlobalSection(payload, null);
	}

	static List<Ref> scanGlobalSection(byte[] payload, @Nullable IntList i32Constants) {
		List<Ref> refs = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			scanValType(payload, p, refs);
			p[0]++; // mutability
			scanConstExpr(payload, p, refs, i32Constants);
		}
		return refs;
	}

	// tagsec := vec(0x00 typeidx). Tags are never dropped (throw/try_table reference them
	// by index), so every tag's type stays live.
	static List<Ref> scanTagSection(byte[] payload) {
		List<Ref> refs = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			int attribute = payload[p[0]++] & 0xff;
			if (attribute != 0x00) {
				throw new IllegalStateException("WasmSections: unhandled tag attribute " + attribute);
			}
			recordTypeIdx(payload, p, refs);
		}
		return refs;
	}

	// A constant initializer expression: instructions up to its terminating `end`.
	private static void scanConstExpr(byte[] buf, int[] p, List<Ref> refs, @Nullable IntList i32Constants) {
		while ((buf[p[0]] & 0xff) != 0x0B) {
			scanInstr(buf, p, refs, i32Constants);
		}
		p[0]++; // end
	}

	static List<byte[]> parseCodeEntries(byte[] payload) {
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

	static byte[] rebuildExportSection(byte[] payload, int[] remap) {
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

	static byte[] rebuildStartSection(byte[] payload, int[] remap) {
		int[] p = { 0 };
		int index = readU(payload, p);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeU(body, remap[index]);
		return body.toByteArray();
	}

	// Walks one code entry (locals + body) and returns every function and type reference,
	// optionally collecting the i32.const immediates on the way (a droppable data range
	// survives when one of them addresses it).
	static List<Ref> scanBody(byte[] entry) {
		return scanBody(entry, null);
	}

	static List<Ref> scanBody(byte[] entry, @Nullable IntList i32Constants) {
		List<Ref> refs = new ArrayList<>();
		int[] p = { 0 };
		// Local declarations: count, then (count, valtype) pairs.
		int localGroups = readU(entry, p);
		for (int i = 0; i < localGroups; i++) {
			readU(entry, p); // run length
			scanValType(entry, p, refs);
		}
		// Instruction stream up to the end of the entry.
		while (p[0] < entry.length) {
			scanInstr(entry, p, refs, i32Constants);
		}
		return refs;
	}

	/**
	 * Every {@code call}/{@code ref.func} site in one code entry. Kept for
	 * {@link WasmImportInjector}, which renumbers functions only.
	 * @param entry a code section entry (locals + body)
	 * @return the call sites in body order
	 */
	static List<CallSite> scanCallSites(byte[] entry) {
		List<CallSite> sites = new ArrayList<>();
		for (Ref r : scanBody(entry)) {
			if (r.kind() == RefKind.FUNC) {
				sites.add(new CallSite(r.start(), r.end(), r.index()));
			}
		}
		return sites;
	}

	// Advances p past one instruction, recording every function and type immediate.
	private static void scanInstr(byte[] buf, int[] p, List<Ref> refs, @Nullable IntList i32Constants) {
		int op = buf[p[0]++] & 0xff;
		if (op >= 0x45 && op <= 0xC4) {
			return; // numeric / comparison / conversion / sign-extension: no immediate
		}
		switch (op) {
			// No immediate (0x0A = throw_ref, exception-handling proposal).
			case 0x00, 0x01, 0x05, 0x0A, 0x0B, 0x0F, 0x1A, 0x1B, 0xD1, 0xD3 -> {
			}
			// Block types.
			case 0x02, 0x03, 0x04 -> scanBlockType(buf, p, refs); // block / loop / if
			// throw (exception-handling proposal): one tag-index immediate. Tags have
			// their own index space, so function remapping does not touch it.
			case 0x08 -> skipLeb(buf, p);
			// try_table (exception-handling proposal): a blocktype, then a vector of
			// catch clauses -- catch/catch_ref carry a tag index and a label,
			// catch_all/catch_all_ref a label only. Labels and tag indices are both
			// remap-free here (only function and type indices are renumbered).
			case 0x1F -> {
				scanBlockType(buf, p, refs);
				int clauses = readU(buf, p);
				for (int i = 0; i < clauses; i++) {
					int kind = buf[p[0]++] & 0xff;
					switch (kind) {
						case 0x00, 0x01 -> { // catch / catch_ref: tag + label
							skipLeb(buf, p);
							skipLeb(buf, p);
						}
						case 0x02, 0x03 -> skipLeb(buf, p); // catch_all / catch_all_ref
						default -> throw new IllegalStateException(
								String.format("WasmSections: unhandled catch clause kind 0x%02X", kind));
					}
				}
			}
			// One label/index immediate.
			case 0x0C, 0x0D, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x42 -> skipLeb(buf, p);
			case 0x41 -> { // i32.const: also a candidate linear-memory address
				int value = readS(buf, p);
				if (i32Constants != null) {
					i32Constants.add(value);
				}
			}
			case 0xD0 -> recordHeapType(buf, p, refs); // ref.null
			case 0x0E -> { // br_table
				int n = readU(buf, p);
				for (int i = 0; i <= n; i++) {
					skipLeb(buf, p);
				}
			}
			case 0x10 -> recordFuncRef(buf, p, refs); // call
			case 0x11 -> { // call_indirect
				recordTypeIdx(buf, p, refs);
				skipLeb(buf, p); // tableidx
			}
			case 0xD2 -> recordFuncRef(buf, p, refs); // ref.func
			// Memory load/store: align + offset.
			case 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
					0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E -> {
				skipLeb(buf, p);
				skipLeb(buf, p);
			}
			case 0x3F, 0x40 -> p[0]++; // memory.size / memory.grow: one memidx byte
			case 0x43 -> p[0] += 4; // f32.const
			case 0x44 -> p[0] += 8; // f64.const
			case 0xFB -> scanGc(buf, p, refs); // wasm-GC prefix
			// Misc prefix: the saturating truncations (0x00-0x07) carry no immediate.
			case 0xFC -> {
				int sub = readU(buf, p);
				if (sub > 0x07) {
					throw new IllegalStateException(
							String.format("WasmSections: unhandled misc opcode 0xFC 0x%02X", sub));
				}
			}
			case 0xFD -> skipSimd(buf, p); // fixed-width SIMD prefix
			default -> throw new IllegalStateException(String.format("WasmSections: unhandled opcode 0x%02X", op));
		}
	}

	private static void recordFuncRef(byte[] buf, int[] p, List<Ref> refs) {
		int start = p[0];
		int target = readU(buf, p);
		refs.add(new Ref(start, p[0], target, RefKind.FUNC));
	}

	// An unsigned-LEB typeidx immediate.
	private static void recordTypeIdx(byte[] buf, int[] p, List<Ref> refs) {
		int start = p[0];
		int index = readU(buf, p);
		refs.add(new Ref(start, p[0], index, RefKind.TYPE_U));
	}

	// A heaptype (s33): negative values are the abstract shorthands (eq, i31, ...), which
	// name no type definition; a non-negative one is a concrete type index.
	private static void recordHeapType(byte[] buf, int[] p, List<Ref> refs) {
		int start = p[0];
		int index = readS(buf, p);
		if (index >= 0) {
			refs.add(new Ref(start, p[0], index, RefKind.TYPE_S));
		}
	}

	// Scans a wasm-GC instruction (after the 0xFB prefix) by its sub-opcode.
	private static void scanGc(byte[] buf, int[] p, List<Ref> refs) {
		int sub = readU(buf, p);
		switch (sub) {
			// struct.new / struct.new_default / array.new / array.new_default /
			// array.get(_s|_u) / array.set / array.fill: one typeidx.
			case 0x00, 0x01, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x10 -> recordTypeIdx(buf, p, refs);
			// ref.test / ref.cast (nullable and not): one heaptype.
			case 0x14, 0x15, 0x16, 0x17 -> recordHeapType(buf, p, refs);
			// struct.get(_s|_u) / struct.set: typeidx + fieldidx.
			// array.new_fixed: typeidx + element count.
			case 0x02, 0x03, 0x04, 0x05, 0x08 -> {
				recordTypeIdx(buf, p, refs);
				skipLeb(buf, p);
			}
			case 0x11 -> { // array.copy: destination typeidx + source typeidx
				recordTypeIdx(buf, p, refs);
				recordTypeIdx(buf, p, refs);
			}
			// No immediate.
			case 0x0F, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> {
			}
			// array.new_data / array.new_elem / array.init_data / array.init_elem carry a
			// dataidx / elemidx this pass does not renumber -- and it DOES drop data
			// segments (see OwnedDataSegment), so accepting one would silently corrupt
			// the module. The backend emits none of them.
			default ->
				throw new IllegalStateException(String.format("WasmSections: unhandled GC opcode 0xFB 0x%02X", sub));
		}
	}

	// Skips a fixed-width SIMD instruction (after the 0xFD prefix) by its u32-LEB
	// sub-opcode. SIMD instructions carry no function or type references, so this only
	// advances p.
	// Only the sub-opcodes the compilers emit (the packed-float-vector kernels:
	// NoGcWasmCompiler f64x2 / f32x4) are handled; an unknown one throws so a
	// newly-emitted SIMD op with different immediates is caught rather than silently
	// mis-skipped (mirroring scanGc).
	private static void skipSimd(byte[] buf, int[] p) {
		int sub = readU(buf, p);
		switch (sub) {
			// v128.load / v128.store: a memarg (align + offset).
			case 0x00, 0x0B -> {
				skipLeb(buf, p);
				skipLeb(buf, p);
			}
			// v128.const / i8x16.shuffle: sixteen immediate bytes (lane values /
			// indices).
			case 0x0C, 0x0D -> p[0] += 16;
			// f32x4 / f64x2 extract_lane and replace_lane: one lane-index byte.
			case 0x1F, 0x20, 0x21, 0x22 -> p[0]++;
			// splat + lane-wise arithmetic (f32x4 / f64x2 abs/neg/sqrt/add/sub/mul/div/
			// min/max), lane-wise lt/gt masks, v128.bitselect and
			// f64x2.promote_low_f32x4: no immediate.
			case 0x13, 0x14, 0x43, 0x44, 0x49, 0x4A, 0x52, 0x5F, 0xE0, 0xE1, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xEC, 0xED,
					0xEF, 0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5 ->
				{
				}
			default ->
				throw new IllegalStateException(String.format("WasmSections: unhandled SIMD opcode 0xFD 0x%X", sub));
		}
	}

	// blocktype := 0x40 | valtype | s33 typeindex
	private static void scanBlockType(byte[] buf, int[] p, List<Ref> refs) {
		int b = buf[p[0]] & 0xff;
		if (b == 0x40) {
			p[0]++;
		}
		else if (isValTypeStart(b)) {
			scanValType(buf, p, refs);
		}
		else {
			int start = p[0];
			int index = readS(buf, p); // s33 type index (never negative in this form)
			refs.add(new Ref(start, p[0], index, RefKind.TYPE_S));
		}
	}

	// valtype := numeric | vector | (0x63|0x64) heaptype | abstract-ref shorthand
	private static void scanValType(byte[] buf, int[] p, List<Ref> refs) {
		int b = buf[p[0]++] & 0xff;
		if (b == 0x63 || b == 0x64) {
			recordHeapType(buf, p, refs);
		}
		// otherwise a single-byte value type (0x7B-0x7F numeric/vector, 0x69-0x74
		// abstract-ref shorthand), which names no type definition
	}

	/**
	 * Whether the byte can begin a {@code valtype} -- the disambiguator for a blocktype,
	 * whose third alternative is an s33 type index.
	 * <p>
	 * The abstract-reference shorthands are the whole contiguous {@code 0x69-0x74} range,
	 * and ALL of them have to be listed: the emitter writes every nullable reference to
	 * an abstract heap type that way ({@code block (result eqref)} is the single byte
	 * {@code 6D}), and a shorthand missing here falls through to the s33 arm and is
	 * recorded as a type reference at the negative index it decodes to. That is a read
	 * that does not describe the module -- the rewriter then indexes its remap table with
	 * a negative number, so the symptom lands far from the cause, and a module with
	 * nothing to drop escapes only because the pass returned early. The range is
	 * unambiguous because a blocktype's s33 is a type index, hence non-negative, so its
	 * first byte is either {@code 0x00-0x3F} (a small index) or has the continuation bit
	 * set -- never {@code 0x40-0x7F}.
	 * @param b the first byte of the blocktype
	 * @return true when the byte begins a value type rather than a type index
	 */
	private static boolean isValTypeStart(int b) {
		return (b >= 0x7B && b <= 0x7F) || (b >= 0x69 && b <= 0x74) || b == 0x63 || b == 0x64;
	}

	// limits := 0x00 min | 0x01 min max (also tolerates the shared/64 flag bits)
	private static void skipLimits(byte[] buf, int[] p) {
		int flag = buf[p[0]++] & 0xff;
		readU(buf, p); // min
		if ((flag & 0x01) != 0) {
			readU(buf, p); // max
		}
	}

	static void skipName(byte[] buf, int[] p) {
		int len = readU(buf, p);
		p[0] += len;
	}

	private static void skipLeb(byte[] buf, int[] p) {
		while ((buf[p[0]] & 0x80) != 0) {
			p[0]++;
		}
		p[0]++;
	}

	static int readU(byte[] buf, int[] p) {
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

	static int readS(byte[] buf, int[] p) {
		int result = 0;
		int shift = 0;
		int b;
		do {
			b = buf[p[0]++] & 0xff;
			result |= (b & 0x7f) << shift;
			shift += 7;
		}
		while ((b & 0x80) != 0);
		if (shift < 32 && (b & 0x40) != 0) {
			result |= -(1 << shift);
		}
		return result;
	}

	static void writeU(ByteArrayOutputStream out, int value) {
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

	static void writeS(ByteArrayOutputStream out, int value) {
		int v = value;
		while (true) {
			int b = v & 0x7f;
			v >>= 7;
			if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
				out.write(b);
				return;
			}
			out.write(b | 0x80);
		}
	}

	/** A growable {@code int} buffer: the scanned {@code i32.const} immediates. */
	static final class IntList {

		private int[] values = new int[16];

		private int size;

		void add(int value) {
			if (this.size == this.values.length) {
				int[] grown = new int[this.values.length * 2];
				System.arraycopy(this.values, 0, grown, 0, this.size);
				this.values = grown;
			}
			this.values[this.size++] = value;
		}

		void addAll(IntList other) {
			for (int i = 0; i < other.size; i++) {
				add(other.values[i]);
			}
		}

		int[] toSortedArray() {
			int[] copy = java.util.Arrays.copyOf(this.values, this.size);
			java.util.Arrays.sort(copy);
			return copy;
		}

	}

	static byte[] slice(byte[] src, int from, int to) {
		byte[] dst = new byte[to - from];
		System.arraycopy(src, from, dst, 0, to - from);
		return dst;
	}

	static void writeRaw(ByteArrayOutputStream out, byte[] bytes) {
		out.write(bytes, 0, bytes.length);
	}

}
