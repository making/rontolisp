package am.ik.wasm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.WasmCodeModel.ArrayType;
import am.ik.wasm.WasmCodeModel.BlockType;
import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.FieldType;
import am.ik.wasm.WasmCodeModel.FuncType;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.StructType;
import am.ik.wasm.WasmCodeModel.TypeDef;
import am.ik.wasm.WasmCodeModel.TypeSection;
import am.ik.wasm.WasmCodeModel.ValType;
import am.ik.wasm.WasmSections.ImportEntry;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent folding of the runtime type tests a closed wasm-GC module can
 * answer statically. Every {@code ref.test}, {@code ref.cast} and {@code ref.is_null}
 * asks a question about which heap types a value can have, and in a module that nothing
 * outside can hand a reference to, the answer is decided by the module's own
 * constructors: a value can only be a {@code struct.new}/{@code array.new} site's type,
 * an {@code i31}, or null. This pass infers, for every reference-typed local, parameter,
 * result, struct field, array element, global and exception payload, the set of heap
 * types that can reach it -- an optimistic least fixpoint over the whole module, in which
 * a branch counts only once its guard can be true and a constructor only once its branch
 * is live -- folds each decided test to the constant it always yields, prunes the arm an
 * {@code if} or {@code br_if} can then never take, and turns a cast that can only fail
 * into {@code unreachable}. {@link WasmTreeShaker} then drops what no live path
 * references any more: the generic arithmetic's float and rational arms in an
 * integer-only program, the printer's arms for types the program never builds, the limb
 * tier behind a boundary that never sees one.
 * <p>
 * <strong>The licence is that the module is closed.</strong> A wasm-GC struct or array is
 * opaque to a host, so the only way a value of a defined type exists is a constructor
 * inside this module -- PROVIDED no reference-typed value crosses the boundary in either
 * direction. The pass therefore declines (returns its input untouched) whenever a
 * function import or export, or a global import or export, mentions a reference type, and
 * refuses ({@code IllegalStateException}) a table, an element segment or an instruction
 * that would let a reference in by another door ({@code call_indirect}, {@code ref.func},
 * {@code any.convert_extern}). The rontolisp backends satisfy the licence by
 * construction: every boundary value is a scalar or a linear-memory span.
 * <p>
 * What makes the fold sound rather than lucky:
 * <ul>
 * <li>a value's set is an over-approximation at every point: joins only grow, the
 * iteration ends only when nothing grew, and the rewrite re-derives every decision from
 * the final sets;</li>
 * <li>a test is folded only where the set decides it -- entirely inside the tested types,
 * or entirely outside them -- and a folded expression's bytes are deleted only when they
 * can neither trap nor have a side effect (a {@code local.get} and the test itself);
 * otherwise the expression stays and its result is dropped;</li>
 * <li>a guard that names a LOCAL refines that local inside the arm the guard selects (and
 * after an arm that never falls through), only across a range the local is never assigned
 * in, so {@code x is i31 | x is bignum | x is bigint} decides as one question about
 * {@code x};</li>
 * <li>two type indices count as the same type exactly when they are canonically equal
 * (structurally, with their {@code rec} groups), so a test on one is a test on both.</li>
 * </ul>
 * The pass never renumbers a function or a type: it rewrites function bodies in place and
 * leaves every other section verbatim, so it composes with any claim a caller makes to
 * the shaker in pre-shake indices.
 */
public final class WasmRefTypeFolder {

	private WasmRefTypeFolder() {
	}

	/**
	 * Folds the statically decided type tests of a core wasm-GC module and prunes the
	 * code they make dead.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with its function bodies rewritten; the input itself when
	 * nothing could be decided or the module is not closed (see the class note)
	 */
	public static byte[] fold(byte[] module) {
		@Nullable Model model = Model.parse(module);
		if (model == null) {
			return module;
		}
		return model.run();
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

	private static final int SEC_TAG = 13;

	// The import/export descriptor kind of a tag (exception-handling proposal).
	private static final int KIND_TAG = 0x04;

	// Frame kinds.
	private static final int KIND_FUNC = 0;

	private static final int KIND_BLOCK = 1;

	private static final int KIND_LOOP = 2;

	private static final int KIND_IF = 3;

	private static final int KIND_TRY = 4;

	// How a constant-conditioned `if` was resolved.
	private static final int FOLD_NONE = 0;

	private static final int FOLD_THEN = 1;

	private static final int FOLD_ELSE = 2;

	/** A symbolic i32: the value is a known constant, or the truth of a type test. */
	private sealed interface Sym permits Const, Bool {

	}

	private record Const(int value) implements Sym {
	}

	/**
	 * "Local {@code local}, as read at instruction {@code getPos}, is (or with
	 * {@code neg}, is not) one of {@code member}" -- kept symbolic while the local's set
	 * cannot decide it, so several tests of the same local combine into one question.
	 */
	private record Bool(int local, BitSet member, boolean neg, int getPos) implements Sym {
	}

	/**
	 * An abstract stack entry: a reference with the set of heap types it can be (plus the
	 * null / i31 / other pseudo-members), or a scalar with an optional symbolic i32
	 * value. {@code local}/{@code getPos} name the local the value was read from, when it
	 * was; {@code spanStart} is the index of the first instruction of the contiguous
	 * range that produced it.
	 */
	private record Val(boolean ref, @Nullable BitSet set, @Nullable Sym sym, int local, int getPos, int spanStart) {

		static Val scalar(int spanStart) {
			return new Val(false, null, null, -1, -1, spanStart);
		}

		static Val i32(@Nullable Sym sym, int spanStart) {
			return new Val(false, null, sym, -1, -1, spanStart);
		}

		static Val ref(BitSet set, int spanStart) {
			return new Val(true, set, null, -1, -1, spanStart);
		}

		BitSet refSet() {
			if (this.set == null) {
				throw new IllegalStateException("WasmRefTypeFolder: a scalar where a reference was expected");
			}
			return this.set;
		}

	}

	/**
	 * A local refined by a guard over an instruction range the local is not assigned in.
	 */
	private record Refinement(int local, BitSet member, boolean inSet, int from, int to) {
	}

	/** One control frame of the structured walk. */
	private static final class Frame {

		final int kind;

		final int opener;

		final int endIndex;

		final int elseIndex;

		final List<ValType> params;

		final List<ValType> results;

		final int stackBase;

		final List<Val> paramVals;

		/**
		 * The joined sets of the values delivered to this label: per result for a
		 * block/if/try/function, per parameter for a loop; null for a scalar.
		 */
		final @Nullable BitSet[] carried;

		/** Definite-assignment state at every arrival (branch-in or fallthrough). */
		final List<BitSet> arrivals = new ArrayList<>();

		boolean unreachable;

		boolean inElse;

		@Nullable BitSet entryAssigned;

		boolean thenFellThrough;

		/**
		 * Whether any branch (or catch) delivered to this label, as opposed to falling
		 * through.
		 */
		boolean branchedTo;

		/**
		 * A folded {@code if} no branch targets needs no label of its own: its
		 * {@code block}/{@code end} are not emitted, and every branch crossing it counts
		 * one label fewer.
		 */
		boolean elided;

		int folded = FOLD_NONE;

		@Nullable Bool cond;

		int refinementMark;

		/**
		 * Whether the carried sets outlive this walk (a function's return sets, a loop's
		 * parameter sets): only growth THERE means the fixpoint has not been reached; a
		 * block's result sets are rebuilt by every walk.
		 */
		final boolean persistent;

		Frame(int kind, int opener, int endIndex, int elseIndex, List<ValType> params, List<ValType> results,
				int stackBase, List<Val> paramVals, @Nullable BitSet[] carried) {
			this.kind = kind;
			this.opener = opener;
			this.endIndex = endIndex;
			this.elseIndex = elseIndex;
			this.params = params;
			this.results = results;
			this.stackBase = stackBase;
			this.paramVals = paramVals;
			this.carried = carried;
			this.persistent = kind == KIND_FUNC || kind == KIND_LOOP;
		}

		int arity() {
			return this.kind == KIND_LOOP ? this.params.size() : this.results.size();
		}

	}

	/**
	 * A growable byte buffer the rewrite emits into; supports truncation and deletion.
	 */
	private static final class Out {

		byte[] buf = new byte[256];

		int size;

		void write(int b) {
			ensure(1);
			this.buf[this.size++] = (byte) b;
		}

		void write(byte[] src, int from, int to) {
			ensure(to - from);
			System.arraycopy(src, from, this.buf, this.size, to - from);
			this.size += to - from;
		}

		void writeU(int value) {
			int v = value;
			do {
				int b = v & 0x7f;
				v >>>= 7;
				if (v != 0) {
					b |= 0x80;
				}
				write(b);
			}
			while (v != 0);
		}

		void writeS(int value) {
			int v = value;
			while (true) {
				int b = v & 0x7f;
				v >>= 7;
				if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
					write(b);
					return;
				}
				write(b | 0x80);
			}
		}

		void truncate(int newSize) {
			this.size = newSize;
		}

		void delete(int from, int to) {
			System.arraycopy(this.buf, to, this.buf, from, this.size - to);
			this.size -= to - from;
		}

		private void ensure(int n) {
			if (this.size + n > this.buf.length) {
				this.buf = Arrays.copyOf(this.buf, Math.max(this.buf.length * 2, this.size + n));
			}
		}

		byte[] toByteArray() {
			return Arrays.copyOf(this.buf, this.size);
		}

	}

	/** The parsed module and the analysis state over it. */
	private static final class Model {

		final byte[] module;

