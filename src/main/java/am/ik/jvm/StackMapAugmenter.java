package am.ik.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Language-independent post-pass that inserts a {@code StackMapTable} into every method
 * of a finished class file and raises the class-file version, so the output verifies
 * under the type-checking verifier that class version 51+ makes mandatory.
 * <p>
 * The pass re-derives the frames from nothing but the finished bytes: it parses the class
 * (the same single-{@code Code}-attribute shape {@link JvmClassShaker} relies on), runs a
 * verifier-style abstract interpretation over each method body -- a worklist dataflow
 * whose abstract values are the JVMS verification types (int/float/long/double, null,
 * uninitialized, and reference types with their class names) tracked through both the
 * operand stack and the local variables -- and records the fixpoint frame at every
 * position that needs one: each branch target, each exception-handler entry, and each
 * instruction following an unconditional transfer.
 * <p>
 * Code the dataflow never reaches (instructions emitted after an unconditional transfer
 * and never jumped to) cannot carry a meaningful frame, and the type-checking verifier
 * still demands one; each maximal dead run is therefore neutralized the way ASM's
 * {@code COMPUTE_FRAMES} does it -- overwritten with {@code nop}s ending in an
 * {@code athrow}, under a synthetic frame whose stack holds one {@code Throwable}.
 * <p>
 * Reference types merge to their nearest common superclass. The pass carries no class
 * hierarchy beyond a fixed table of the {@code java.lang} relations the code generators
 * actually rely on (the boxed numerics under {@code Number}); any other unequal pair
 * merges to {@code java/lang/Object}, and a downstream instruction that would need the
 * narrower type makes the pass throw rather than emit an unverifiable frame. Interface
 * types need no modelling at all: the verifier treats them as {@code Object} and defers
 * the real check to {@code invokeinterface}.
 * <p>
 * The pass must run after {@link JvmClassShaker} when both apply: the shaker rejects
 * {@code Code} sub-attributes, and the frames reference constant-pool entries this pass
 * appends, which the shaker's compaction would not know how to rewrite.
 */
public final class StackMapAugmenter {

	private StackMapAugmenter() {
	}

	// Constant pool tags.
	private static final int TAG_UTF8 = 1;

	private static final int TAG_INTEGER = 3;

	private static final int TAG_FLOAT = 4;

	private static final int TAG_LONG = 5;

	private static final int TAG_DOUBLE = 6;

	private static final int TAG_CLASS = 7;

	private static final int TAG_STRING = 8;

	private static final int TAG_FIELDREF = 9;

	private static final int TAG_METHODREF = 10;

	private static final int TAG_INTERFACE_METHODREF = 11;

	private static final int TAG_NAME_AND_TYPE = 12;

	// Verification type tags (JVMS 4.7.4), used directly as the VType kind.
	private static final int ITEM_TOP = 0;

	private static final int ITEM_INTEGER = 1;

	private static final int ITEM_FLOAT = 2;

	private static final int ITEM_DOUBLE = 3;

	private static final int ITEM_LONG = 4;

	private static final int ITEM_NULL = 5;

	private static final int ITEM_UNINITIALIZED_THIS = 6;

	private static final int ITEM_OBJECT = 7;

	private static final int ITEM_UNINITIALIZED = 8;

	/**
	 * Boxed numerics share {@code java/lang/Number}: the one non-trivial superclass
	 * relation a merge in generated code can need. Everything else merges to Object.
	 */
	private static final Map<String, String> SUPERCLASS = Map.of( //
			"java/lang/Long", "java/lang/Number", //
			"java/lang/Integer", "java/lang/Number", //
			"java/lang/Double", "java/lang/Number", //
			"java/lang/Float", "java/lang/Number", //
			"java/lang/Short", "java/lang/Number", //
			"java/lang/Byte", "java/lang/Number", //
			"java/lang/Number", "java/lang/Object");

	private static final String OBJECT_CLASS = "java/lang/Object";

	private static final String THROWABLE_CLASS = "java/lang/Throwable";

	/** A verification type: one operand-stack or local-variable entry. */
	private record VType(int kind, @Nullable String cls, int newPc) {

		static final VType TOP = new VType(ITEM_TOP, null, 0);

		static final VType INT = new VType(ITEM_INTEGER, null, 0);

		static final VType FLOAT = new VType(ITEM_FLOAT, null, 0);

		static final VType DOUBLE = new VType(ITEM_DOUBLE, null, 0);

		static final VType LONG = new VType(ITEM_LONG, null, 0);

		static final VType NULL = new VType(ITEM_NULL, null, 0);

		static final VType UNINIT_THIS = new VType(ITEM_UNINITIALIZED_THIS, null, 0);

		static VType object(String cls) {
			return new VType(ITEM_OBJECT, cls, 0);
		}

		static VType uninitialized(int newPc) {
			return new VType(ITEM_UNINITIALIZED, null, newPc);
		}

		boolean wide() {
			return this.kind == ITEM_LONG || this.kind == ITEM_DOUBLE;
		}

		boolean isReference() {
			return this.kind == ITEM_OBJECT || this.kind == ITEM_NULL || this.kind == ITEM_UNINITIALIZED
					|| this.kind == ITEM_UNINITIALIZED_THIS;
		}

	}

	/** The abstract machine state at one code position: locals and operand stack. */
	private static final class Frame {

		final VType[] locals;

		final List<VType> stack;

		Frame(VType[] locals, List<VType> stack) {
			this.locals = locals;
			this.stack = stack;
		}

		Frame copy() {
			return new Frame(this.locals.clone(), new ArrayList<>(this.stack));
		}

	}

	/** A constant pool entry: its tag and its body bytes (everything after the tag). */
	private record CpEntry(int tag, byte[] body) {
	}

	private record FieldInfo(int access, int nameIdx, int descIdx) {
	}

	private record ExcEntry(int startPc, int endPc, int handlerPc, int catchType) {
	}

	private record MethodInfo(int access, int nameIdx, int descIdx, int codeAttrNameIdx, int maxStack, int maxLocals,
			byte[] code, List<ExcEntry> exceptionTable) {
	}

	/** One StackMapTable entry: the frame asserted at a code offset. */
	private record FrameEntry(int pc, List<VType> locals, List<VType> stack) {
	}

	/**
	 * A method's analysis output: the (possibly dead-code-patched) code, the exception
	 * table with dead ranges carved out, and the frames.
	 */
	private record MethodFrames(byte[] code, List<ExcEntry> exceptionTable, List<FrameEntry> frames) {
	}

