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
 * The same walk collects every <strong>type</strong> index the survivors still mention
 * (function signatures, GC-op immediates, block types, locals, globals, tags, imports,
 * plus the type-to-type edges inside the type section itself), so unreferenced type
 * definitions are dropped and the rest renumbered as well. A {@code rec} group is kept or
 * dropped atomically: its members occupy consecutive indices and its structural identity
 * under wasm-GC canonicalization is a property of the whole group.
 * <p>
 * The pass is purely additive and opt-in ({@code --optimize}); it never runs on the
 * default deterministic output. The module's section order is preserved.
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
	private static final int SEC_TYPE = 1;

	private static final int SEC_IMPORT = 2;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_TABLE = 4;

	private static final int SEC_GLOBAL = 6;

	private static final int SEC_EXPORT = 7;

	private static final int SEC_START = 8;

	private static final int SEC_ELEMENT = 9;

	private static final int SEC_CODE = 10;

	private static final int SEC_DATA = 11;

	private static final int SEC_TAG = 13;

	// Import / export descriptor kinds.
	private static final int KIND_FUNC = 0x00;

	private static final int KIND_TABLE = 0x01;

	private static final int KIND_MEM = 0x02;

	private static final int KIND_GLOBAL = 0x03;

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
	 * {@link #applyRefs} relies on.
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
	 * A data segment whose bytes are referenced EXCLUSIVELY by the given functions, so it
	 * may be dropped whenever every one of those functions is unreachable. The claim of
	 * exclusivity is the caller's: this pass cannot see linear-memory references (they
	 * are indistinguishable {@code i32.const} immediates), so it drops the segment purely
	 * on the owners' reachability. Dropping leaves an uninitialized (all-zero) hole in
	 * linear memory at the segment's offset -- sound precisely because nothing reachable
	 * reads it. Segment indices are the positions in the data section; the backend emits
	 * no bulk-memory instructions ({@code memory.init}/{@code data.drop}), so removing a
	 * segment never breaks a {@code dataidx} reference.
	 *
	 * @param segmentIndex index of the segment within the data section
	 * @param ownerFuncIndices global function indices (pre-shake) that own the segment
	 */
	public record OwnedDataSegment(int segmentIndex, int[] ownerFuncIndices) {
	}

	/**
	 * A byte range WITHIN one active data segment that may be dropped when no surviving
	 * function body still addresses it -- the sub-segment counterpart of
	 * {@link OwnedDataSegment}, for a segment holding many independently-referenced
	 * pieces (the string blob). Where {@code OwnedDataSegment} takes the caller's word
	 * for who owns the bytes, this form is decided by OBSERVATION: the pass keeps the
	 * range when any surviving body holds an {@code i32.const} addressing into it (its
	 * whole span plus the one-past-the-end address, so a range reached through an
	 * interior or end pointer survives too). A linear-memory reference is an
	 * indistinguishable {@code i32.const}, which makes the test conservative in the safe
	 * direction: an unrelated constant that happens to land inside a range only KEEPS
	 * bytes.
	 * <p>
	 * What the caller still owes: a range must not be cited from anywhere the scan cannot
	 * see -- notably a word inside another DATA blob. Dropping cuts the range out and
	 * re-emits the segment as one active segment per surviving run, each at the absolute
	 * address it already had, so no surviving reference moves.
	 *
	 * @param segmentIndex index of the segment within the data section
	 * @param start offset of the range within that segment's bytes
	 * @param end end offset (exclusive) of the range within that segment's bytes
	 */
	public record DroppableDataRange(int segmentIndex, int start, int end) {
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return an equivalent module with dead functions removed; the input is returned
	 * unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module) {
		return shake(module, List.of());
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors,
	 * then drops every {@link OwnedDataSegment} whose owners all died with them.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param ownedDataSegments data segments to drop when their owning functions are all
	 * unreachable
	 * @return an equivalent module with dead functions and orphaned data segments
	 * removed; the input is returned unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module, List<OwnedDataSegment> ownedDataSegments) {
		return shake(module, ownedDataSegments, List.of());
	}

	/**
	 * Removes functions unreachable from the module's roots and renumbers the survivors,
	 * drops every {@link OwnedDataSegment} whose owners all died with them and every
	 * {@link DroppableDataRange} no survivor still addresses, and drops every type
	 * definition the survivors no longer name.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param ownedDataSegments data segments to drop when their owning functions are all
	 * unreachable
	 * @param droppableDataRanges byte ranges within a data segment to cut out when no
	 * surviving body holds an {@code i32.const} addressing into them
	 * @return an equivalent module with dead functions, orphaned data and unreferenced
	 * types removed; the input is returned unchanged when nothing is dropped
	 */
	public static byte[] shake(byte[] module, List<OwnedDataSegment> ownedDataSegments,
			List<DroppableDataRange> droppableDataRanges) {
		List<Section> sections = parseSections(module);

		@Nullable Section typeSec = find(sections, SEC_TYPE);
		@Nullable Section importSec = find(sections, SEC_IMPORT);
		@Nullable Section functionSec = find(sections, SEC_FUNCTION);
		@Nullable Section globalSec = find(sections, SEC_GLOBAL);
		@Nullable Section tagSec = find(sections, SEC_TAG);
		@Nullable Section codeSec = find(sections, SEC_CODE);
		@Nullable Section dataSec = find(sections, SEC_DATA);
		@Nullable Section exportSec = find(sections, SEC_EXPORT);
		@Nullable Section startSec = find(sections, SEC_START);
		// A table or element section would carry reference types and function indices
		// this pass does not renumber. The backend emits neither (first-class calls go
		// through dispatch functions), so their presence means the module is not the
		// shape this pass verifies by construction.
		for (Section s : sections) {
			if (s.id() == SEC_TABLE || s.id() == SEC_ELEMENT) {
				throw new IllegalStateException("WasmTreeShaker: unhandled section id " + s.id());
			}
		}

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

		// Call graph, type references and -- only when a droppable range makes them
		// matter -- the i32.const immediates: one forward walk per defined function.
		List<List<Ref>> bodyRefs = new ArrayList<>(numDefined);
		List<@Nullable IntList> bodyConstants = new ArrayList<>(numDefined);
		boolean needConstants = !droppableDataRanges.isEmpty();
		for (byte[] entry : codeEntries) {
			@Nullable IntList constants = needConstants ? new IntList() : null;
			bodyRefs.add(scanBody(entry, constants));
			bodyConstants.add(constants);
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
			for (Ref r : bodyRefs.get(defIndex)) {
				if (r.kind() == RefKind.FUNC && r.index() >= 0 && r.index() < totalFuncs && !reachable[r.index()]) {
					reachable[r.index()] = true;
					work.push(r.index());
				}
			}
		}

		// Old global function index -> new global function index (kept functions only),
		// preserving order (kept imports first, then kept defined functions).
		int[] funcRemap = new int[totalFuncs];
		int next = 0;
		for (int i = 0; i < totalFuncs; i++) {
			funcRemap[i] = reachable[i] ? next++ : -1;
		}

		// Types still named by the survivors, closed over the type section's own edges.
		List<TypeEntry> typeEntries = typeSec == null ? List.of() : parseTypeSection(typeSec.payload());
		int totalTypes = 0;
		for (TypeEntry e : typeEntries) {
			totalTypes += e.typeCount();
		}
		List<Ref> globalRefs = globalSec == null ? List.of() : scanGlobalSection(globalSec.payload());
		List<Ref> tagRefs = tagSec == null ? List.of() : scanTagSection(tagSec.payload());
		boolean[] typeUsed = markUsedTypes(typeEntries, totalTypes, imports, reachable, numImportedFuncs, defTypeIdx,
				bodyRefs, globalRefs, tagRefs);
		int[] typeRemap = new int[totalTypes];
		int nextType = 0;
		for (TypeEntry e : typeEntries) {
			for (int k = 0; k < e.typeCount(); k++) {
				typeRemap[e.firstTypeIndex() + k] = typeUsed[e.firstTypeIndex()] ? nextType++ : -1;
			}
		}

		// Data segments whose owners all died go with them.
		List<Integer> deadSegments = new ArrayList<>();
		for (OwnedDataSegment owned : ownedDataSegments) {
			if (!anyOwnerAlive(owned.ownerFuncIndices(), reachable, totalFuncs)) {
				deadSegments.add(owned.segmentIndex());
			}
		}
		// ... and so do the ranges inside a surviving segment that no survivor addresses.
		List<DataSegment> dataSegments = dataSec == null ? List.of() : parseDataSection(dataSec.payload());
		List<DroppableDataRange> deadRanges = deadRanges(droppableDataRanges, dataSegments, deadSegments, reachable,
				numImportedFuncs, bodyConstants, globalConstants(globalSec));

		if (next == totalFuncs && nextType == totalTypes && deadSegments.isEmpty() && deadRanges.isEmpty()) {
			return module; // nothing to drop
		}

		// Rebuild the affected sections in place, preserving section order.
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			switch (s.id()) {
				case SEC_TYPE -> rebuilt.add(new Section(SEC_TYPE,
						rebuildTypeSection(s.payload(), typeEntries, typeUsed, funcRemap, typeRemap)));
				case SEC_IMPORT ->
					rebuilt.add(new Section(SEC_IMPORT, rebuildImports(imports, reachable, funcRemap, typeRemap)));
				case SEC_FUNCTION -> rebuilt.add(new Section(SEC_FUNCTION,
						rebuildFunctionSection(defTypeIdx, numImportedFuncs, reachable, typeRemap)));
				case SEC_GLOBAL ->
					rebuilt.add(new Section(SEC_GLOBAL, applyRefs(s.payload(), globalRefs, funcRemap, typeRemap)));
				case SEC_TAG ->
					rebuilt.add(new Section(SEC_TAG, applyRefs(s.payload(), tagRefs, funcRemap, typeRemap)));
				case SEC_CODE -> rebuilt.add(new Section(SEC_CODE,
						rebuildCodeSection(codeEntries, bodyRefs, numImportedFuncs, reachable, funcRemap, typeRemap)));
				case SEC_EXPORT -> rebuilt.add(new Section(SEC_EXPORT, rebuildExportSection(s.payload(), funcRemap)));
				case SEC_START -> rebuilt.add(new Section(SEC_START, rebuildStartSection(s.payload(), funcRemap)));
				case SEC_DATA -> rebuilt.add(deadSegments.isEmpty() && deadRanges.isEmpty() ? s
						: new Section(SEC_DATA, rebuildDataSection(dataSegments, deadSegments, deadRanges)));
				default -> rebuilt.add(s);
			}
		}
		return assemble(rebuilt);
	}

	private static boolean anyOwnerAlive(int[] owners, boolean[] reachable, int totalFuncs) {
		for (int owner : owners) {
			if (owner >= 0 && owner < totalFuncs && reachable[owner]) {
				return true;
			}
		}
		return false;
	}

	// --- Section framing ---

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

	private static @Nullable Section find(List<Section> sections, int id) {
		for (Section s : sections) {
			if (s.id() == id) {
				return s;
			}
		}
		return null;
	}

	// Splices a buffer, replacing each recorded immediate with its remapped value. The
	// refs must be ascending and non-overlapping (they come from one forward walk), and
	// the replacement's LEB length may differ from the original's -- exactly as the
	// function renumbering already relies on.
	private static byte[] applyRefs(byte[] buf, List<Ref> refs, int[] funcRemap, int[] typeRemap) {
		if (refs.isEmpty()) {
			return buf;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int cursor = 0;
		for (Ref r : refs) {
			writeRaw(out, slice(buf, cursor, r.start()));
			int[] remap = r.kind() == RefKind.FUNC ? funcRemap : typeRemap;
			// A kept site naming a dropped definition means the reachability walk missed
			// an edge; -1 would encode as a five-byte index and validate as nothing.
			if (remap[r.index()] < 0) {
				throw new IllegalStateException(
						"WasmTreeShaker: surviving " + r.kind() + " reference to dropped index " + r.index());
			}
			switch (r.kind()) {
				case FUNC, TYPE_U -> writeU(out, remap[r.index()]);
				case TYPE_S -> writeS(out, remap[r.index()]);
			}
			cursor = r.end();
		}
		writeRaw(out, slice(buf, cursor, buf.length));
		return out.toByteArray();
	}

	// --- Type section ---

	/**
	 * One {@code rectype} entry of the type section: its byte span within the payload,
	 * the type indices it defines ({@code typeCount} consecutive indices from
	 * {@code firstTypeIndex} -- more than one for a {@code rec} group), and every type
	 * index its own definition mentions.
	 */
	private record TypeEntry(int start, int end, int firstTypeIndex, int typeCount, List<Ref> refs) {
	}

	private static List<TypeEntry> parseTypeSection(byte[] payload) {
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
			default ->
				throw new IllegalStateException(String.format("WasmTreeShaker: unhandled comptype tag 0x%02X", b));
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

	// Marks every type index the surviving module still names, closed over the type
	// section's own edges. A rec group is atomic: naming one member keeps them all (their
	// indices are consecutive and their structural identity is a property of the group),
	// and every member's own references are then live too.
	private static boolean[] markUsedTypes(List<TypeEntry> typeEntries, int totalTypes, List<ImportEntry> imports,
			boolean[] reachable, int numImportedFuncs, int[] defTypeIdx, List<List<Ref>> bodyRefs, List<Ref> globalRefs,
			List<Ref> tagRefs) {
		boolean[] used = new boolean[totalTypes];
		Deque<Integer> work = new ArrayDeque<>();
		// entryOf[t] = the index of the rectype entry defining type t.
		int[] entryOf = new int[totalTypes];
		for (int i = 0; i < typeEntries.size(); i++) {
			TypeEntry e = typeEntries.get(i);
			for (int k = 0; k < e.typeCount(); k++) {
				entryOf[e.firstTypeIndex() + k] = i;
			}
		}
		int funcOrdinal = 0;
		for (ImportEntry e : imports) {
			boolean kept = e.kind != KIND_FUNC || reachable[funcOrdinal];
			if (e.kind == KIND_FUNC) {
				funcOrdinal++;
			}
			if (kept) {
				seed(e.refs, used, work, totalTypes);
			}
		}
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (reachable[numImportedFuncs + i]) {
				seedIndex(defTypeIdx[i], used, work, totalTypes);
			}
		}
		for (int i = 0; i < bodyRefs.size(); i++) {
			if (reachable[numImportedFuncs + i]) {
				seed(bodyRefs.get(i), used, work, totalTypes);
			}
		}
		seed(globalRefs, used, work, totalTypes);
		seed(tagRefs, used, work, totalTypes);
		while (!work.isEmpty()) {
			TypeEntry e = typeEntries.get(entryOf[work.pop()]);
			for (int k = 0; k < e.typeCount(); k++) {
				used[e.firstTypeIndex() + k] = true;
			}
			for (Ref r : e.refs()) {
				seedIndex(r.index(), used, work, totalTypes);
			}
		}
		return used;
	}

	private static void seed(List<Ref> refs, boolean[] used, Deque<Integer> work, int totalTypes) {
		for (Ref r : refs) {
			if (r.kind() != RefKind.FUNC) {
				seedIndex(r.index(), used, work, totalTypes);
			}
		}
	}

	private static void seedIndex(int index, boolean[] used, Deque<Integer> work, int totalTypes) {
		if (index >= 0 && index < totalTypes && !used[index]) {
			used[index] = true;
			work.push(index);
		}
	}

	private static byte[] rebuildTypeSection(byte[] payload, List<TypeEntry> entries, boolean[] typeUsed,
			int[] funcRemap, int[] typeRemap) {
		List<byte[]> kept = new ArrayList<>();
		for (TypeEntry e : entries) {
			if (!typeUsed[e.firstTypeIndex()]) {
				continue;
			}
			byte[] raw = slice(payload, e.start(), e.end());
			List<Ref> refs = new ArrayList<>(e.refs().size());
			for (Ref r : e.refs()) {
				refs.add(new Ref(r.start() - e.start(), r.end() - e.start(), r.index(), r.kind()));
			}
			kept.add(applyRefs(raw, refs, funcRemap, typeRemap));
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeU(body, kept.size());
		for (byte[] entry : kept) {
			writeRaw(body, entry);
		}
		return body.toByteArray();
	}

	// --- Import section ---

	private record ImportEntry(int kind, byte[] raw, List<Ref> refs) {
	}

	private static List<ImportEntry> parseImports(byte[] payload) {
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

	private static byte[] rebuildImports(List<ImportEntry> imports, boolean[] reachable, int[] funcRemap,
			int[] typeRemap) {
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
			writeRaw(body, applyRefs(e.raw, e.refs, funcRemap, typeRemap));
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

	private static byte[] rebuildFunctionSection(int[] defTypeIdx, int numImportedFuncs, boolean[] reachable,
			int[] typeRemap) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<Integer> kept = new ArrayList<>();
		for (int i = 0; i < defTypeIdx.length; i++) {
			if (reachable[numImportedFuncs + i]) {
				kept.add(typeRemap[defTypeIdx[i]]);
			}
		}
		writeU(body, kept.size());
		for (int t : kept) {
			writeU(body, t);
		}
		return body.toByteArray();
	}

	// --- Global / tag sections ---

	// globalsec := vec(globaltype expr); globaltype := valtype mut
	private static List<Ref> scanGlobalSection(byte[] payload) {
		return scanGlobalSection(payload, null);
	}

	private static List<Ref> scanGlobalSection(byte[] payload, @Nullable IntList i32Constants) {
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
	private static List<Ref> scanTagSection(byte[] payload) {
		List<Ref> refs = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			int attribute = payload[p[0]++] & 0xff;
			if (attribute != 0x00) {
				throw new IllegalStateException("WasmTreeShaker: unhandled tag attribute " + attribute);
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

	// --- Code section ---

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

	private static byte[] rebuildCodeSection(List<byte[]> codeEntries, List<List<Ref>> bodyRefs, int numImportedFuncs,
			boolean[] reachable, int[] funcRemap, int[] typeRemap) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		List<byte[]> kept = new ArrayList<>();
		for (int i = 0; i < codeEntries.size(); i++) {
			if (reachable[numImportedFuncs + i]) {
				kept.add(applyRefs(codeEntries.get(i), bodyRefs.get(i), funcRemap, typeRemap));
			}
		}
		writeU(body, kept.size());
		for (byte[] entry : kept) {
			writeU(body, entry.length);
			writeRaw(body, entry);
		}
		return body.toByteArray();
	}

	// --- Data section ---

	/**
	 * One active data segment: its linear-memory address, its bytes, and its raw span.
	 */
	private record DataSegment(int offset, byte[] bytes, byte[] raw) {
	}

	// Only active mode-0 segments (flags 0: memory 0, i32.const offset expression) are
	// understood; anything else throws rather than risk mis-framing a segment.
	private static List<DataSegment> parseDataSection(byte[] payload) {
		int[] p = { 0 };
		int count = readU(payload, p);
		List<DataSegment> segments = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int start = p[0];
			int flags = readU(payload, p);
			if (flags != 0) {
				throw new IllegalStateException("WasmTreeShaker: unhandled data segment flags " + flags);
			}
			int op = payload[p[0]++] & 0xff;
			if (op != 0x41) { // i32.const
				throw new IllegalStateException(
						String.format("WasmTreeShaker: unhandled data offset opcode 0x%02X", op));
			}
			int offset = readS(payload, p);
			int end = payload[p[0]++] & 0xff;
			if (end != 0x0B) {
				throw new IllegalStateException(
						String.format("WasmTreeShaker: unterminated data offset expression 0x%02X", end));
			}
			int len = readU(payload, p);
			byte[] bytes = slice(payload, p[0], p[0] + len);
			p[0] += len;
			segments.add(new DataSegment(offset, bytes, slice(payload, start, p[0])));
		}
		return segments;
	}

	// The droppable ranges no surviving function body (nor a global initializer) still
	// addresses. A range survives when some live i32.const lands anywhere in
	// [address, address + length] -- the closed interval, so an interior pointer or a
	// one-past-the-end pointer keeps its range too.
	private static List<DroppableDataRange> deadRanges(List<DroppableDataRange> candidates, List<DataSegment> segments,
			List<Integer> deadSegments, boolean[] reachable, int numImportedFuncs,
			List<@Nullable IntList> bodyConstants, IntList globalConstants) {
		if (candidates.isEmpty()) {
			return List.of();
		}
		IntList live = new IntList();
		live.addAll(globalConstants);
		for (int i = 0; i < bodyConstants.size(); i++) {
			@Nullable IntList constants = bodyConstants.get(i);
			if (constants != null && reachable[numImportedFuncs + i]) {
				live.addAll(constants);
			}
		}
		int[] sorted = live.toSortedArray();
		List<DroppableDataRange> dead = new ArrayList<>();
		for (DroppableDataRange r : candidates) {
			if (r.end() <= r.start() || deadSegments.contains(r.segmentIndex())) {
				continue;
			}
			int address = segments.get(r.segmentIndex()).offset() + r.start();
			if (!containsInRange(sorted, address, address + (r.end() - r.start()))) {
				dead.add(r);
			}
		}
		return dead;
	}

	// Whether the sorted array holds a value in [lo, hi] (both inclusive).
	private static boolean containsInRange(int[] sorted, int lo, int hi) {
		int at = java.util.Arrays.binarySearch(sorted, lo);
		int from = at >= 0 ? at : -at - 1;
		return from < sorted.length && sorted[from] <= hi;
	}

	private static IntList globalConstants(@Nullable Section globalSec) {
		IntList constants = new IntList();
		if (globalSec != null) {
			scanGlobalSection(globalSec.payload(), constants);
		}
		return constants;
	}

	// Rebuilds the data section without the segments listed in deadSegments and without
	// the byte ranges listed in deadRanges (a segment carrying one keeps its surviving
	// runs, each re-emitted as its own active segment at the address it already had).
	private static byte[] rebuildDataSection(List<DataSegment> segments, List<Integer> deadSegments,
			List<DroppableDataRange> deadRanges) {
		List<byte[]> kept = new ArrayList<>();
		for (int i = 0; i < segments.size(); i++) {
			DataSegment segment = segments.get(i);
			if (deadSegments.contains(i)) {
				continue;
			}
			List<DroppableDataRange> cuts = new ArrayList<>();
			for (DroppableDataRange r : deadRanges) {
				if (r.segmentIndex() == i) {
					cuts.add(r);
				}
			}
			if (cuts.isEmpty()) {
				kept.add(segment.raw());
				continue;
			}
			cuts.sort((a, b) -> Integer.compare(a.start(), b.start()));
			int len = segment.bytes().length;
			int cursor = 0;
			for (DroppableDataRange cut : cuts) {
				if (cut.start() > cursor) {
					kept.add(activeSegment(segment.offset() + cursor, slice(segment.bytes(), cursor, cut.start())));
				}
				cursor = Math.max(cursor, cut.end());
			}
			if (cursor < len) {
				kept.add(activeSegment(segment.offset() + cursor, slice(segment.bytes(), cursor, len)));
			}
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeU(body, kept.size());
		for (byte[] segment : kept) {
			writeRaw(body, segment);
		}
		return body.toByteArray();
	}

	// One active mode-0 data segment: flags 0, an i32.const offset expression, then the
	// bytes.
	private static byte[] activeSegment(int offset, byte[] bytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeU(out, 0); // flags: active, memory 0
		out.write(0x41); // i32.const
		writeS(out, offset);
		out.write(0x0B); // end
		writeU(out, bytes.length);
		writeRaw(out, bytes);
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
								String.format("WasmTreeShaker: unhandled catch clause kind 0x%02X", kind));
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
							String.format("WasmTreeShaker: unhandled misc opcode 0xFC 0x%02X", sub));
				}
			}
			case 0xFD -> skipSimd(buf, p); // fixed-width SIMD prefix
			default -> throw new IllegalStateException(String.format("WasmTreeShaker: unhandled opcode 0x%02X", op));
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
				throw new IllegalStateException(String.format("WasmTreeShaker: unhandled GC opcode 0xFB 0x%02X", sub));
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
				throw new IllegalStateException(String.format("WasmTreeShaker: unhandled SIMD opcode 0xFD 0x%X", sub));
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
		// otherwise a single-byte value type (0x7B-0x7F numeric/vector, 0x6F/0x70 ref
		// shorthand)
	}

	private static boolean isValTypeStart(int b) {
		// 0x69 = exnref (exception-handling proposal).
		return (b >= 0x7B && b <= 0x7F) || b == 0x70 || b == 0x6F || b == 0x69 || b == 0x63 || b == 0x64;
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

	// --- LEB128 + byte helpers ---

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