		final List<Section> sections;

		final TypeSection types;

		final int numTypes;

		// Pseudo-members after the concrete type indices.
		final int nullBit;

		final int i31Bit;

		final int otherBit;

		final BitSet nullSet;

		final BitSet i31Set;

		final BitSet otherSet;

		final BitSet structSet = new BitSet();

		final BitSet arraySet = new BitSet();

		final BitSet eqSet = new BitSet();

		final BitSet[] typeClassSet;

		final int numImports;

		final int numFuncs;

		final FuncType[] funcTypes;

		final byte[][] codeEntries;

		// Per defined function, decoded on first use (materialize) together with the
		// per-body tables below: the analysis only ever reaches the functions it proves
		// live, and a backend module is mostly runtime the program never calls.
		final @Nullable Body[] bodies;

		final ValType[] globalTypes;

		final List<List<Instr>> globalInits;

		final byte[] globalPayload;

		final List<FuncType> tagTypes;

		final boolean[] live;

		// Persistent sets (only ever grow).
		final @Nullable BitSet[][] localSets; // per defined function, per local (params
												// first)

		final @Nullable BitSet[][] returnSets; // per function

		final @Nullable BitSet[][] fieldSets; // per type: per field, or [0] = element

		final @Nullable BitSet[] globalSets;

		final @Nullable BitSet[][] tagSets;

		final Map<Long, @Nullable BitSet[]> loopParamSets = new HashMap<>();

		final int[][][] assignPositions; // per defined function, per local: sorted

		final int[][] impurePrefix; // per defined function

		// Per defined function, per instruction: whether the if opened there was the
		// target of a branch in the latest walk -- what the rewrite reads to decide
		// whether a folded if can drop its block wrapper.
		final boolean[][] targeted;

		boolean changed;

		// The worklist (.kb/wasm-ref-type-fold.md, "The analysis"): a walk reads its own
		// local, parameter and loop sets, its callees' return sets, and the field, global
		// and tag sets of the instructions it walks. A function is walked again only when
		// one of those grew since its last walk: each such set knows the functions that
		// read it (callers for a return set, readers for the rest) and marks them dirty.
		// The global initializers read only other globals, so any global's growth
		// re-walks them all (globalInitEpoch).
		final boolean[] dirty;

		final @Nullable BitSet[] callers;

		final @Nullable BitSet[] fieldReaders;

		final @Nullable BitSet[] globalReaders;

		final @Nullable BitSet[] tagReaders;

		int globalInitEpoch = 1;

		final int[] lastGlobalInitEpoch;

		private Model(byte[] module, List<Section> sections, TypeSection types, List<ImportEntry> imports,
				int[] defTypeIdx, List<byte[]> codeEntries, @Nullable Section globalSec, @Nullable Section tagSec) {
			this.module = module;
			this.sections = sections;
			this.types = types;
			this.numTypes = types.count();
			this.nullBit = this.numTypes;
			this.i31Bit = this.numTypes + 1;
			this.otherBit = this.numTypes + 2;
			this.nullSet = bit(this.nullBit);
			this.i31Set = bit(this.i31Bit);
			this.otherSet = bit(this.otherBit);
			for (int t = 0; t < this.numTypes; t++) {
				TypeDef d = types.types().get(t);
				if (d instanceof StructType) {
					this.structSet.set(t);
				}
				else if (d instanceof ArrayType) {
					this.arraySet.set(t);
				}
			}
			this.eqSet.or(this.structSet);
			this.eqSet.or(this.arraySet);
			this.eqSet.set(this.i31Bit);
			this.typeClassSet = canonicalClasses(types);

			// Functions: imports first, then the defined ones.
			List<FuncType> ft = new ArrayList<>();
			int importedFuncs = 0;
			for (ImportEntry e : imports) {
				if (e.kind() == WasmSections.KIND_FUNC) {
					ft.add(types.func(importTypeIndex(e)));
					importedFuncs++;
				}
			}
			this.numImports = importedFuncs;
			for (int t : defTypeIdx) {
				ft.add(types.func(t));
			}
			this.funcTypes = ft.toArray(new FuncType[0]);
			this.numFuncs = this.funcTypes.length;
			this.codeEntries = codeEntries.toArray(new byte[0][]);
			this.bodies = new Body[codeEntries.size()];

			// Globals: imported ones first (none may be a reference -- checked by parse),
			// then the defined ones with their initializers.
			List<ValType> gt = new ArrayList<>();
			List<List<Instr>> inits = new ArrayList<>();
			for (ImportEntry e : imports) {
				if (e.kind() == WasmSections.KIND_GLOBAL) {
					gt.add(importGlobalType(e));
					inits.add(List.of());
				}
			}
			this.globalPayload = globalSec == null ? new byte[0] : globalSec.payload();
			if (globalSec != null) {
				int[] p = { 0 };
				int count = WasmSections.readU(this.globalPayload, p);
				for (int i = 0; i < count; i++) {
					ValType t = WasmCodeModel.readValType(this.globalPayload, p);
					p[0]++; // mutability
					gt.add(t);
					inits.add(WasmCodeModel.decodeConstExpr(this.globalPayload, p, types));
				}
			}
			this.globalTypes = gt.toArray(new ValType[0]);
			this.globalInits = inits;

			List<FuncType> tags = new ArrayList<>();
			if (tagSec != null) {
				byte[] payload = tagSec.payload();
				int[] p = { 0 };
				int count = WasmSections.readU(payload, p);
				for (int i = 0; i < count; i++) {
					p[0]++; // attribute
					tags.add(types.func(WasmSections.readU(payload, p)));
				}
			}
			this.tagTypes = tags;

			this.live = new boolean[this.numFuncs];
			this.dirty = new boolean[this.numFuncs];
			this.callers = new BitSet[this.numFuncs];
			this.fieldReaders = new BitSet[this.numTypes];
			this.globalReaders = new BitSet[this.globalTypes.length];
			this.tagReaders = new BitSet[this.tagTypes.size()];
			this.lastGlobalInitEpoch = new int[this.globalInits.size()];
			this.localSets = new @Nullable BitSet[this.bodies.length][];
			this.assignPositions = new int[this.bodies.length][][];
			this.impurePrefix = new int[this.bodies.length][];
			this.targeted = new boolean[this.bodies.length][];
			this.returnSets = new @Nullable BitSet[this.numFuncs][];
			for (int f = 0; f < this.numFuncs; f++) {
				this.returnSets[f] = setsFor(this.funcTypes[f].results());
			}
			this.fieldSets = new @Nullable BitSet[this.numTypes][];
			for (int t = 0; t < this.numTypes; t++) {
				TypeDef d = types.types().get(t);
				if (d instanceof StructType s) {
					@Nullable BitSet[] sets = new BitSet[s.fields().size()];
					for (int k = 0; k < sets.length; k++) {
						sets[k] = s.fields().get(k).isRef() ? new BitSet() : null;
					}
					this.fieldSets[t] = sets;
				}
				else if (d instanceof ArrayType a) {
					@Nullable BitSet[] one = new BitSet[1];
					one[0] = a.elem().isRef() ? new BitSet() : null;
					this.fieldSets[t] = one;
				}
				else {
					this.fieldSets[t] = new BitSet[0];
				}
			}
			this.globalSets = new BitSet[this.globalTypes.length];
			for (int g = 0; g < this.globalSets.length; g++) {
				this.globalSets[g] = this.globalTypes[g].isRef() ? new BitSet() : null;
			}
			this.tagSets = new @Nullable BitSet[this.tagTypes.size()][];
			for (int t = 0; t < this.tagSets.length; t++) {
				this.tagSets[t] = setsFor(this.tagTypes.get(t).params());
			}
		}

		private static BitSet bit(int index) {
			BitSet s = new BitSet();
			s.set(index);
			return s;
		}

		private static @Nullable BitSet[] setsFor(List<ValType> types) {
			@Nullable BitSet[] sets = new BitSet[types.size()];
			for (int k = 0; k < sets.length; k++) {
				sets[k] = types.get(k).isRef() ? new BitSet() : null;
			}
			return sets;
		}

		private static int importTypeIndex(ImportEntry e) {
			// module name, field name, kind byte, then the typeidx.
			int[] p = { 0 };
			WasmSections.skipName(e.raw(), p);
			WasmSections.skipName(e.raw(), p);
			p[0]++;
			return WasmSections.readU(e.raw(), p);
		}

		private static ValType importGlobalType(ImportEntry e) {
			int[] p = { 0 };
			WasmSections.skipName(e.raw(), p);
			WasmSections.skipName(e.raw(), p);
			p[0]++;
			return WasmCodeModel.readValType(e.raw(), p);
		}