	/**
	 * Computes and inserts a {@code StackMapTable} into every method and stamps the given
	 * class-file major version.
	 * @param classFile a class file as produced by {@link ByteCodeWriter} (single
	 * {@code Code} attribute per method, no other attributes anywhere)
	 * @param majorVersion the class-file major version to stamp (e.g. 61 for Java 17)
	 * @return the augmented class file
	 */
	public static byte[] augment(byte[] classFile, int majorVersion) {
		int[] p = { 0 };
		int magic = readU4(classFile, p);
		if (magic != 0xCAFEBABE) {
			throw new IllegalStateException("StackMapAugmenter: not a class file");
		}
		int minor = readU2(classFile, p);
		readU2(classFile, p); // major, replaced by majorVersion

		int cpCount = readU2(classFile, p);
		List<@Nullable CpEntry> cp = new ArrayList<>(cpCount);
		cp.add(null); // index 0 is unused
		for (int i = 1; i < cpCount; i++) {
			int tag = classFile[p[0]++] & 0xff;
			int bodyLen = switch (tag) {
				case TAG_UTF8 -> 2 + ((classFile[p[0]] & 0xff) << 8 | (classFile[p[0] + 1] & 0xff));
				case TAG_INTEGER, TAG_FLOAT, TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE ->
					4;
				case TAG_LONG, TAG_DOUBLE -> 8;
				case TAG_CLASS, TAG_STRING -> 2;
				default -> throw new IllegalStateException("StackMapAugmenter: unhandled constant tag " + tag);
			};
			cp.add(new CpEntry(tag, Arrays.copyOfRange(classFile, p[0], p[0] + bodyLen)));
			p[0] += bodyLen;
			if (tag == TAG_LONG || tag == TAG_DOUBLE) {
				cp.add(null); // phantom second slot
				i++;
			}
		}

		int accessFlags = readU2(classFile, p);
		int thisIdx = readU2(classFile, p);
		int superIdx = readU2(classFile, p);
		int interfacesCount = readU2(classFile, p);
		int[] interfaces = new int[interfacesCount];
		for (int i = 0; i < interfacesCount; i++) {
			interfaces[i] = readU2(classFile, p);
		}

		int fieldsCount = readU2(classFile, p);
		List<FieldInfo> fields = new ArrayList<>(fieldsCount);
		for (int i = 0; i < fieldsCount; i++) {
			int access = readU2(classFile, p);
			int nameIdx = readU2(classFile, p);
			int descIdx = readU2(classFile, p);
			int attrCount = readU2(classFile, p);
			if (attrCount != 0) {
				throw new IllegalStateException("StackMapAugmenter: unsupported field attribute");
			}
			fields.add(new FieldInfo(access, nameIdx, descIdx));
		}

		int methodsCount = readU2(classFile, p);
		List<MethodInfo> methods = new ArrayList<>(methodsCount);
		for (int i = 0; i < methodsCount; i++) {
			int access = readU2(classFile, p);
			int nameIdx = readU2(classFile, p);
			int descIdx = readU2(classFile, p);
			int attrCount = readU2(classFile, p);
			if (attrCount != 1) {
				throw new IllegalStateException(
						"StackMapAugmenter: expected exactly one method attribute, got " + attrCount);
			}
			int attrNameIdx = readU2(classFile, p);
			if (!"Code".equals(utf8(cp, attrNameIdx))) {
				throw new IllegalStateException(
						"StackMapAugmenter: unsupported method attribute " + utf8(cp, attrNameIdx));
			}
			readU4(classFile, p); // attribute_length (recomputed on write)
			int maxStack = readU2(classFile, p);
			int maxLocals = readU2(classFile, p);
			int codeLen = readU4(classFile, p);
			byte[] code = Arrays.copyOfRange(classFile, p[0], p[0] + codeLen);
			p[0] += codeLen;
			int excCount = readU2(classFile, p);
			List<ExcEntry> exceptionTable = new ArrayList<>(excCount);
			for (int j = 0; j < excCount; j++) {
				exceptionTable.add(new ExcEntry(readU2(classFile, p), readU2(classFile, p), readU2(classFile, p),
						readU2(classFile, p)));
			}
			int codeAttrCount = readU2(classFile, p);
			if (codeAttrCount != 0) {
				throw new IllegalStateException("StackMapAugmenter: unsupported Code sub-attribute");
			}
			methods
				.add(new MethodInfo(access, nameIdx, descIdx, attrNameIdx, maxStack, maxLocals, code, exceptionTable));
		}

		int classAttrCount = readU2(classFile, p);
		if (classAttrCount != 0) {
			throw new IllegalStateException("StackMapAugmenter: unsupported class attribute");
		}
		if (p[0] != classFile.length) {
			throw new IllegalStateException("StackMapAugmenter: trailing bytes after class structure");
		}

		String thisClassName = className(cp, thisIdx);

		// Analyze every method.
		List<MethodFrames> analyzed = new ArrayList<>(methods.size());
		for (MethodInfo m : methods) {
			try {
				analyzed.add(new MethodAnalyzer(cp, thisClassName, m).analyze());
			}
			catch (RuntimeException e) {
				throw new IllegalStateException("StackMapAugmenter: method " + utf8(cp, m.nameIdx) + utf8(cp, m.descIdx)
						+ ": " + e.getMessage(), e);
			}
		}

		// Constant-pool entries the frames need: the attribute name and one Class entry
		// per referenced type. Reuse an existing entry when one exists.
		Map<String, Integer> classIdxByName = new HashMap<>();
		Map<String, Integer> utf8IdxByValue = new HashMap<>();
		for (int i = 1; i < cp.size(); i++) {
			CpEntry e = cp.get(i);
			if (e == null) {
				continue;
			}
			if (e.tag == TAG_UTF8) {
				utf8IdxByValue.putIfAbsent(utf8(cp, i), i);
			}
			else if (e.tag == TAG_CLASS) {
				classIdxByName.putIfAbsent(className(cp, i), i);
			}
		}
		Set<String> neededClasses = new TreeSet<>();
		boolean anyFrames = false;
		for (MethodFrames mf : analyzed) {
			for (FrameEntry fe : mf.frames) {
				anyFrames = true;
				for (VType t : fe.locals) {
					if (t.kind == ITEM_OBJECT) {
						neededClasses.add(Objects.requireNonNull(t.cls));
					}
				}
				for (VType t : fe.stack) {
					if (t.kind == ITEM_OBJECT) {
						neededClasses.add(Objects.requireNonNull(t.cls));
					}
				}
			}
		}
		int stackMapNameIdx = 0;
		if (anyFrames) {
			stackMapNameIdx = utf8IdxByValue.computeIfAbsent("StackMapTable", v -> appendUtf8(cp, v));
			for (String name : neededClasses) {
				classIdxByName.computeIfAbsent(name, n -> {
					int nameUtf8 = utf8IdxByValue.computeIfAbsent(n, v -> appendUtf8(cp, v));
					cp.add(new CpEntry(TAG_CLASS, new byte[] { (byte) (nameUtf8 >>> 8), (byte) nameUtf8 }));
					return cp.size() - 1;
				});
			}
		}
		if (cp.size() > 0xFFFF) {
			throw new IllegalStateException("StackMapAugmenter: constant pool overflow: " + cp.size());
		}

		// Reassemble.
		ByteArrayOutputStream out = new ByteArrayOutputStream(classFile.length + 1024);
		writeU4(out, 0xCAFEBABE);
		writeU2(out, minor);
		writeU2(out, majorVersion);
		writeU2(out, cp.size());
		for (int i = 1; i < cp.size(); i++) {
			CpEntry e = cp.get(i);
			if (e == null) {
				continue;
			}
			out.write(e.tag);
			out.write(e.body, 0, e.body.length);
		}
		writeU2(out, accessFlags);
		writeU2(out, thisIdx);
		writeU2(out, superIdx);
		writeU2(out, interfaces.length);
		for (int idx : interfaces) {
			writeU2(out, idx);
		}
		writeU2(out, fields.size());
		for (FieldInfo f : fields) {
			writeU2(out, f.access);
			writeU2(out, f.nameIdx);
			writeU2(out, f.descIdx);
			writeU2(out, 0);
		}
		writeU2(out, methods.size());
		for (int i = 0; i < methods.size(); i++) {
			MethodInfo m = methods.get(i);
			MethodFrames mf = analyzed.get(i);
			byte[] stackMap = mf.frames.isEmpty() ? new byte[0] : encodeStackMapTable(mf.frames, classIdxByName);
			writeU2(out, m.access);
			writeU2(out, m.nameIdx);
			writeU2(out, m.descIdx);
			writeU2(out, 1); // the single Code attribute
			writeU2(out, m.codeAttrNameIdx);
			int codeAttrLen = 2 + 2 + 4 + mf.code.length + 2 + 8 * mf.exceptionTable.size() + 2
					+ (stackMap.length == 0 ? 0 : 2 + 4 + stackMap.length);
			writeU4(out, codeAttrLen);
			writeU2(out, m.maxStack);
			writeU2(out, m.maxLocals);
			writeU4(out, mf.code.length);
			out.write(mf.code, 0, mf.code.length);
			writeU2(out, mf.exceptionTable.size());
			for (ExcEntry e : mf.exceptionTable) {
				writeU2(out, e.startPc);
				writeU2(out, e.endPc);
				writeU2(out, e.handlerPc);
				writeU2(out, e.catchType);
			}
			if (stackMap.length == 0) {
				writeU2(out, 0);
			}
			else {
				writeU2(out, 1);
				writeU2(out, stackMapNameIdx);
				writeU4(out, stackMap.length);
				out.write(stackMap, 0, stackMap.length);
			}
		}
		writeU2(out, 0); // class attributes
		return out.toByteArray();
	}