		/**
		 * Parses the module, or returns null when it is not closed (a reference type on
		 * an import or export boundary).
		 */
		/**
		 * Decodes one defined function and builds its per-body tables, once: its local
		 * sets, where each local is assigned, the impure-instruction prefix sums and the
		 * branch-target flags. Everything that reads a function's body or tables goes
		 * through here first.
		 * @param d the defined-function ordinal
		 * @return the decoded body
		 */
		Body materialize(int d) {
			@Nullable Body decoded = this.bodies[d];
			if (decoded != null) {
				return decoded;
			}
			FuncType f = this.funcTypes[this.numImports + d];
			Body body = WasmCodeModel.decode(this.codeEntries[d], this.types);
			int n = f.params().size() + body.locals().size();
			@Nullable BitSet[] sets = new BitSet[n];
			List<List<Integer>> assigns = new ArrayList<>();
			for (int l = 0; l < n; l++) {
				ValType t = l < f.params().size() ? f.params().get(l) : body.locals().get(l - f.params().size());
				sets[l] = t.isRef() ? new BitSet() : null;
				assigns.add(new ArrayList<>());
			}
			this.localSets[d] = sets;
			int[] prefix = new int[body.code().size() + 1];
			for (int i = 0; i < body.code().size(); i++) {
				Instr in = body.code().get(i);
				if (in.op == 0x21 || in.op == 0x22) {
					assigns.get((int) in.a).add(i);
				}
				prefix[i + 1] = prefix[i] + (isPure(in) ? 0 : 1);
			}
			int[][] positions = new int[n][];
			for (int l = 0; l < n; l++) {
				positions[l] = assigns.get(l).stream().mapToInt(Integer::intValue).toArray();
			}
			this.assignPositions[d] = positions;
			this.impurePrefix[d] = prefix;
			this.targeted[d] = new boolean[body.code().size()];
			this.bodies[d] = body;
			return body;
		}

		static @Nullable Model parse(byte[] module) {
			List<Section> sections = WasmSections.parseSections(module);
			for (Section s : sections) {
				if (s.id() == SEC_TABLE || s.id() == SEC_ELEMENT) {
					throw new IllegalStateException("WasmRefTypeFolder: unhandled section id " + s.id());
				}
			}
			@Nullable Section typeSec = WasmSections.find(sections, SEC_TYPE);
			@Nullable Section importSec = WasmSections.find(sections, SEC_IMPORT);
			@Nullable Section functionSec = WasmSections.find(sections, SEC_FUNCTION);
			@Nullable Section codeSec = WasmSections.find(sections, SEC_CODE);
			@Nullable Section exportSec = WasmSections.find(sections, SEC_EXPORT);
			if (typeSec == null || codeSec == null || functionSec == null) {
				return null;
			}
			TypeSection types = WasmCodeModel.parseTypeSection(typeSec.payload());
			List<ImportEntry> imports = importSec == null ? List.of() : WasmSections.parseImports(importSec.payload());
			int[] defTypeIdx = WasmSections.parseFunctionSection(functionSec.payload());
			List<FuncType> importedFuncs = new ArrayList<>();
			for (ImportEntry e : imports) {
				if (e.kind() == WasmSections.KIND_FUNC) {
					FuncType f = types.func(importTypeIndex(e));
					if (mentionsRef(f)) {
						return null;
					}
					importedFuncs.add(f);
				}
				else if (e.kind() == WasmSections.KIND_GLOBAL && importGlobalType(e).isRef()) {
					return null;
				}
				else if (e.kind() == KIND_TAG) {
					// A host-owned tag can be thrown into the module with any payload.
					return null;
				}
			}
			if (exportSec != null) {
				byte[] payload = exportSec.payload();
				int[] p = { 0 };
				int count = WasmSections.readU(payload, p);
				for (int i = 0; i < count; i++) {
					WasmSections.skipName(payload, p);
					int kind = payload[p[0]++] & 0xff;
					int index = WasmSections.readU(payload, p);
					if (kind == WasmSections.KIND_FUNC) {
						FuncType f = index < importedFuncs.size() ? importedFuncs.get(index)
								: types.func(defTypeIdx[index - importedFuncs.size()]);
						if (mentionsRef(f)) {
							return null;
						}
					}
					else if (kind == WasmSections.KIND_GLOBAL || kind == KIND_TAG) {
						// A host can read and write an exported global, and throw an
						// exception carrying any payload on an exported tag: either is a
						// door into the module's type space.
						return null;
					}
				}
			}
			return new Model(module, sections, types, imports, defTypeIdx,
					WasmSections.parseCodeEntries(codeSec.payload()), WasmSections.find(sections, SEC_GLOBAL),
					WasmSections.find(sections, SEC_TAG));
		}

		private static boolean mentionsRef(FuncType f) {
			for (ValType t : f.params()) {
				if (t.isRef()) {
					return true;
				}
			}
			for (ValType t : f.results()) {
				if (t.isRef()) {
					return true;
				}
			}
			return false;
		}

		// Whether an instruction can be deleted from a folded expression: no side effect,
		// no trap. Allocation is unobservable, so a constructor counts as pure.
		private static boolean isPure(Instr in) {
			if (in.op >= 0x45 && in.op <= 0xC4) {
				return !WasmCodeModel.numericMayTrap(in.op);
			}
			return switch (in.op) {
				case 0x01, 0x1A, 0x1B, 0x20, 0x23, 0x41, 0x42, 0x43, 0x44, 0xD0, 0xD1, 0xD3 -> true;
				case 0xFB -> in.sub == 0x00 || in.sub == 0x01 || in.sub == 0x14 || in.sub == 0x15 || in.sub == 0x1C;
				// The saturating truncations never trap; the bulk-memory pair
				// (memory.copy 0x0A / memory.fill 0x0B) both writes memory and traps
				// out of bounds.
				case 0xFC -> in.sub <= 0x07;
				default -> false;
			};
		}

		byte[] run() {
			// Roots: exported functions and the start function.
			@Nullable Section exportSec = WasmSections.find(this.sections, SEC_EXPORT);
			if (exportSec != null) {
				byte[] payload = exportSec.payload();
				int[] p = { 0 };
				int count = WasmSections.readU(payload, p);
				for (int i = 0; i < count; i++) {
					WasmSections.skipName(payload, p);
					int kind = payload[p[0]++] & 0xff;
					int index = WasmSections.readU(payload, p);
					if (kind == WasmSections.KIND_FUNC) {
						this.live[index] = true;
					}
				}
			}
			@Nullable Section startSec = WasmSections.find(this.sections, SEC_START);
			if (startSec != null) {
				int[] p = { 0 };
				this.live[WasmSections.readU(startSec.payload(), p)] = true;
			}
			for (int f = this.numImports; f < this.numFuncs; f++) {
				this.dirty[f] = this.live[f];
			}
			byte @Nullable [] @Nullable [] rewritten;
			while (true) {
				do {
					this.changed = false;
					for (int g = 0; g < this.globalInits.size(); g++) {
						if (!this.globalInits.get(g).isEmpty() && this.lastGlobalInitEpoch[g] != this.globalInitEpoch) {
							this.lastGlobalInitEpoch[g] = this.globalInitEpoch;
							new Walk(this, -1, g, false).run();
						}
					}
					for (int f = this.numImports; f < this.numFuncs; f++) {
						if (this.live[f] && this.dirty[f]) {
							this.dirty[f] = false;
							new Walk(this, f, -1, false).run();
						}
					}
				}
				while (this.changed);
				// The rewrite is the same walk over the final sets; a set that still
				// grows
				// here means the fixpoint was not one, and the loop simply continues.
				rewritten = new byte @Nullable [this.bodies.length][];
				this.changed = false;
				for (int f = this.numImports; f < this.numFuncs; f++) {
					if (this.live[f]) {
						rewritten[f - this.numImports] = new Walk(this, f, -1, true).run();
					}
				}
				if (!this.changed) {
					break;
				}
			}
			boolean any = false;
			for (int d = 0; d < this.bodies.length; d++) {
				if (rewritten[d] != null && !Arrays.equals(rewritten[d], this.codeEntries[d])) {
					any = true;
					break;
				}
			}
			if (!any) {
				return this.module;
			}
			java.io.ByteArrayOutputStream body = new UnsynchronizedByteArrayOutputStream();
			WasmSections.writeU(body, this.bodies.length);
			for (int d = 0; d < this.bodies.length; d++) {
				byte[] entry = rewritten[d] != null ? rewritten[d] : this.codeEntries[d];
				WasmSections.writeU(body, entry.length);
				WasmSections.writeRaw(body, entry);
			}
			List<Section> rebuilt = new ArrayList<>(this.sections.size());
			for (Section s : this.sections) {
				rebuilt.add(s.id() == SEC_CODE ? new Section(SEC_CODE, body.toByteArray()) : s);
			}
			return WasmSections.assemble(rebuilt);
		}

		// A set only this function's walk reads grew: walk it again.
		void joinOwn(int f, @Nullable BitSet target, @Nullable BitSet src) {
			if (join(target, src) && f >= 0) {
				this.dirty[f] = true;
			}
		}

		// A field (or element) set of type t grew: walk its readers again.
		void joinField(int t, @Nullable BitSet target, @Nullable BitSet src) {
			if (join(target, src)) {
				markAll(this.fieldReaders[t]);
			}
		}

		// Global g's set grew: walk its readers and the global initializers again.
		void joinGlobal(int g, @Nullable BitSet target, @Nullable BitSet src) {
			if (join(target, src)) {
				markAll(this.globalReaders[g]);
				this.globalInitEpoch++;
			}
		}

		// A payload set of tag x grew: walk the functions that catch it again.
		void joinTag(int x, @Nullable BitSet target, @Nullable BitSet src) {
			if (join(target, src)) {
				markAll(this.tagReaders[x]);
			}
		}

		private void markAll(@Nullable BitSet functions) {
			if (functions != null) {
				for (int g = functions.nextSetBit(0); g >= 0; g = functions.nextSetBit(g + 1)) {
					this.dirty[g] = true;
				}
			}
		}

		// A walk of function f read entry `index` of a reader table.
		static void reads(@Nullable BitSet[] readers, int index, int f) {
			if (f < 0) {
				return;
			}
			@Nullable BitSet fs = readers[index];
			if (fs == null) {
				fs = new BitSet();
				readers[index] = fs;
			}
			fs.set(f);
		}

		// Function f's return set grew: walk every function that called it again.
		void joinReturn(int f, @Nullable BitSet target, @Nullable BitSet src) {
			if (join(target, src)) {
				@Nullable BitSet fs = this.callers[f];
				if (fs != null) {
					for (int g = fs.nextSetBit(0); g >= 0; g = fs.nextSetBit(g + 1)) {
						this.dirty[g] = true;
					}
				}
			}
		}

		// A walk of `caller` read callee's return sets.
		void calls(int caller, int callee) {
			if (caller < 0) {
				return;
			}
			@Nullable BitSet fs = this.callers[callee];
			if (fs == null) {
				fs = new BitSet();
				this.callers[callee] = fs;
			}
			fs.set(caller);
		}

		boolean join(@Nullable BitSet target, @Nullable BitSet src) {
			if (target == null || src == null) {
				return false;
			}
			int before = target.cardinality();
			target.or(src);
			if (target.cardinality() != before) {
				this.changed = true;
				return true;
			}
			return false;
		}

		/**
		 * The set a heap type denotes, or null for one this pass does not reason about.
		 */
		@Nullable BitSet heapTypeSet(long heap) {
			if (heap >= 0) {
				return this.typeClassSet[(int) heap];
			}
			if (heap == WasmCodeModel.HEAP_I31) {
				return this.i31Set;
			}
			if (heap == WasmCodeModel.HEAP_ARRAY) {
				return this.arraySet;
			}
			if (heap == WasmCodeModel.HEAP_STRUCT) {
				return this.structSet;
			}
			if (heap == WasmCodeModel.HEAP_EQ || heap == WasmCodeModel.HEAP_ANY) {
				return this.eqSet;
			}
			if (heap == WasmCodeModel.HEAP_NONE) {
				return new BitSet();
			}
			return null;
		}

		/**
		 * Canonical identity of every type index under wasm-GC: the coarsest partition of
		 * the {@code rec} groups in which two groups are equal exactly when they have the
		 * same shape and every external reference points into equal groups at the same
		 * position -- computed by refinement from "all equal". A {@code ref.test} on an
		 * index answers for its whole class.
		 */
		private static BitSet[] canonicalClasses(TypeSection types) {
			int groups = types.groupStart().length;
			int[] classOf = new int[groups];
			int classes = 1;
			while (true) {
				Map<String, Integer> ids = new LinkedHashMap<>();
				int[] next = new int[groups];
				for (int g = 0; g < groups; g++) {
					String key = classOf[g] + "|" + groupKey(types, g, classOf);
					next[g] = ids.computeIfAbsent(key, k -> ids.size());
				}
				classOf = next;
				if (ids.size() == classes) {
					break;
				}
				classes = ids.size();
			}
			BitSet[] sets = new BitSet[types.count()];
			for (int t = 0; t < sets.length; t++) {
				BitSet s = new BitSet();
				int g = types.groupOf()[t];
				int rel = t - types.groupStart()[g];
				for (int u = 0; u < sets.length; u++) {
					int gu = types.groupOf()[u];
					if (classOf[gu] == classOf[g] && u - types.groupStart()[gu] == rel) {
						s.set(u);
					}
				}
				sets[t] = s;
			}
			return sets;
		}

		private static String groupKey(TypeSection types, int g, int[] classOf) {
			StringBuilder sb = new StringBuilder();
			int start = types.groupStart()[g];
			int size = types.groupSize()[g];
			sb.append(size).append('{');
			for (int t = start; t < start + size; t++) {
				TypeDef d = types.types().get(t);
				switch (d) {
					case FuncType f -> {
						sb.append("F(");
						for (ValType v : f.params()) {
							renderType(sb, v, types, g, classOf);
						}
						sb.append(")(");
						for (ValType v : f.results()) {
							renderType(sb, v, types, g, classOf);
						}
						sb.append(')');
					}
					case StructType s -> {
						sb.append("S(");
						for (FieldType f : s.fields()) {
							renderType(sb, f.storage(), types, g, classOf);
							sb.append(f.mutable() ? 'm' : 'c');
						}
						sb.append(')');
					}
					case ArrayType a -> {
						sb.append("A(");
						renderType(sb, a.elem().storage(), types, g, classOf);
						sb.append(a.elem().mutable() ? 'm' : 'c');
						sb.append(')');
					}
				}
				sb.append(';');
			}
			sb.append('}');
			return sb.toString();
		}

		private static void renderType(StringBuilder sb, ValType v, TypeSection types, int g, int[] classOf) {
			sb.append(Integer.toHexString(v.code()));
			if (v.isRef()) {
				sb.append(v.nullable() ? 'n' : 'x');
				if (v.heap() < 0) {
					sb.append("h").append(v.heap());
				}
				else {
					int target = v.heap();
					int tg = types.groupOf()[target];
					int rel = target - types.groupStart()[tg];
					if (tg == g) {
						sb.append("r").append(rel);
					}
					else {
						sb.append("c").append(classOf[tg]).append('.').append(rel);
					}
				}
			}
			sb.append(',');
		}

	}

	/** One structured walk over a function body (or a global initializer). */
	private static final class Walk {

		final Model m;

		final int f; // function index, or -1 for a global initializer

		final int def; // defined-function ordinal, or -1

		final int global; // the global whose initializer this walks, or -1

		final List<Instr> code;

		final byte[] entry;

		final ValType[] localTypes;

		final @Nullable BitSet[] localSets;

		final int[][] assignPositions;

		final int[] impurePrefix;

		final boolean[] targeted;

		final @Nullable Out out;

		final int[] emitPos;

		final List<Val> stack = new ArrayList<>();

		final List<Frame> frames = new ArrayList<>();

		final List<Refinement> refinements = new ArrayList<>();

		BitSet assigned = new BitSet();

		Walk(Model m, int f, int global, boolean emitting) {
			this.m = m;
			this.f = f;
			this.global = global;
			if (f >= 0) {
				this.def = f - m.numImports;
				Body body = m.materialize(this.def);
				this.code = body.code();
				this.entry = m.codeEntries[this.def];
				FuncType ft = m.funcTypes[f];
				this.localTypes = new ValType[ft.params().size() + body.locals().size()];
				for (int i = 0; i < ft.params().size(); i++) {
					this.localTypes[i] = ft.params().get(i);
				}
				for (int i = 0; i < body.locals().size(); i++) {
					this.localTypes[ft.params().size() + i] = body.locals().get(i);
				}
				this.localSets = m.localSets[this.def];
				this.assignPositions = m.assignPositions[this.def];
				this.impurePrefix = m.impurePrefix[this.def];
				this.targeted = m.targeted[this.def];
			}
			else {
				this.def = -1;
				this.code = m.globalInits.get(global);
				this.entry = m.globalPayload;
				this.localTypes = new ValType[0];
				this.localSets = new BitSet[0];
				this.assignPositions = new int[0][];
				this.impurePrefix = new int[this.code.size() + 1];
				this.targeted = new boolean[this.code.size()];
			}
			this.out = emitting ? new Out() : null;
			this.emitPos = new int[this.code.size() + 1];
			// The root frame: the function's results, joined into its return sets -- or
			// a global's one value, joined into the global's set.
			List<ValType> results;
			@Nullable BitSet[] carried;
			if (f >= 0) {
				results = m.funcTypes[f].results();
				carried = m.returnSets[f];
				this.assigned.set(0, m.funcTypes[f].params().size());
			}
			else {
				results = List.of(m.globalTypes[global]);
				carried = new BitSet[] { m.globalSets[global] };
			}
			this.frames
				.add(new Frame(KIND_FUNC, -1, this.code.size() - 1, -1, List.of(), results, 0, List.of(), carried));
		}

		byte @Nullable [] run() {
			if (this.out != null) {
				// The locals declaration is copied verbatim; only the instruction stream
				// is rewritten.
				this.out.write(this.entry, 0, this.code.get(0).start);
			}
			int i = 0;
			while (i < this.code.size()) {
				if (this.out != null) {
					this.emitPos[i] = this.out.size;
				}
				i = step(i, this.code.get(i));
			}
			return this.out == null ? null : this.out.toByteArray();
		}

		// --- stack helpers ---

		private Frame top() {
			return this.frames.get(this.frames.size() - 1);
		}

		private Val pop() {
			if (this.stack.size() <= top().stackBase) {
				throw new IllegalStateException("WasmRefTypeFolder: operand stack underflow at " + this.f);
			}
			return this.stack.remove(this.stack.size() - 1);
		}

		private List<Val> popN(int n) {
			List<Val> vals = new ArrayList<>(n);
			for (int k = 0; k < n; k++) {
				vals.add(pop());
			}
			java.util.Collections.reverse(vals);
			return vals;
		}

		private List<Val> peekN(int n) {
			int size = this.stack.size();
			if (size - n < top().stackBase) {
				throw new IllegalStateException("WasmRefTypeFolder: operand stack underflow at " + this.f);
			}
			return new ArrayList<>(this.stack.subList(size - n, size));
		}

		private void push(Val v) {
			this.stack.add(v);
		}

		private void pushAll(List<Val> vals) {
			this.stack.addAll(vals);
		}

		private void truncateStack(int height) {
			while (this.stack.size() > height) {
				this.stack.remove(this.stack.size() - 1);
			}
		}

		// The values a label receives, as fresh stack entries built from its carried
		// sets.
		private List<Val> valuesOf(List<ValType> types, @Nullable BitSet[] carried, int spanStart) {
			List<Val> vals = new ArrayList<>(types.size());
			for (int k = 0; k < types.size(); k++) {
				@Nullable BitSet set = carried[k];
				vals.add(types.get(k).isRef() && set != null ? Val.ref((BitSet) set.clone(), spanStart)
						: Val.scalar(spanStart));
			}
			return vals;
		}