	private static int appendUtf8(List<@Nullable CpEntry> cp, String value) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		// The frame class names are plain ASCII, so UTF-8 == modified UTF-8 here.
		byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		buf.write((bytes.length >>> 8) & 0xff);
		buf.write(bytes.length & 0xff);
		buf.write(bytes, 0, bytes.length);
		cp.add(new CpEntry(TAG_UTF8, buf.toByteArray()));
		return cp.size() - 1;
	}

	// --- StackMapTable encoding ---

	/**
	 * Encodes the frames, sorted by offset, using delta encoding with the compact frame
	 * types where they apply ({@code same_frame}, {@code same_locals_1_stack_item} and
	 * their extended forms) and {@code full_frame} otherwise.
	 */
	private static byte[] encodeStackMapTable(List<FrameEntry> frames, Map<String, Integer> classIdxByName) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeU2(out, frames.size());
		int prevPc = -1;
		List<VType> prevLocals = null;
		for (FrameEntry fe : frames) {
			int delta = prevPc < 0 ? fe.pc : fe.pc - prevPc - 1;
			boolean sameLocals = fe.locals.equals(prevLocals);
			if (sameLocals && fe.stack.isEmpty()) {
				if (delta <= 63) {
					out.write(delta); // same_frame
				}
				else {
					out.write(251); // same_frame_extended
					writeU2(out, delta);
				}
			}
			else if (sameLocals && fe.stack.size() == 1) {
				if (delta <= 63) {
					out.write(64 + delta); // same_locals_1_stack_item
				}
				else {
					out.write(247); // same_locals_1_stack_item_extended
					writeU2(out, delta);
				}
				writeVType(out, fe.stack.get(0), classIdxByName);
			}
			else if (prevLocals != null && fe.stack.isEmpty() && fe.locals.size() > prevLocals.size()
					&& fe.locals.size() - prevLocals.size() <= 3
					&& fe.locals.subList(0, prevLocals.size()).equals(prevLocals)) {
				int appended = fe.locals.size() - prevLocals.size();
				out.write(251 + appended); // append_frame
				writeU2(out, delta);
				for (VType t : fe.locals.subList(prevLocals.size(), fe.locals.size())) {
					writeVType(out, t, classIdxByName);
				}
			}
			else if (prevLocals != null && fe.stack.isEmpty() && prevLocals.size() > fe.locals.size()
					&& prevLocals.size() - fe.locals.size() <= 3
					&& prevLocals.subList(0, fe.locals.size()).equals(fe.locals)) {
				out.write(251 - (prevLocals.size() - fe.locals.size())); // chop_frame
				writeU2(out, delta);
			}
			else {
				out.write(255); // full_frame
				writeU2(out, delta);
				writeU2(out, fe.locals.size());
				for (VType t : fe.locals) {
					writeVType(out, t, classIdxByName);
				}
				writeU2(out, fe.stack.size());
				for (VType t : fe.stack) {
					writeVType(out, t, classIdxByName);
				}
			}
			prevPc = fe.pc;
			prevLocals = fe.locals;
		}
		return out.toByteArray();
	}

	private static void writeVType(ByteArrayOutputStream out, VType t, Map<String, Integer> classIdxByName) {
		out.write(t.kind);
		if (t.kind == ITEM_OBJECT) {
			writeU2(out, Objects.requireNonNull(classIdxByName.get(t.cls)));
		}
		else if (t.kind == ITEM_UNINITIALIZED) {
			writeU2(out, t.newPc);
		}
	}

	// --- Per-method dataflow analysis ---

	private static final class MethodAnalyzer {

		private final List<@Nullable CpEntry> cp;

		private final String thisClassName;

		private final MethodInfo m;

		private final byte[] code;

		/** Entry frames of the positions that need a StackMapTable entry. */
		private final TreeMap<Integer, Frame> leaderFrames = new TreeMap<>();

		/** Positions needing a frame: branch targets, handlers, post-unconditional. */
		private final TreeSet<Integer> needsFrame = new TreeSet<>();

		private final TreeSet<Integer> leaders = new TreeSet<>();

		private final boolean[] visited;

		MethodAnalyzer(List<@Nullable CpEntry> cp, String thisClassName, MethodInfo m) {
			this.cp = cp;
			this.thisClassName = thisClassName;
			this.m = m;
			this.code = m.code;
			this.visited = new boolean[m.code.length];
		}

		MethodFrames analyze() {
			// Pass 1: instruction boundaries, branch targets, leaders.
			boolean[] isInstrStart = new boolean[this.code.length + 1];
			int pc = 0;
			while (pc < this.code.length) {
				isInstrStart[pc] = true;
				int op = this.code[pc] & 0xff;
				for (int target : branchTargets(pc, op)) {
					this.leaders.add(target);
					this.needsFrame.add(target);
				}
				int next = pc + 1 + operandLength(op, pc);
				if (isUnconditional(op) && next <= this.code.length) {
					if (next < this.code.length) {
						this.leaders.add(next);
						this.needsFrame.add(next);
					}
				}
				pc = next;
			}
			if (pc != this.code.length) {
				throw new IllegalStateException("bytecode overruns the code array");
			}
			for (ExcEntry e : this.m.exceptionTable) {
				this.leaders.add(e.handlerPc);
				this.needsFrame.add(e.handlerPc);
			}
			for (int leader : this.leaders) {
				if (leader >= this.code.length || !isInstrStart[leader]) {
					throw new IllegalStateException("branch target " + leader + " is not an instruction start");
				}
			}

			// Pass 2: worklist dataflow from the method entry frame.
			Deque<Integer> work = new ArrayDeque<>();
			this.leaderFrames.put(0, entryFrame());
			work.push(0);
			HashSet<Integer> queued = new HashSet<>();
			queued.add(0);
			while (!work.isEmpty()) {
				int leader = work.pop();
				queued.remove(leader);
				Frame frame = Objects.requireNonNull(this.leaderFrames.get(leader)).copy();
				int at = leader;
				while (true) {
					this.visited[at] = true;
					int op = this.code[at] & 0xff;
					// An exception thrown at this instruction reaches its handlers with
					// the locals as they are here and the thrown value as the stack.
					for (ExcEntry e : this.m.exceptionTable) {
						if (e.startPc <= at && at < e.endPc) {
							String caught = e.catchType == 0 ? THROWABLE_CLASS : className(this.cp, e.catchType);
							Frame handlerFrame = new Frame(cleanForHandler(frame.locals),
									new ArrayList<>(List.of(VType.object(caught))));
							if (mergeInto(e.handlerPc, handlerFrame) && queued.add(e.handlerPc)) {
								work.push(e.handlerPc);
							}
						}
					}
					int next = at + 1 + operandLength(op, at);
					interpret(frame, at, op);
					for (int target : branchTargets(at, op)) {
						if (mergeInto(target, frame) && queued.add(target)) {
							work.push(target);
						}
					}
					if (isUnconditional(op)) {
						break;
					}
					if (next >= this.code.length) {
						throw new IllegalStateException("execution falls off the end of the code array at " + at);
					}
					if (this.leaders.contains(next)) {
						if (mergeInto(next, frame) && queued.add(next)) {
							work.push(next);
						}
						break;
					}
					at = next;
				}
			}

			// Pass 3: neutralize dead code (nop* + athrow per maximal dead run) and give
			// every frame-needing position inside a dead run the synthetic frame.
			byte[] patched = this.code;
			List<int[]> deadRuns = new ArrayList<>();
			int runStart = -1;
			pc = 0;
			while (pc < this.code.length) {
				int len = 1 + operandLength(this.code[pc] & 0xff, pc);
				if (!this.visited[pc]) {
					if (runStart < 0) {
						runStart = pc;
					}
				}
				else if (runStart >= 0) {
					deadRuns.add(new int[] { runStart, pc });
					runStart = -1;
				}
				pc += len;
			}
			if (runStart >= 0) {
				deadRuns.add(new int[] { runStart, this.code.length });
			}
			Frame deadFrame = new Frame(new VType[0], new ArrayList<>(List.of(VType.object(THROWABLE_CLASS))));
			List<ExcEntry> exceptionTable = this.m.exceptionTable;
			if (!deadRuns.isEmpty()) {
				patched = this.code.clone();
				for (int[] run : deadRuns) {
					Arrays.fill(patched, run[0], run[1], (byte) Opcode.NOP);
					patched[run[1] - 1] = (byte) Opcode.ATHROW;
					this.needsFrame.add(run[0]);
					for (int needed : this.needsFrame.subSet(run[0], run[1])) {
						this.leaderFrames.put(needed, deadFrame);
					}
				}
				// A dead run's synthetic frame (no locals) cannot satisfy a live
				// handler's frame, so carve the dead ranges out of every protected
				// region -- the nop/athrow filler has nothing to protect anyway.
				exceptionTable = new ArrayList<>();
				for (ExcEntry e : this.m.exceptionTable) {
					List<int[]> ranges = new ArrayList<>();
					ranges.add(new int[] { e.startPc, e.endPc });
					for (int[] run : deadRuns) {
						List<int[]> next = new ArrayList<>();
						for (int[] range : ranges) {
							if (run[1] <= range[0] || range[1] <= run[0]) {
								next.add(range);
								continue;
							}
							if (range[0] < run[0]) {
								next.add(new int[] { range[0], run[0] });
							}
							if (run[1] < range[1]) {
								next.add(new int[] { run[1], range[1] });
							}
						}
						ranges = next;
					}
					for (int[] range : ranges) {
						exceptionTable.add(new ExcEntry(range[0], range[1], e.handlerPc, e.catchType));
					}
				}
			}

			// Collect the frames in offset order.
			List<FrameEntry> frames = new ArrayList<>();
			for (int needed : this.needsFrame) {
				Frame frame = this.leaderFrames.get(needed);
				if (frame == null) {
					throw new IllegalStateException("no frame computed for position " + needed);
				}
				frames.add(new FrameEntry(needed, frameLocals(frame), List.copyOf(frame.stack)));
			}
			return new MethodFrames(patched, exceptionTable, frames);
		}

		/**
		 * The frame's local_variable_types: wide types swallow their filler slot, and
		 * trailing TOPs are trimmed.
		 */
		private static List<VType> frameLocals(Frame frame) {
			List<VType> locals = new ArrayList<>();
			for (int i = 0; i < frame.locals.length; i++) {
				VType t = frame.locals[i];
				locals.add(t);
				if (t.wide()) {
					i++;
				}
			}
			while (!locals.isEmpty() && locals.get(locals.size() - 1) == VType.TOP) {
				locals.remove(locals.size() - 1);
			}
			return locals;
		}

		/** Uninitialized references cannot survive into a handler; they become TOP. */
		private static VType[] cleanForHandler(VType[] locals) {
			VType[] cleaned = locals.clone();
			for (int i = 0; i < cleaned.length; i++) {
				if (cleaned[i].kind == ITEM_UNINITIALIZED || cleaned[i].kind == ITEM_UNINITIALIZED_THIS) {
					cleaned[i] = VType.TOP;
				}
			}
			return cleaned;
		}

		private Frame entryFrame() {
			VType[] locals = new VType[this.m.maxLocals];
			Arrays.fill(locals, VType.TOP);
			int slot = 0;
			if ((this.m.access & AccessFlag.ACC_STATIC) == 0) {
				locals[slot++] = "<init>".equals(utf8(this.cp, this.m.nameIdx)) ? VType.UNINIT_THIS
						: VType.object(this.thisClassName);
			}
			String desc = utf8(this.cp, this.m.descIdx);
			for (VType param : parameterTypes(desc)) {
				locals[slot] = param;
				slot += param.wide() ? 2 : 1;
			}
			return new Frame(locals, new ArrayList<>());
		}

		/**
		 * Merges the incoming frame into the leader's entry frame.
		 * @return true when the leader's frame changed (it must be re-interpreted)
		 */
		private boolean mergeInto(int leader, Frame incoming) {
			Frame existing = this.leaderFrames.get(leader);
			if (existing == null) {
				this.leaderFrames.put(leader, incoming.copy());
				return true;
			}
			boolean changed = false;
			for (int i = 0; i < existing.locals.length; i++) {
				VType merged = mergeLocal(existing.locals[i], incoming.locals[i]);
				if (!merged.equals(existing.locals[i])) {
					existing.locals[i] = merged;
					changed = true;
				}
			}
			if (existing.stack.size() != incoming.stack.size()) {
				throw new IllegalStateException(
						"operand stack depth mismatch at " + leader + ": " + existing.stack + " vs " + incoming.stack);
			}
			for (int i = 0; i < existing.stack.size(); i++) {
				VType merged = mergeStack(existing.stack.get(i), incoming.stack.get(i), leader);
				if (!merged.equals(existing.stack.get(i))) {
					existing.stack.set(i, merged);
					changed = true;
				}
			}
			return changed;
		}

		/** Local-variable merge: a kind mismatch simply makes the slot unusable (TOP). */
		private static VType mergeLocal(VType a, VType b) {
			if (a.equals(b)) {
				return a;
			}
			if (a.kind == ITEM_NULL && b.kind == ITEM_OBJECT) {
				return b;
			}
			if (b.kind == ITEM_NULL && a.kind == ITEM_OBJECT) {
				return a;
			}
			if (a.kind == ITEM_OBJECT && b.kind == ITEM_OBJECT) {
				return VType.object(commonSuperclass(Objects.requireNonNull(a.cls), Objects.requireNonNull(b.cls)));
			}
			return VType.TOP;
		}

		/** Operand-stack merge: a kind mismatch is a code-generator bug. */
		private static VType mergeStack(VType a, VType b, int leader) {
			if (a.equals(b)) {
				return a;
			}
			if (a.kind == ITEM_NULL && b.isReference()) {
				return b;
			}
			if (b.kind == ITEM_NULL && a.isReference()) {
				return a;
			}
			if (a.kind == ITEM_OBJECT && b.kind == ITEM_OBJECT) {
				return VType.object(commonSuperclass(Objects.requireNonNull(a.cls), Objects.requireNonNull(b.cls)));
			}
			throw new IllegalStateException("operand stack type mismatch at " + leader + ": " + a + " vs " + b);
		}

		private static String commonSuperclass(String a, String b) {
			List<String> chainA = superChain(a);
			Set<String> chainB = new java.util.HashSet<>(superChain(b));
			for (String s : chainA) {
				if (chainB.contains(s)) {
					return s;
				}
			}
			return OBJECT_CLASS;
		}

		private static List<String> superChain(String cls) {
			List<String> chain = new ArrayList<>();
			String at = cls;
			while (at != null && !OBJECT_CLASS.equals(at)) {
				chain.add(at);
				at = SUPERCLASS.get(at);
			}
			chain.add(OBJECT_CLASS);
			return chain;
		}

		// --- The transfer function ---

		private void interpret(Frame frame, int pc, int op) {
			switch (op) {
				case Opcode.NOP, Opcode.INEG, Opcode.LNEG, Opcode.FNEG, Opcode.DNEG, Opcode.IINC, Opcode.I2B,
						Opcode.I2C, Opcode.I2S, Opcode.GOTO, Opcode.GOTO_W, Opcode.RETURN ->
					{
					}
				case Opcode.ACONST_NULL -> push(frame, VType.NULL);
				case Opcode.NEW -> push(frame, VType.uninitialized(pc));
				case Opcode.ICONST_M1, Opcode.ICONST_0, Opcode.ICONST_1, Opcode.ICONST_2, Opcode.ICONST_3,
						Opcode.ICONST_4, Opcode.ICONST_5, Opcode.BIPUSH, Opcode.SIPUSH ->
					push(frame, VType.INT);
				case Opcode.LCONST_0, Opcode.LCONST_1 -> push(frame, VType.LONG);
				case Opcode.FCONST_0, Opcode.FCONST_1, Opcode.FCONST_2 -> push(frame, VType.FLOAT);
				case Opcode.DCONST_0, Opcode.DCONST_1 -> push(frame, VType.DOUBLE);
				case Opcode.LDC -> push(frame, constantType(this.code[pc + 1] & 0xff));
				case Opcode.LDC_W, Opcode.LDC2_W -> push(frame, constantType(u2At(pc + 1)));
				case Opcode.ILOAD -> push(frame, VType.INT);
				case Opcode.LLOAD -> push(frame, VType.LONG);
				case Opcode.FLOAD -> push(frame, VType.FLOAT);
				case Opcode.DLOAD -> push(frame, VType.DOUBLE);
				case Opcode.ALOAD -> push(frame, refLocal(frame, this.code[pc + 1] & 0xff));
				case Opcode.ILOAD_0, Opcode.ILOAD_1, Opcode.ILOAD_2, Opcode.ILOAD_3 -> push(frame, VType.INT);
				case Opcode.LLOAD_0, Opcode.LLOAD_1, Opcode.LLOAD_2, Opcode.LLOAD_3 -> push(frame, VType.LONG);
				case Opcode.FLOAD_0, Opcode.FLOAD_1, Opcode.FLOAD_2, Opcode.FLOAD_3 -> push(frame, VType.FLOAT);
				case Opcode.DLOAD_0, Opcode.DLOAD_1, Opcode.DLOAD_2, Opcode.DLOAD_3 -> push(frame, VType.DOUBLE);
				case Opcode.ALOAD_0, Opcode.ALOAD_1, Opcode.ALOAD_2, Opcode.ALOAD_3 ->
					push(frame, refLocal(frame, op - Opcode.ALOAD_0));
				case Opcode.ISTORE, Opcode.LSTORE, Opcode.FSTORE, Opcode.DSTORE, Opcode.ASTORE ->
					store(frame, this.code[pc + 1] & 0xff);
				case Opcode.ISTORE_0, Opcode.ISTORE_1, Opcode.ISTORE_2, Opcode.ISTORE_3 ->
					store(frame, op - Opcode.ISTORE_0);
				case Opcode.LSTORE_0, Opcode.LSTORE_1, Opcode.LSTORE_2, Opcode.LSTORE_3 ->
					store(frame, op - Opcode.LSTORE_0);
				case Opcode.FSTORE_0, Opcode.FSTORE_1, Opcode.FSTORE_2, Opcode.FSTORE_3 ->
					store(frame, op - Opcode.FSTORE_0);
				case Opcode.DSTORE_0, Opcode.DSTORE_1, Opcode.DSTORE_2, Opcode.DSTORE_3 ->
					store(frame, op - Opcode.DSTORE_0);
				case Opcode.ASTORE_0, Opcode.ASTORE_1, Opcode.ASTORE_2, Opcode.ASTORE_3 ->
					store(frame, op - Opcode.ASTORE_0);
				case Opcode.IALOAD, Opcode.BALOAD, Opcode.CALOAD, Opcode.SALOAD -> arrayLoad(frame, VType.INT);
				case Opcode.LALOAD -> arrayLoad(frame, VType.LONG);
				case Opcode.FALOAD -> arrayLoad(frame, VType.FLOAT);
				case Opcode.DALOAD -> arrayLoad(frame, VType.DOUBLE);
				case Opcode.AALOAD -> {
					pop(frame); // index
					VType array = pop(frame);
					push(frame, elementType(array, pc));
				}
				case Opcode.IASTORE, Opcode.LASTORE, Opcode.FASTORE, Opcode.DASTORE, Opcode.AASTORE, Opcode.BASTORE,
						Opcode.CASTORE, Opcode.SASTORE -> {
					pop(frame);
					pop(frame);
					pop(frame);
				}
				case Opcode.POP -> pop(frame);
				case Opcode.POP2 -> popSlots(frame, 2);
				case Opcode.DUP -> duplicate(frame, 1, 0);
				case Opcode.DUP_X1 -> duplicate(frame, 1, 1);
				case Opcode.DUP_X2 -> duplicate(frame, 1, 2);
				case Opcode.DUP2 -> duplicate(frame, 2, 0);
				case Opcode.DUP2_X1 -> duplicate(frame, 2, 1);
				case Opcode.DUP2_X2 -> duplicate(frame, 2, 2);
				case Opcode.SWAP -> {
					VType top = pop(frame);
					VType below = pop(frame);
					push(frame, top);
					push(frame, below);
				}
				case Opcode.IADD, Opcode.ISUB, Opcode.IMUL, Opcode.IDIV, Opcode.IREM, Opcode.IAND, Opcode.IOR,
						Opcode.IXOR, Opcode.ISHL, Opcode.ISHR, Opcode.IUSHR -> {
					pop(frame);
					pop(frame);
					push(frame, VType.INT);
				}
				case Opcode.LADD, Opcode.LSUB, Opcode.LMUL, Opcode.LDIV, Opcode.LREM, Opcode.LAND, Opcode.LOR,
						Opcode.LXOR, Opcode.LSHL, Opcode.LSHR, Opcode.LUSHR -> {
					pop(frame);
					pop(frame);
					push(frame, VType.LONG);
				}
				case Opcode.FADD, Opcode.FSUB, Opcode.FMUL, Opcode.FDIV, Opcode.FREM -> {
					pop(frame);
					pop(frame);
					push(frame, VType.FLOAT);
				}
				case Opcode.DADD, Opcode.DSUB, Opcode.DMUL, Opcode.DDIV, Opcode.DREM -> {
					pop(frame);
					pop(frame);
					push(frame, VType.DOUBLE);
				}
				case Opcode.I2L, Opcode.F2L, Opcode.D2L -> convert(frame, VType.LONG);
				case Opcode.I2F, Opcode.L2F, Opcode.D2F -> convert(frame, VType.FLOAT);
				case Opcode.I2D, Opcode.L2D, Opcode.F2D -> convert(frame, VType.DOUBLE);
				case Opcode.L2I, Opcode.F2I, Opcode.D2I -> convert(frame, VType.INT);
				case Opcode.LCMP, Opcode.FCMPL, Opcode.FCMPG, Opcode.DCMPL, Opcode.DCMPG -> {
					pop(frame);
					pop(frame);
					push(frame, VType.INT);
				}
				case Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT, Opcode.IFGE, Opcode.IFGT, Opcode.IFLE, Opcode.IFNULL,
						Opcode.IFNONNULL ->
					pop(frame);
				case Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT, Opcode.IF_ICMPGE, Opcode.IF_ICMPGT,
						Opcode.IF_ICMPLE, Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE -> {
					pop(frame);
					pop(frame);
				}
				case Opcode.IRETURN, Opcode.LRETURN, Opcode.FRETURN, Opcode.DRETURN, Opcode.ARETURN, Opcode.ATHROW ->
					pop(frame);
				case Opcode.GETSTATIC -> push(frame, fieldType(refDescriptor(u2At(pc + 1))));
				case Opcode.PUTSTATIC -> pop(frame);
				case Opcode.GETFIELD -> {
					pop(frame);
					push(frame, fieldType(refDescriptor(u2At(pc + 1))));
				}
				case Opcode.PUTFIELD -> {
					pop(frame);
					pop(frame);
				}
				case Opcode.INVOKEVIRTUAL, Opcode.INVOKEINTERFACE -> invoke(frame, u2At(pc + 1), true, false);
				case Opcode.INVOKESPECIAL -> invoke(frame, u2At(pc + 1), true, true);
				case Opcode.INVOKESTATIC -> invoke(frame, u2At(pc + 1), false, false);
				case Opcode.NEWARRAY -> {
					pop(frame);
					push(frame, VType.object(primitiveArrayName(this.code[pc + 1] & 0xff)));
				}
				case Opcode.ANEWARRAY -> {
					pop(frame);
					String component = className(this.cp, u2At(pc + 1));
					push(frame, VType.object(component.startsWith("[") ? "[" + component : "[L" + component + ";"));
				}
				case Opcode.CHECKCAST -> {
					pop(frame);
					push(frame, VType.object(className(this.cp, u2At(pc + 1))));
				}
				case Opcode.INSTANCEOF, Opcode.ARRAYLENGTH -> {
					pop(frame);
					push(frame, VType.INT);
				}
				default ->
					throw new IllegalStateException("unsupported opcode 0x" + Integer.toHexString(op) + " at " + pc);
			}
		}

		private void invoke(Frame frame, int refIdx, boolean hasReceiver, boolean special) {
			String descriptor = refDescriptor(refIdx);
			List<VType> params = parameterTypes(descriptor);
			for (int i = params.size() - 1; i >= 0; i--) {
				pop(frame);
			}
			if (hasReceiver) {
				VType receiver = pop(frame);
				if (special && isInitRef(refIdx)) {
					VType initialized = receiver.kind == ITEM_UNINITIALIZED_THIS ? VType.object(this.thisClassName)
							: VType.object(refClassName(refIdx));
					replaceAll(frame, receiver, initialized);
				}
			}
			VType returned = returnType(descriptor);
			if (returned != null) {
				push(frame, returned);
			}
		}

		/**
		 * The constructor call turns every copy of the uninitialized value initialized.
		 */
		private static void replaceAll(Frame frame, VType from, VType to) {
			for (int i = 0; i < frame.locals.length; i++) {
				if (frame.locals[i].equals(from)) {
					frame.locals[i] = to;
				}
			}
			frame.stack.replaceAll(t -> t.equals(from) ? to : t);
		}

		private VType elementType(VType array, int pc) {
			if (array.kind == ITEM_NULL) {
				return VType.NULL;
			}
			String cls = array.kind == ITEM_OBJECT ? array.cls : null;
			if (cls == null || !cls.startsWith("[")) {
				throw new IllegalStateException(
						"aaload at " + pc + " on non-array type " + array + " (a reference merge was too lossy)");
			}
			String component = cls.substring(1);
			if (component.startsWith("L")) {
				return VType.object(component.substring(1, component.length() - 1));
			}
			if (component.startsWith("[")) {
				return VType.object(component);
			}
			throw new IllegalStateException("aaload at " + pc + " on primitive array " + cls);
		}

		private static String primitiveArrayName(int atype) {
			return switch (atype) {
				case ArrayType.T_BOOLEAN -> "[Z";
				case ArrayType.T_CHAR -> "[C";
				case ArrayType.T_FLOAT -> "[F";
				case ArrayType.T_DOUBLE -> "[D";
				case ArrayType.T_BYTE -> "[B";
				case ArrayType.T_SHORT -> "[S";
				case ArrayType.T_INT -> "[I";
				case ArrayType.T_LONG -> "[J";
				default -> throw new IllegalStateException("unknown newarray atype " + atype);
			};
		}

		private void arrayLoad(Frame frame, VType loaded) {
			pop(frame);
			pop(frame);
			push(frame, loaded);
		}

		private void convert(Frame frame, VType to) {
			pop(frame);
			push(frame, to);
		}

		private VType refLocal(Frame frame, int index) {
			VType t = frame.locals[index];
			if (!t.isReference()) {
				throw new IllegalStateException("aload of non-reference local " + index + ": " + t);
			}
			return t;
		}

		private void store(Frame frame, int index) {
			VType value = pop(frame);
			// Overwriting the second slot of a wide value invalidates the wide value.
			if (index > 0 && frame.locals[index - 1].wide()) {
				frame.locals[index - 1] = VType.TOP;
			}
			frame.locals[index] = value;
			if (value.wide() && index + 1 < frame.locals.length) {
				frame.locals[index + 1] = VType.TOP;
			}
		}

		private void push(Frame frame, VType t) {
			frame.stack.add(t);
		}

		private VType pop(Frame frame) {
			if (frame.stack.isEmpty()) {
				throw new IllegalStateException("operand stack underflow");
			}
			return frame.stack.remove(frame.stack.size() - 1);
		}

		private void popSlots(Frame frame, int slots) {
			int remaining = slots;
			while (remaining > 0) {
				remaining -= pop(frame).wide() ? 2 : 1;
			}
		}

		private void duplicate(Frame frame, int topSlots, int underSlots) {
			List<VType> top = take(frame, topSlots);
			List<VType> under = take(frame, underSlots);
			top.forEach(t -> push(frame, t));
			under.forEach(t -> push(frame, t));
			top.forEach(t -> push(frame, t));
		}

		private List<VType> take(Frame frame, int slots) {
			List<VType> taken = new ArrayList<>();
			int remaining = slots;
			while (remaining > 0) {
				VType t = pop(frame);
				taken.add(0, t);
				remaining -= t.wide() ? 2 : 1;
			}
			return taken;
		}

		// --- Constant pool and descriptor helpers ---

		private int u2At(int offset) {
			return (this.code[offset] & 0xff) << 8 | (this.code[offset + 1] & 0xff);
		}

		private VType constantType(int cpIdx) {
			CpEntry e = entry(this.cp, cpIdx);
			return switch (e.tag) {
				case TAG_INTEGER -> VType.INT;
				case TAG_FLOAT -> VType.FLOAT;
				case TAG_LONG -> VType.LONG;
				case TAG_DOUBLE -> VType.DOUBLE;
				case TAG_STRING -> VType.object("java/lang/String");
				case TAG_CLASS -> VType.object("java/lang/Class");
				default -> throw new IllegalStateException("ldc of unsupported constant tag " + e.tag);
			};
		}

		private String refDescriptor(int refIdx) {
			CpEntry ref = entry(this.cp, refIdx);
			CpEntry nat = entry(this.cp, u2(ref.body, 2));
			return utf8(this.cp, u2(nat.body, 2));
		}

		private boolean isInitRef(int refIdx) {
			CpEntry ref = entry(this.cp, refIdx);
			CpEntry nat = entry(this.cp, u2(ref.body, 2));
			return "<init>".equals(utf8(this.cp, u2(nat.body, 0)));
		}

		private String refClassName(int refIdx) {
			CpEntry ref = entry(this.cp, refIdx);
			return className(this.cp, u2(ref.body, 0));
		}

		private static List<VType> parameterTypes(String descriptor) {
			List<VType> params = new ArrayList<>();
			int i = 1;
			while (descriptor.charAt(i) != ')') {
				int start = i;
				while (descriptor.charAt(i) == '[') {
					i++;
				}
				if (descriptor.charAt(i) == 'L') {
					i = descriptor.indexOf(';', i) + 1;
				}
				else {
					i++;
				}
				params.add(descriptorType(descriptor.substring(start, i)));
			}
			return params;
		}

		private static @Nullable VType returnType(String descriptor) {
			String returned = descriptor.substring(descriptor.indexOf(')') + 1);
			return "V".equals(returned) ? null : descriptorType(returned);
		}

		private static VType fieldType(String descriptor) {
			return descriptorType(descriptor);
		}

		private static VType descriptorType(String descriptor) {
			return switch (descriptor.charAt(0)) {
				case 'J' -> VType.LONG;
				case 'D' -> VType.DOUBLE;
				case 'F' -> VType.FLOAT;
				case 'I', 'Z', 'B', 'C', 'S' -> VType.INT;
				case '[' -> VType.object(descriptor);
				case 'L' -> VType.object(descriptor.substring(1, descriptor.length() - 1));
				default -> throw new IllegalStateException("bad descriptor " + descriptor);
			};
		}

		// --- Instruction shape ---

		private List<Integer> branchTargets(int pc, int op) {
			if ((op >= Opcode.IFEQ && op <= Opcode.IF_ACMPNE) || op == Opcode.GOTO || op == Opcode.IFNULL
					|| op == Opcode.IFNONNULL) {
				int offset = (short) u2At(pc + 1);
				return List.of(pc + offset);
			}
			if (op == Opcode.GOTO_W) {
				int offset = (this.code[pc + 1] & 0xff) << 24 | (this.code[pc + 2] & 0xff) << 16
						| (this.code[pc + 3] & 0xff) << 8 | (this.code[pc + 4] & 0xff);
				return List.of(pc + offset);
			}
			return List.of();
		}

		private static boolean isUnconditional(int op) {
			return op == Opcode.GOTO || op == Opcode.GOTO_W || op == Opcode.ATHROW
					|| (op >= Opcode.IRETURN && op <= Opcode.RETURN);
		}

		/** Operand byte count of the instruction whose opcode is at {@code pc}. */
		private int operandLength(int op, int pc) {
			return switch (op) {
				case Opcode.BIPUSH, Opcode.LDC, Opcode.ILOAD, Opcode.LLOAD, Opcode.FLOAD, Opcode.DLOAD, Opcode.ALOAD,
						Opcode.ISTORE, Opcode.LSTORE, Opcode.FSTORE, Opcode.DSTORE, Opcode.ASTORE, Opcode.NEWARRAY ->
					1;
				case Opcode.SIPUSH, Opcode.LDC_W, Opcode.LDC2_W, Opcode.IINC, Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT,
						Opcode.IFGE, Opcode.IFGT, Opcode.IFLE, Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT,
						Opcode.IF_ICMPGE, Opcode.IF_ICMPGT, Opcode.IF_ICMPLE, Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE,
						Opcode.GOTO, Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.GETFIELD, Opcode.PUTFIELD,
						Opcode.INVOKEVIRTUAL, Opcode.INVOKESPECIAL, Opcode.INVOKESTATIC, Opcode.NEW, Opcode.ANEWARRAY,
						Opcode.CHECKCAST, Opcode.INSTANCEOF, Opcode.IFNULL, Opcode.IFNONNULL ->
					2;
				case Opcode.MULTIANEWARRAY -> 3;
				case Opcode.INVOKEINTERFACE, Opcode.GOTO_W -> 4;
				case Opcode.TABLESWITCH, Opcode.LOOKUPSWITCH, Opcode.WIDE, Opcode.JSR, Opcode.JSR_W, Opcode.RET ->
					throw new IllegalStateException("unsupported opcode 0x" + Integer.toHexString(op) + " at " + pc);
				default -> 0;
			};
		}

	}

	// --- Shared byte and constant pool helpers ---

	private static CpEntry entry(List<@Nullable CpEntry> cp, int idx) {
		CpEntry e = idx > 0 && idx < cp.size() ? cp.get(idx) : null;
		if (e == null) {
			throw new IllegalStateException("StackMapAugmenter: invalid constant pool index " + idx);
		}
		return e;
	}

	private static String utf8(List<@Nullable CpEntry> cp, int idx) {
		CpEntry e = entry(cp, idx);
		if (e.tag != TAG_UTF8) {
			throw new IllegalStateException("StackMapAugmenter: constant " + idx + " is not Utf8");
		}
		try {
			return new DataInputStream(new ByteArrayInputStream(e.body)).readUTF();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String className(List<@Nullable CpEntry> cp, int classIdx) {
		return utf8(cp, u2(entry(cp, classIdx).body, 0));
	}

	private static int u2(byte[] buf, int off) {
		return (buf[off] & 0xff) << 8 | (buf[off + 1] & 0xff);
	}

	private static int readU2(byte[] buf, int[] p) {
		int v = u2(buf, p[0]);
		p[0] += 2;
		return v;
	}

	private static int readU4(byte[] buf, int[] p) {
		int v = (buf[p[0]] & 0xff) << 24 | (buf[p[0] + 1] & 0xff) << 16 | (buf[p[0] + 2] & 0xff) << 8
				| (buf[p[0] + 3] & 0xff);
		p[0] += 4;
		return v;
	}

	private static void writeU2(ByteArrayOutputStream out, int v) {
		out.write((v >>> 8) & 0xff);
		out.write(v & 0xff);
	}

	private static void writeU4(ByteArrayOutputStream out, int v) {
		out.write((v >>> 24) & 0xff);
		out.write((v >>> 16) & 0xff);
		out.write((v >>> 8) & 0xff);
		out.write(v & 0xff);
	}

}