		// --- emission helpers ---

		private void emit(Instr in) {
			if (this.out != null) {
				this.out.write(this.entry, in.start, in.end);
			}
		}

		private boolean pureRange(int from, int to) {
			return this.impurePrefix[to] - this.impurePrefix[from] == 0;
		}

		// Replaces the expression that produced `v` (ending just before instruction i) by
		// nothing when it is pure, else keeps it and drops its value.
		private void discard(Val v, int i) {
			if (this.out == null) {
				return;
			}
			if (pureRange(v.spanStart(), i)) {
				this.out.truncate(this.emitPos[v.spanStart()]);
			}
			else {
				this.out.write(0x1A); // drop
			}
		}

		// Folds the expression producing `v` (whose consumer is instruction i, not
		// emitted) to the constant c, and pushes the constant.
		private void foldTo(Val v, int i, int c) {
			discard(v, i);
			if (this.out != null) {
				this.out.write(0x41);
				this.out.writeS(c);
			}
			push(Val.i32(new Const(c), v.spanStart()));
		}

		// Folds a binary expression over a and b (consumer i) to the constant c.
		private void foldPairTo(Val a, Val b, Instr in, int i, int c) {
			if (this.out != null) {
				if (pureRange(a.spanStart(), i)) {
					this.out.truncate(this.emitPos[a.spanStart()]);
				}
				else {
					emit(in); // compute the value, then throw it away
					this.out.write(0x1A);
				}
				this.out.write(0x41);
				this.out.writeS(c);
			}
			push(Val.i32(new Const(c), a.spanStart()));
		}

		// --- locals ---

		private boolean assignedIn(int local, int from, int to) {
			int[] positions = this.assignPositions[local];
			int at = Arrays.binarySearch(positions, from + 1);
			int idx = at >= 0 ? at : -at - 1;
			return idx < positions.length && positions[idx] < to;
		}

		private BitSet effectiveSet(int local, int at) {
			@Nullable BitSet base = this.localSets[local];
			if (base == null) {
				throw new IllegalStateException("WasmRefTypeFolder: local " + local + " is not a reference");
			}
			BitSet result = base;
			for (Refinement r : this.refinements) {
				if (r.local() == local && r.from() < at && at < r.to()) {
					if (result == base) {
						result = (BitSet) base.clone();
					}
					if (r.inSet()) {
						result.and(r.member());
					}
					else {
						result.andNot(r.member());
					}
				}
			}
			if (!this.assigned.get(local)) {
				if (result == base) {
					result = (BitSet) base.clone();
				}
				result.or(this.m.nullSet);
			}
			return result;
		}

		// The guard was answered about the local as READ at cond.getPos(): it says
		// nothing
		// about a value assigned since, so the local must be unassigned from that read to
		// the end of the range the refinement covers.
		private void addRefinement(Bool cond, boolean inSet, int from, int to) {
			if (to > from + 1 && !assignedIn(cond.local(), cond.getPos(), to)) {
				this.refinements.add(new Refinement(cond.local(), cond.member(), inSet, from, to));
			}
		}

		private void dropRefinementsFrom(int mark) {
			while (this.refinements.size() > mark) {
				this.refinements.remove(this.refinements.size() - 1);
			}
		}

		// The end of the region a refinement made inside `fr` at instruction i may cover.
		private int regionEnd(Frame fr, int i) {
			if (fr.kind == KIND_IF && !fr.inElse && fr.elseIndex >= 0 && i < fr.elseIndex) {
				return fr.elseIndex;
			}
			return fr.endIndex;
		}

		// --- deciding tests ---

		private static boolean subset(BitSet a, BitSet b) {
			BitSet rest = (BitSet) a.clone();
			rest.andNot(b);
			return rest.isEmpty();
		}

		// Whether `v` is within `target`: 1, 0, a symbolic question about a local, or
		// null.
		// The caller has already ruled out an EMPTY set (no value ever reaches the test).
		private @Nullable Sym decide(Val v, BitSet target) {
			BitSet set = v.refSet();
			if (!set.intersects(target)) {
				return new Const(0);
			}
			if (subset(set, target)) {
				return new Const(1);
			}
			if (v.local() >= 0) {
				return new Bool(v.local(), target, false, v.getPos());
			}
			return null;
		}

		// Re-decides a symbolic question against the local's set at instruction i -- the
		// set there describes the value the question was asked about only while the local
		// has not been assigned since the read.
		private Sym redecide(Bool b, int i) {
			if (assignedIn(b.local(), b.getPos(), i)) {
				return b;
			}
			BitSet set = effectiveSet(b.local(), i);
			if (set.isEmpty() || !set.intersects(b.member())) {
				return new Const(b.neg() ? 1 : 0);
			}
			if (subset(set, b.member())) {
				return new Const(b.neg() ? 0 : 1);
			}
			return b;
		}

		// --- control ---

		private int unreachable(Frame fr) {
			fr.unreachable = true;
			if (fr.kind == KIND_IF && !fr.inElse && fr.elseIndex >= 0) {
				return fr.elseIndex;
			}
			return fr.endIndex;
		}

		// Delivers the top `arity` values (popped or peeked) to the label `depth` up.
		private void branchTo(int depth, boolean popValues) {
			Frame target = this.frames.get(this.frames.size() - 1 - depth);
			int arity = target.arity();
			List<Val> vals = popValues ? popN(arity) : peekN(arity);
			for (int k = 0; k < arity; k++) {
				if (vals.get(k).ref()) {
					deliver(target, k, vals.get(k).refSet());
				}
			}
			if (target.kind != KIND_LOOP) {
				target.arrivals.add((BitSet) this.assigned.clone());
				target.branchedTo = true;
			}
		}

		private int step(int i, Instr in) {
			Frame fr = top();
			if (in.op >= 0x45 && in.op <= 0xC4) {
				return stepNumeric(i, in);
			}
			switch (in.op) {
				case 0x00 -> { // unreachable
					emit(in);
					return unreachable(fr);
				}
				case 0x01 -> { // nop
					emit(in);
					return i + 1;
				}
				case 0x02 -> { // block
					BlockType bt = blockType(in);
					List<Val> params = popN(bt.params().size());
					Frame nf = new Frame(KIND_BLOCK, i, in.match, -1, bt.params(), bt.results(), this.stack.size(),
							params, Model.setsFor(bt.results()));
					nf.refinementMark = this.refinements.size();
					this.frames.add(nf);
					pushAll(params);
					emit(in);
					return i + 1;
				}
				case 0x03 -> { // loop
					BlockType bt = blockType(in);
					List<Val> params = popN(bt.params().size());
					long key = ((long) this.f << 32) | i;
					@Nullable BitSet[] sets = this.m.loopParamSets.computeIfAbsent(key, k -> Model.setsFor(bt.params()));
					for (int k = 0; k < params.size(); k++) {
						if (params.get(k).ref()) {
							this.m.joinOwn(this.f, sets[k], params.get(k).refSet());
						}
					}
					Frame nf = new Frame(KIND_LOOP, i, in.match, -1, bt.params(), bt.results(), this.stack.size(),
							params, sets);
					nf.refinementMark = this.refinements.size();
					this.frames.add(nf);
					pushAll(valuesOf(bt.params(), sets, i));
					emit(in);
					return i + 1;
				}
				case 0x04 -> {
					return stepIf(i, in);
				}
				case 0x05 -> {
					return stepElse(i, in);
				}
				case 0x0B -> {
					return stepEnd(i, in);
				}
				case 0x08 -> { // throw
					FuncType tag = this.m.tagTypes.get((int) in.a);
					List<Val> args = popN(tag.params().size());
					for (int k = 0; k < args.size(); k++) {
						if (args.get(k).ref()) {
							this.m.joinTag((int) in.a, this.m.tagSets[(int) in.a][k], args.get(k).refSet());
						}
					}
					emit(in);
					return unreachable(fr);
				}
				case 0x0A -> { // throw_ref
					pop();
					emit(in);
					return unreachable(fr);
				}
				case 0x0C -> { // br
					branchTo((int) in.a, true);
					emitBranch(in, 0x0C, (int) in.a);
					return unreachable(fr);
				}
				case 0x0D -> { // br_if
					Val c = pop();
					if (c.sym() instanceof Const k) {
						discard(c, i);
						if (k.value() == 0) {
							return i + 1;
						}
						branchTo((int) in.a, true);
						emitBranch(null, 0x0C, (int) in.a);
						return unreachable(fr);
					}
					branchTo((int) in.a, false);
					emitBranch(in, 0x0D, (int) in.a);
					if (c.sym() instanceof Bool b) {
						// Not taken: the condition was false.
						addRefinement(b, b.neg(), i, regionEnd(fr, i));
					}
					return i + 1;
				}
				case 0x0E -> { // br_table
					pop();
					int[] labels = java.util.Objects.requireNonNull(in.labels);
					for (int label : labels) {
						branchTo(label, false);
					}
					if (this.out != null) {
						boolean adjusted = false;
						int[] emitted = new int[labels.length];
						for (int k = 0; k < labels.length; k++) {
							emitted[k] = adjustedDepth(labels[k], this.frames.size());
							adjusted |= emitted[k] != labels[k];
						}
						if (!adjusted) {
							emit(in);
						}
						else {
							this.out.write(0x0E);
							this.out.writeU(emitted.length - 1);
							for (int label : emitted) {
								this.out.writeU(label);
							}
						}
					}
					return unreachable(fr);
				}
				case 0x0F -> { // return
					branchTo(this.frames.size() - 1, true);
					emit(in);
					return unreachable(fr);
				}
				case 0x10 -> { // call
					int callee = (int) in.a;
					FuncType ft = this.m.funcTypes[callee];
					List<Val> args = popN(ft.params().size());
					if (callee >= this.m.numImports) {
						if (!this.m.live[callee]) {
							this.m.live[callee] = true;
							this.m.dirty[callee] = true;
							this.m.changed = true;
						}
						this.m.materialize(callee - this.m.numImports);
						@Nullable BitSet[] paramSets = this.m.localSets[callee - this.m.numImports];
						for (int k = 0; k < args.size(); k++) {
							if (args.get(k).ref()) {
								this.m.joinOwn(callee, paramSets[k], args.get(k).refSet());
							}
						}
						this.m.calls(this.f, callee);
					}
					int spanStart = args.isEmpty() ? i : args.get(0).spanStart();
					pushAll(valuesOf(ft.results(), this.m.returnSets[callee], spanStart));
					emit(in);
					return i + 1;
				}
				case 0x12 -> { // return_call: a call whose results are this function's
					int callee = (int) in.a;
					FuncType ft = this.m.funcTypes[callee];
					List<Val> args = popN(ft.params().size());
					if (callee >= this.m.numImports) {
						if (!this.m.live[callee]) {
							this.m.live[callee] = true;
							this.m.dirty[callee] = true;
							this.m.changed = true;
						}
						this.m.materialize(callee - this.m.numImports);
						@Nullable BitSet[] paramSets = this.m.localSets[callee - this.m.numImports];
						for (int k = 0; k < args.size(); k++) {
							if (args.get(k).ref()) {
								this.m.joinOwn(callee, paramSets[k], args.get(k).refSet());
							}
						}
						this.m.calls(this.f, callee);
					}
					// What the callee answers is what this function returns: deliver the
					// callee's return sets to the function frame as a `return` would,
					// then nothing below is reachable.
					int spanStart = args.isEmpty() ? i : args.get(0).spanStart();
					pushAll(valuesOf(ft.results(), this.m.returnSets[callee], spanStart));
					branchTo(this.frames.size() - 1, true);
					emit(in);
					return unreachable(fr);
				}
				case 0x1A -> { // drop
					pop();
					emit(in);
					return i + 1;
				}
				case 0x1B -> { // select
					pop();
					Val b = pop();
					Val a = pop();
					if (a.ref()) {
						BitSet set = (BitSet) a.refSet().clone();
						set.or(b.refSet());
						push(Val.ref(set, a.spanStart()));
					}
					else {
						push(Val.scalar(a.spanStart()));
					}
					emit(in);
					return i + 1;
				}
				case 0x1F -> { // try_table
					BlockType bt = blockType(in);
					List<Val> params = popN(bt.params().size());
					for (WasmCodeModel.Catch c : java.util.Objects.requireNonNull(in.catches)) {
						// Catch labels are relative to the enclosing context, not to the
						// try_table's own label.
						Frame target = this.frames.get(this.frames.size() - 1 - c.label());
						List<@Nullable BitSet> delivered = new ArrayList<>();
						if (c.kind() == 0x00 || c.kind() == 0x01) {
							FuncType tag = this.m.tagTypes.get(c.tag());
							for (int k = 0; k < tag.params().size(); k++) {
								delivered.add(this.m.tagSets[c.tag()][k]);
								Model.reads(this.m.tagReaders, c.tag(), this.f);
							}
						}
						if (c.kind() == 0x01 || c.kind() == 0x03) {
							delivered.add(this.m.otherSet);
						}
						if (delivered.size() != target.arity()) {
							throw new IllegalStateException("WasmRefTypeFolder: catch arity mismatch");
						}
						for (int k = 0; k < delivered.size(); k++) {
							deliver(target, k, delivered.get(k));
						}
						if (target.kind != KIND_LOOP) {
							target.arrivals.add((BitSet) this.assigned.clone());
							target.branchedTo = true;
						}
					}
					Frame nf = new Frame(KIND_TRY, i, in.match, -1, bt.params(), bt.results(), this.stack.size(),
							params, Model.setsFor(bt.results()));
					nf.refinementMark = this.refinements.size();
					if (this.out != null) {
						emitTryTable(in, bt, java.util.Objects.requireNonNull(in.catches));
					}
					this.frames.add(nf);
					pushAll(params);
					return i + 1;
				}
				case 0x20 -> { // local.get
					int x = (int) in.a;
					if (this.localTypes[x].isRef()) {
						BitSet set = effectiveSet(x, i);
						push(new Val(true, set == this.localSets[x] ? (BitSet) set.clone() : set, null, x, i, i));
					}
					else {
						push(Val.scalar(i));
					}
					emit(in);
					return i + 1;
				}
				case 0x21 -> { // local.set
					int x = (int) in.a;
					Val v = pop();
					if (this.localTypes[x].isRef()) {
						this.m.joinOwn(this.f, this.localSets[x], v.refSet());
					}
					this.assigned.set(x);
					emit(in);
					return i + 1;
				}
				case 0x22 -> { // local.tee
					int x = (int) in.a;
					Val v = pop();
					if (this.localTypes[x].isRef()) {
						this.m.joinOwn(this.f, this.localSets[x], v.refSet());
					}
					this.assigned.set(x);
					push(new Val(v.ref(), v.set(), v.sym(), x, i, v.spanStart()));
					emit(in);
					return i + 1;
				}
				case 0x23 -> { // global.get
					int g = (int) in.a;
					if (this.m.globalTypes[g].isRef()) {
						Model.reads(this.m.globalReaders, g, this.f);
						push(Val.ref((BitSet) java.util.Objects.requireNonNull(this.m.globalSets[g]).clone(), i));
					}
					else {
						push(Val.scalar(i));
					}
					emit(in);
					return i + 1;
				}
				case 0x24 -> { // global.set
					int g = (int) in.a;
					Val v = pop();
					if (this.m.globalTypes[g].isRef()) {
						this.m.joinGlobal(g, this.m.globalSets[g], v.refSet());
					}
					emit(in);
					return i + 1;
				}
				case 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35 -> { // loads
					Val a = pop();
					push(Val.scalar(a.spanStart()));
					emit(in);
					return i + 1;
				}
				case 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E -> { // stores
					pop();
					pop();
					emit(in);
					return i + 1;
				}
				case 0x3F -> { // memory.size
					push(Val.scalar(i));
					emit(in);
					return i + 1;
				}
				case 0x40 -> { // memory.grow
					Val a = pop();
					push(Val.scalar(a.spanStart()));
					emit(in);
					return i + 1;
				}
				case 0x41 -> { // i32.const
					push(Val.i32(new Const((int) in.a), i));
					emit(in);
					return i + 1;
				}
				case 0x42, 0x43, 0x44 -> {
					push(Val.scalar(i));
					emit(in);
					return i + 1;
				}
				case 0xD0 -> { // ref.null
					push(Val.ref(this.m.nullSet, i));
					emit(in);
					return i + 1;
				}
				case 0xD1 -> { // ref.is_null
					Val v = pop();
					return stepTest(i, in, v, this.m.nullSet);
				}
				case 0xD3 -> { // ref.eq
					pop();
					Val a = pop();
					push(Val.scalar(a.spanStart()));
					emit(in);
					return i + 1;
				}
				case 0xFB -> {
					return stepGc(i, in);
				}
				case 0xFC -> {
					if (in.sub == 0x0A || in.sub == 0x0B) {
						// memory.copy / memory.fill: three operands, no result.
						pop();
						pop();
						pop();
					}
					else { // saturating truncation
						Val a = pop();
						push(Val.scalar(a.spanStart()));
					}
					emit(in);
					return i + 1;
				}
				case 0xFD -> {
					int[] arity = WasmCodeModel.simdArity(in.sub);
					List<Val> args = popN(arity[0]);
					int spanStart = args.isEmpty() ? i : args.get(0).spanStart();
					for (int k = 0; k < arity[1]; k++) {
						push(Val.scalar(spanStart));
					}
					emit(in);
					return i + 1;
				}
				default ->
					throw new IllegalStateException(String.format("WasmRefTypeFolder: unhandled opcode 0x%02X", in.op));
			}
		}

		private static BlockType blockType(Instr in) {
			return java.util.Objects.requireNonNull(in.blockType);
		}

		private int stepIf(int i, Instr in) {
			Frame fr = top();
			BlockType bt = blockType(in);
			Val cond = pop();
			List<Val> params = popN(bt.params().size());
			Frame nf = new Frame(KIND_IF, i, in.match, in.elseIndex, bt.params(), bt.results(), this.stack.size(),
					params, Model.setsFor(bt.results()));
			nf.entryAssigned = (BitSet) this.assigned.clone();
			nf.refinementMark = this.refinements.size();
			if (cond.sym() instanceof Const c) {
				boolean taken = c.value() != 0;
				discard(cond, i);
				if (!taken && in.elseIndex < 0) {
					// No arm to run: an if without else passes its parameters through as
					// its results, so the stack is already what follows.
					pushAll(params);
					return in.match + 1;
				}
				nf.folded = taken ? FOLD_THEN : FOLD_ELSE;
				// The arm keeps a label only when something branches to it (known from
				// the last analysis walk, which the rewrite repeats); otherwise it is
				// spliced in bare and the branches crossing it count one label fewer.
				nf.elided = !this.targeted[i];
				if (this.out != null && !nf.elided) {
					this.out.write(0x02); // block, with the if's own block type
					this.out.write(this.entry, bt.rawStart(), bt.rawEnd());
				}
				this.frames.add(nf);
				pushAll(params);
				if (taken) {
					return i + 1;
				}
				nf.inElse = true;
				return in.elseIndex + 1;
			}
			if (cond.sym() instanceof Bool b) {
				nf.cond = b;
				addRefinement(b, !b.neg(), i, in.elseIndex >= 0 ? in.elseIndex : in.match);
			}
			this.frames.add(nf);
			pushAll(params);
			emit(in);
			// The unreachable-jump target of a frame in its then-arm is its else, so a
			// reachable else arm is always walked; nothing else to do here.
			return i + 1;
		}

		private int stepElse(int i, Instr in) {
			Frame fr = top();
			if (fr.kind != KIND_IF) {
				throw new IllegalStateException("WasmRefTypeFolder: else outside if");
			}
			if (!fr.unreachable) {
				List<Val> vals = popN(fr.results.size());
				for (int k = 0; k < vals.size(); k++) {
					if (vals.get(k).ref()) {
						deliver(fr, k, vals.get(k).refSet());
					}
				}
				fr.thenFellThrough = true;
				fr.arrivals.add((BitSet) this.assigned.clone());
			}
			fr.inElse = true;
			dropRefinementsFrom(fr.refinementMark);
			if (fr.folded == FOLD_THEN) {
				// The else arm can never run: skip it, and let the end see no fallthrough
				// from it.
				fr.unreachable = true;
				return fr.endIndex;
			}
			truncateStack(fr.stackBase);
			pushAll(fr.paramVals);
			this.assigned = (BitSet) java.util.Objects.requireNonNull(fr.entryAssigned).clone();
			fr.unreachable = false;
			if (fr.cond != null) {
				addRefinement(fr.cond, fr.cond.neg(), i, fr.endIndex);
			}
			emit(in);
			return i + 1;
		}

		private int stepEnd(int i, Instr in) {
			Frame fr = top();
			boolean fell = !fr.unreachable;
			if (fr.kind == KIND_FUNC) {
				if (fell) {
					branchTo(0, true);
				}
				emit(in);
				return i + 1;
			}
			@Nullable List<Val> fallVals = fell ? popN(fr.results.size()) : null;
			this.frames.remove(this.frames.size() - 1);
			dropRefinementsFrom(fr.refinementMark);
			if (fr.kind == KIND_IF) {
				this.targeted[fr.opener] = fr.branchedTo;
			}
			Frame outer = top();
			boolean after;
			switch (fr.kind) {
				case KIND_LOOP -> after = fell;
				case KIND_IF -> {
					if (fallVals != null) {
						joinCarried(fr, fallVals);
						fr.arrivals.add((BitSet) this.assigned.clone());
					}
					if (fr.folded == FOLD_NONE && fr.elseIndex < 0) {
						// The implicit else: the parameters fall through as the results.
						joinCarried(fr, fr.paramVals);
						fr.arrivals.add(java.util.Objects.requireNonNull(fr.entryAssigned));
					}
					after = !fr.arrivals.isEmpty();
					if (fr.cond != null && fr.folded == FOLD_NONE && !fr.branchedTo) {
						boolean thenFalls = fr.elseIndex >= 0 ? fr.thenFellThrough : fell;
						boolean elseFalls = fr.elseIndex >= 0 ? fell : true;
						if (thenFalls != elseFalls) {
							// Only one arm ever falls through, so past the end the
							// guard's
							// answer is known.
							addRefinement(fr.cond, thenFalls != fr.cond.neg(), i, regionEnd(outer, i));
						}
					}
				}
				default -> {
					if (fallVals != null) {
						joinCarried(fr, fallVals);
						fr.arrivals.add((BitSet) this.assigned.clone());
					}
					after = !fr.arrivals.isEmpty();
				}
			}
			if (fr.kind == KIND_LOOP) {
				if (fallVals != null) {
					pushAll(fallVals);
				}
			}
			else {
				truncateStack(fr.stackBase);
				pushAll(valuesOf(fr.results, fr.carried, fr.opener));
				if (after) {
					BitSet merged = (BitSet) fr.arrivals.get(0).clone();
					for (int k = 1; k < fr.arrivals.size(); k++) {
						merged.and(fr.arrivals.get(k));
					}
					this.assigned = merged;
				}
			}
			if (!fr.elided) {
				emit(in);
				if (!after && this.out != null) {
					// The block's end is never reached, but a block's END is not itself
					// stack-polymorphic for the validator: say so explicitly. (A
					// spliced-in
					// arm ends on its own terminator, which already is.)
					this.out.write(0x00);
				}
			}
			if (!after) {
				return unreachable(outer);
			}
			return i + 1;
		}

		// A label depth as emitted: the frames a branch crosses minus the ones spliced in
		// without a label. `size` is the frame count the depth is relative to.
		private int adjustedDepth(int depth, int size) {
			int adjusted = depth;
			for (int k = size - depth; k < size; k++) {
				if (this.frames.get(k).elided) {
					adjusted--;
				}
			}
			if (this.frames.get(size - 1 - depth).elided) {
				throw new IllegalStateException("WasmRefTypeFolder: a branch targets a spliced-in arm");
			}
			return adjusted;
		}

		// Emits a br/br_if with its label depth adjusted for spliced-in arms; `in` is the
		// original instruction (copied verbatim when nothing changed), or null for a
		// br_if folded to a br.
		private void emitBranch(@Nullable Instr in, int opcode, int depth) {
			if (this.out == null) {
				return;
			}
			int adjusted = adjustedDepth(depth, this.frames.size());
			if (in != null && adjusted == depth) {
				emit(in);
				return;
			}
			this.out.write(opcode);
			this.out.writeU(adjusted);
		}

		// Emits a try_table, its catch labels (relative to the enclosing context)
		// adjusted
		// for spliced-in arms.
		private void emitTryTable(Instr in, BlockType bt, List<WasmCodeModel.Catch> catches) {
			if (this.out == null) {
				return;
			}
			boolean adjusted = false;
			int[] labels = new int[catches.size()];
			for (int k = 0; k < labels.length; k++) {
				labels[k] = adjustedDepth(catches.get(k).label(), this.frames.size());
				adjusted |= labels[k] != catches.get(k).label();
			}
			if (!adjusted) {
				emit(in);
				return;
			}
			this.out.write(0x1F);
			this.out.write(this.entry, bt.rawStart(), bt.rawEnd());
			this.out.writeU(catches.size());
			for (int k = 0; k < labels.length; k++) {
				WasmCodeModel.Catch c = catches.get(k);
				this.out.write(c.kind());
				if (c.kind() == 0x00 || c.kind() == 0x01) {
					this.out.writeU(c.tag());
				}
				this.out.writeU(labels[k]);
			}
		}

		private void joinCarried(Frame fr, List<Val> vals) {
			for (int k = 0; k < vals.size(); k++) {
				if (vals.get(k).ref()) {
					deliver(fr, k, vals.get(k).refSet());
				}
			}
		}

		// Joins a delivered value into a label's k-th carried set; growth counts toward
		// the fixpoint only for a persistent set.
		private void deliver(Frame target, int k, @Nullable BitSet src) {
			@Nullable BitSet carried = target.carried[k];
			if (carried == null || src == null) {
				return;
			}
			if (target.persistent) {
				if (target.kind == KIND_LOOP) {
					this.m.joinOwn(this.f, carried, src);
				}
				else if (this.f >= 0) {
					this.m.joinReturn(this.f, carried, src);
				}
				else {
					this.m.joinGlobal(this.global, carried, src);
				}
			}
			else {
				carried.or(src);
			}
		}

		// ref.test / ref.is_null over `v`: fold when the set decides it, else keep the
		// question symbolic when it is about a local. A value NO source produces is not
		// folded either way -- nothing reaches the test, so the code from here to the
		// end of the block is dead. (Folding it to 0 would be the wrong optimism: it
		// would select the else arm and count that arm's constructors as reachable, and a
		// runtime helper's rational fallback would then keep the rational type alive for
		// the very test that should have ruled it out.)
		private int stepTest(int i, Instr in, Val v, BitSet target) {
			if (v.refSet().isEmpty()) {
				if (this.out != null) {
					this.out.write(0x00);
				}
				return unreachable(top());
			}
			@Nullable Sym sym = decide(v, target);
			if (sym instanceof Const c) {
				foldTo(v, i, c.value());
				return i + 1;
			}
			push(Val.i32(sym, v.spanStart()));
			emit(in);
			return i + 1;
		}

		private int stepGc(int i, Instr in) {
			int t = (int) in.a;
			switch (in.sub) {
				case 0x00 -> { // struct.new
					StructType st = (StructType) this.m.types.types().get(t);
					List<Val> args = popN(st.fields().size());
					for (int k = 0; k < args.size(); k++) {
						if (args.get(k).ref()) {
							this.m.joinField(t, this.m.fieldSets[t][k], args.get(k).refSet());
						}
					}
					push(Val.ref(this.m.typeClassSet[t], args.isEmpty() ? i : args.get(0).spanStart()));
				}
				case 0x01 -> { // struct.new_default
					for (@Nullable
					BitSet field : this.m.fieldSets[t]) {
						this.m.joinField(t, field, this.m.nullSet);
					}
					push(Val.ref(this.m.typeClassSet[t], i));
				}
				case 0x02, 0x03, 0x04 -> { // struct.get(_s|_u)
					Val ref = pop();
					Model.reads(this.m.fieldReaders, t, this.f);
					@Nullable BitSet field = this.m.fieldSets[t][(int) in.b];
					push(field != null ? Val.ref((BitSet) field.clone(), ref.spanStart())
							: Val.scalar(ref.spanStart()));
				}
				case 0x05 -> { // struct.set
					Val v = pop();
					pop();
					if (v.ref()) {
						this.m.joinField(t, this.m.fieldSets[t][(int) in.b], v.refSet());
					}
				}
				case 0x06 -> { // array.new
					pop();
					Val init = pop();
					if (init.ref()) {
						this.m.joinField(t, this.m.fieldSets[t][0], init.refSet());
					}
					push(Val.ref(this.m.typeClassSet[t], init.spanStart()));
				}
				case 0x07 -> { // array.new_default
					Val len = pop();
					this.m.joinField(t, this.m.fieldSets[t][0], this.m.nullSet);
					push(Val.ref(this.m.typeClassSet[t], len.spanStart()));
				}
				case 0x08 -> { // array.new_fixed
					List<Val> args = popN((int) in.b);
					for (Val a : args) {
						if (a.ref()) {
							this.m.joinField(t, this.m.fieldSets[t][0], a.refSet());
						}
					}
					push(Val.ref(this.m.typeClassSet[t], args.isEmpty() ? i : args.get(0).spanStart()));
				}
				case 0x0B, 0x0C, 0x0D -> { // array.get(_s|_u)
					pop();
					Val arr = pop();
					Model.reads(this.m.fieldReaders, t, this.f);
					@Nullable BitSet elem = this.m.fieldSets[t][0];
					push(elem != null ? Val.ref((BitSet) elem.clone(), arr.spanStart()) : Val.scalar(arr.spanStart()));
				}
				case 0x0E -> { // array.set
					Val v = pop();
					pop();
					pop();
					if (v.ref()) {
						this.m.joinField(t, this.m.fieldSets[t][0], v.refSet());
					}
				}
				case 0x0F -> { // array.len
					Val arr = pop();
					push(Val.scalar(arr.spanStart()));
				}
				case 0x10 -> { // array.fill
					pop();
					Val v = pop();
					pop();
					pop();
					if (v.ref()) {
						this.m.joinField(t, this.m.fieldSets[t][0], v.refSet());
					}
				}
				case 0x11 -> { // array.copy dst src
					for (int k = 0; k < 5; k++) {
						pop();
					}
					Model.reads(this.m.fieldReaders, (int) in.b, this.f);
					this.m.joinField(t, this.m.fieldSets[t][0], this.m.fieldSets[(int) in.b][0]);
				}
				case 0x14, 0x15 -> { // ref.test
					Val v = pop();
					@Nullable BitSet target = this.m.heapTypeSet(in.a);
					if (target == null) {
						push(Val.scalar(v.spanStart()));
						break;
					}
					if (in.sub == 0x15) {
						target = (BitSet) target.clone();
						target.or(this.m.nullSet);
					}
					return stepTest(i, in, v, target);
				}
				case 0x16, 0x17 -> { // ref.cast
					Val v = pop();
					@Nullable BitSet target = this.m.heapTypeSet(in.a);
					if (target == null) {
						push(v);
						break;
					}
					BitSet narrowed = (BitSet) v.refSet().clone();
					narrowed.and(target);
					if (in.sub == 0x17 && v.refSet().get(this.m.nullBit)) {
						narrowed.set(this.m.nullBit);
					}
					if (narrowed.isEmpty()) {
						// A cast no value can pass: it always traps, so nothing after it
						// runs.
						if (this.out != null) {
							this.out.write(0x00);
						}
						return unreachable(top());
					}
					push(new Val(true, narrowed, null, v.local(), v.getPos(), v.spanStart()));
				}
				case 0x1C -> { // ref.i31
					Val a = pop();
					push(Val.ref(this.m.i31Set, a.spanStart()));
				}
				case 0x1D, 0x1E -> { // i31.get_s / i31.get_u
					Val a = pop();
					push(Val.scalar(a.spanStart()));
				}
				default -> throw new IllegalStateException(
						String.format("WasmRefTypeFolder: unhandled GC opcode 0xFB 0x%02X", in.sub));
			}
			emit(in);
			return i + 1;
		}

		private int stepNumeric(int i, Instr in) {
			switch (in.op) {
				case 0x45 -> { // i32.eqz
					Val a = pop();
					if (a.sym() instanceof Const c) {
						foldTo(a, i, c.value() == 0 ? 1 : 0);
						return i + 1;
					}
					if (a.sym() instanceof Bool b) {
						push(Val.i32(new Bool(b.local(), b.member(), !b.neg(), b.getPos()), a.spanStart()));
					}
					else {
						push(Val.scalar(a.spanStart()));
					}
					emit(in);
					return i + 1;
				}
				case 0x71, 0x72 -> { // i32.and / i32.or
					return stepAndOr(i, in, in.op == 0x72);
				}
				case 0x46, 0x47 -> { // i32.eq / i32.ne
					Val b = pop();
					Val a = pop();
					if (a.sym() instanceof Const ca && b.sym() instanceof Const cb) {
						boolean eq = ca.value() == cb.value();
						foldPairTo(a, b, in, i, (in.op == 0x46) == eq ? 1 : 0);
						return i + 1;
					}
					if (b.sym() instanceof Const cb && cb.value() == 0 && a.sym() instanceof Bool ba) {
						// `x == 0` is `not x`; `x != 0` is `x` for a 0/1 value.
						boolean flip = in.op == 0x46;
						if (this.out != null && pureRange(b.spanStart(), i)) {
							this.out.truncate(this.emitPos[b.spanStart()]);
							if (flip) {
								this.out.write(0x45); // i32.eqz
							}
						}
						else {
							emit(in);
						}
						push(Val.i32(flip ? new Bool(ba.local(), ba.member(), !ba.neg(), ba.getPos()) : ba,
								a.spanStart()));
						return i + 1;
					}
					push(Val.scalar(a.spanStart()));
					emit(in);
					return i + 1;
				}
				default -> {
					List<Val> args = popN(WasmCodeModel.numericPops(in.op));
					push(Val.scalar(args.get(0).spanStart()));
					emit(in);
					return i + 1;
				}
			}
		}

		private int stepAndOr(int i, Instr in, boolean or) {
			Val b = pop();
			Val a = pop();
			@Nullable Sym sa = a.sym();
			@Nullable Sym sb = b.sym();
			if (sa instanceof Const ca && sb instanceof Const cb) {
				foldPairTo(a, b, in, i, or ? ca.value() | cb.value() : ca.value() & cb.value());
				return i + 1;
			}
			// The identity element on either side leaves the other operand as the value.
			int identity = or ? 0 : 1;
			if (sb instanceof Const cb && cb.value() == identity && isBoolean(sa)) {
				if (this.out != null && pureRange(b.spanStart(), i)) {
					this.out.truncate(this.emitPos[b.spanStart()]);
				}
				else {
					emit(in);
				}
				push(new Val(false, null, sa, a.local(), a.getPos(), a.spanStart()));
				return i + 1;
			}
			if (sa instanceof Const ca && ca.value() == identity && isBoolean(sb)) {
				if (this.out != null && pureRange(a.spanStart(), b.spanStart())) {
					this.out.delete(this.emitPos[a.spanStart()], this.emitPos[b.spanStart()]);
					shiftEmitPos(this.emitPos[a.spanStart()], this.emitPos[b.spanStart()]);
				}
				else {
					emit(in);
				}
				push(new Val(false, null, sb, b.local(), b.getPos(), a.spanStart()));
				return i + 1;
			}
			// The absorbing element: 0 & x = 0, and 1 | x = 1 for a 0/1-valued x.
			int absorbing = or ? 1 : 0;
			if ((sb instanceof Const cb && cb.value() == absorbing && (or ? isBoolean(sa) : true))
					|| (sa instanceof Const ca && ca.value() == absorbing && (or ? isBoolean(sb) : true))) {
				foldPairTo(a, b, in, i, absorbing);
				return i + 1;
			}
			if (sa instanceof Bool ba && sb instanceof Bool bb && ba.local() == bb.local()
					&& !assignedIn(ba.local(), ba.getPos(), bb.getPos()) && ba.neg() == bb.neg()) {
				// Two questions about one local combine into one: A or B is membership in
				// the union, A and B in the intersection; both negated, De Morgan.
				BitSet member = (BitSet) ba.member().clone();
				if (or != ba.neg()) {
					member.or(bb.member());
				}
				else {
					member.and(bb.member());
				}
				Bool merged = new Bool(ba.local(), member, ba.neg(), bb.getPos());
				Sym decided = redecide(merged, i);
				if (decided instanceof Const c) {
					foldPairTo(a, b, in, i, c.value());
					return i + 1;
				}
				push(new Val(false, null, merged, ba.local(), bb.getPos(), a.spanStart()));
				emit(in);
				return i + 1;
			}
			push(Val.scalar(a.spanStart()));
			emit(in);
			return i + 1;
		}

		private static boolean isBoolean(@Nullable Sym s) {
			return s instanceof Bool || (s instanceof Const c && (c.value() == 0 || c.value() == 1));
		}

		// After deleting emitted bytes [from, to), every later instruction's position
		// moves down.
		private void shiftEmitPos(int from, int to) {
			int len = to - from;
			for (int k = 0; k < this.emitPos.length; k++) {
				if (this.emitPos[k] >= to) {
					this.emitPos[k] -= len;
				}
				else if (this.emitPos[k] > from) {
					this.emitPos[k] = from;
				}
			}
		}

	}

}
