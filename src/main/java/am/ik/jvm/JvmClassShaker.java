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
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Language-independent dead-code eliminator (tree shaker) for a JVM class file, the JVM
 * counterpart of {@link am.ik.wasm.WasmTreeShaker}.
 * <p>
 * The rontolisp JVM code generator emits every runtime helper method (print / numeric /
 * reader / eval helpers, built-in function wrappers, ...) unconditionally, so a compiled
 * class embeds the whole runtime even when the program uses almost none of it. This pass
 * removes that bloat: it builds a call graph from the actual {@code invoke*}
 * constant-pool immediates in every method body, computes the set of methods reachable
 * from the class's roots (given by name, e.g. {@code main}), drops the rest along with
 * any static field no surviving method references, and compacts the constant pool,
 * rewriting every constant-pool index in the surviving bytecode. Unlike the WASM pass no
 * renumbering of the methods themselves is needed (JVM methods are referenced by name),
 * but constant-pool entries are referenced by index, so the pool compaction rewrites each
 * instruction's index immediates in place (sizes never change: a u2 stays a u2, and an
 * {@code ldc} u1 index only ever shrinks because compaction preserves order).
 * <p>
 * The pass is purely additive and opt-in ({@code --optimize}); it never runs on the
 * default deterministic output.
 * <p>
 * Correctness rests on properties of the rontolisp output that this class verifies by
 * construction: methods carry exactly one {@code Code} attribute with no nested
 * attributes, fields and the class itself carry none (no StackMapTable at class version
 * 50, no {@code invokedynamic}), and every constant-pool tag and instruction is in the
 * finite set enumerated here. Anything unrecognized makes the pass throw rather than
 * silently emit a corrupt class. Dynamically-reached methods stay alive the same way they
 * do on WASM: first-class calls go through dispatch methods whose bodies contain real
 * {@code invokestatic}s to every registered function. The one edge invisible to bytecode
 * is a reflective call by name; the caller lists such methods as extra roots (rontolisp:
 * {@code _apply}, looked up reflectively by the embedded {@code java:} bridge).
 */
public final class JvmClassShaker {

	private JvmClassShaker() {
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

	/** A constant pool entry: its tag and its body bytes (everything after the tag). */
	private record CpEntry(int tag, byte[] body) {
	}

	private record FieldInfo(int access, int nameIdx, int descIdx) {
	}

	private record ExcEntry(int startPc, int endPc, int handlerPc, int catchType) {
	}

	/**
	 * A parsed method: its header indices and single {@code Code} attribute, plus the
	 * derived facts the shaker needs (every constant-pool index site in the bytecode, and
	 * the this-class method/field keys it references).
	 */
	private record MethodInfo(int access, int nameIdx, int descIdx, int codeAttrNameIdx, int maxStack, int maxLocals,
			byte[] code, List<ExcEntry> exceptionTable, List<CpSite> cpSites) {
	}

	/** A constant-pool index immediate within a method body: offset and byte width. */
	private record CpSite(int offset, int width) {
	}

	/**
	 * Removes methods unreachable from the given roots, drops fields no surviving method
	 * references, and compacts the constant pool.
	 * @param classFile a JVM class file as produced by {@link ByteCodeWriter}
	 * @param rootMethodNames names of the entry-point methods to keep (with everything
	 * they transitively reach); {@code <init>}/{@code <clinit>} are always kept
	 * @return an equivalent class file with dead methods removed; the input is returned
	 * unchanged when nothing can be dropped
	 */
	public static byte[] shake(byte[] classFile, Set<String> rootMethodNames) {
		int[] p = { 0 };
		int magic = readU4(classFile, p);
		if (magic != 0xCAFEBABE) {
			throw new IllegalStateException("JvmClassShaker: not a class file");
		}
		int minor = readU2(classFile, p);
		int major = readU2(classFile, p);

		// Constant pool. Long/Double entries occupy two slots; the second is left null.
		int cpCount = readU2(classFile, p);
		@Nullable CpEntry[] cp = new CpEntry[cpCount];
		for (int i = 1; i < cpCount; i++) {
			int tag = classFile[p[0]++] & 0xff;
			int bodyLen = switch (tag) {
				case TAG_UTF8 -> 2 + ((classFile[p[0]] & 0xff) << 8 | (classFile[p[0] + 1] & 0xff));
				case TAG_INTEGER, TAG_FLOAT, TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE ->
					4;
				case TAG_LONG, TAG_DOUBLE -> 8;
				case TAG_CLASS, TAG_STRING -> 2;
				default -> throw new IllegalStateException("JvmClassShaker: unhandled constant tag " + tag);
			};
			cp[i] = new CpEntry(tag, slice(classFile, p[0], p[0] + bodyLen));
			p[0] += bodyLen;
			if (tag == TAG_LONG || tag == TAG_DOUBLE) {
				i++; // second slot stays null
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
				throw new IllegalStateException("JvmClassShaker: unsupported field attribute");
			}
			fields.add(new FieldInfo(access, nameIdx, descIdx));
		}

		int methodsCount = readU2(classFile, p);
		List<MethodInfo> methods = new ArrayList<>(methodsCount);
		for (int i = 0; i < methodsCount; i++) {
			methods.add(parseMethod(classFile, p, cp));
		}

		int classAttrCount = readU2(classFile, p);
		if (classAttrCount != 0) {
			throw new IllegalStateException("JvmClassShaker: unsupported class attribute");
		}
		if (p[0] != classFile.length) {
			throw new IllegalStateException("JvmClassShaker: trailing bytes after class structure");
		}

		String thisClassName = className(cp, thisIdx);

		// Call graph: this-class method keys (name + desc) each method's body references,
		// via invokestatic/invokevirtual/invokespecial/invokeinterface immediates.
		Map<String, Integer> methodByKey = new HashMap<>();
		for (int i = 0; i < methods.size(); i++) {
			MethodInfo m = methods.get(i);
			methodByKey.put(methodKey(cp, m.nameIdx, m.descIdx), i);
		}

		// Reachability from the named roots (plus <init>/<clinit>, always entry points).
		boolean[] reachableMethod = new boolean[methods.size()];
		Deque<Integer> work = new ArrayDeque<>();
		for (int i = 0; i < methods.size(); i++) {
			String name = utf8(cp, methods.get(i).nameIdx);
			if (rootMethodNames.contains(name) || "<init>".equals(name) || "<clinit>".equals(name)) {
				reachableMethod[i] = true;
				work.push(i);
			}
		}
		while (!work.isEmpty()) {
			MethodInfo m = methods.get(work.pop());
			for (CpSite site : m.cpSites) {
				int idx = readIndex(m.code, site);
				CpEntry e = entry(cp, idx);
				if (e.tag != TAG_METHODREF && e.tag != TAG_INTERFACE_METHODREF) {
					continue;
				}
				if (!thisClassName.equals(className(cp, u2(e.body, 0)))) {
					continue;
				}
				Integer target = methodByKey.get(refKey(cp, e));
				if (target != null && !reachableMethod[target]) {
					reachableMethod[target] = true;
					work.push(target);
				}
			}
		}

		// A field survives when a surviving method's body references it.
		Set<String> usedFieldKeys = new HashSet<>();
		for (int i = 0; i < methods.size(); i++) {
			if (!reachableMethod[i]) {
				continue;
			}
			MethodInfo m = methods.get(i);
			for (CpSite site : m.cpSites) {
				CpEntry e = entry(cp, readIndex(m.code, site));
				if (e.tag == TAG_FIELDREF && thisClassName.equals(className(cp, u2(e.body, 0)))) {
					usedFieldKeys.add(refKey(cp, e));
				}
			}
		}
		boolean[] keptField = new boolean[fields.size()];
		for (int i = 0; i < fields.size(); i++) {
			FieldInfo f = fields.get(i);
			keptField[i] = usedFieldKeys.contains(methodKey(cp, f.nameIdx, f.descIdx));
		}

		// Mark every constant-pool entry the surviving structure references, closing over
		// Class -> Utf8, String -> Utf8, refs -> Class + NameAndType, NameAndType ->
		// Utf8s.
		boolean[] marked = new boolean[cpCount];
		markCp(cp, marked, thisIdx);
		markCp(cp, marked, superIdx);
		for (int idx : interfaces) {
			markCp(cp, marked, idx);
		}
		for (int i = 0; i < fields.size(); i++) {
			if (keptField[i]) {
				markCp(cp, marked, fields.get(i).nameIdx);
				markCp(cp, marked, fields.get(i).descIdx);
			}
		}
		for (int i = 0; i < methods.size(); i++) {
			if (!reachableMethod[i]) {
				continue;
			}
			MethodInfo m = methods.get(i);
			markCp(cp, marked, m.nameIdx);
			markCp(cp, marked, m.descIdx);
			markCp(cp, marked, m.codeAttrNameIdx);
			for (CpSite site : m.cpSites) {
				markCp(cp, marked, readIndex(m.code, site));
			}
			for (ExcEntry e : m.exceptionTable) {
				if (e.catchType != 0) {
					markCp(cp, marked, e.catchType);
				}
			}
		}

		// Old constant-pool index -> new index, preserving order (Long/Double keep their
		// phantom second slot).
		int[] remap = new int[cpCount];
		int next = 1;
		for (int i = 1; i < cpCount; i++) {
			CpEntry e = cp[i];
			if (e == null || !marked[i]) {
				continue;
			}
			remap[i] = next;
			next += (e.tag == TAG_LONG || e.tag == TAG_DOUBLE) ? 2 : 1;
		}
		int newCpCount = next;

		// Reassemble.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeU4(out, 0xCAFEBABE);
		writeU2(out, minor);
		writeU2(out, major);
		writeU2(out, newCpCount);
		for (int i = 1; i < cpCount; i++) {
			CpEntry e = cp[i];
			if (e == null || !marked[i]) {
				continue;
			}
			out.write(e.tag);
			switch (e.tag) {
				case TAG_CLASS, TAG_STRING -> writeU2(out, remap[u2(e.body, 0)]);
				case TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE -> {
					writeU2(out, remap[u2(e.body, 0)]);
					writeU2(out, remap[u2(e.body, 2)]);
				}
				default -> writeRaw(out, e.body);
			}
		}
		writeU2(out, accessFlags);
		writeU2(out, remap[thisIdx]);
		writeU2(out, remap[superIdx]);
		writeU2(out, interfaces.length);
		for (int idx : interfaces) {
			writeU2(out, remap[idx]);
		}
		int keptFieldCount = 0;
		for (boolean k : keptField) {
			if (k) {
				keptFieldCount++;
			}
		}
		writeU2(out, keptFieldCount);
		for (int i = 0; i < fields.size(); i++) {
			if (!keptField[i]) {
				continue;
			}
			FieldInfo f = fields.get(i);
			writeU2(out, f.access);
			writeU2(out, remap[f.nameIdx]);
			writeU2(out, remap[f.descIdx]);
			writeU2(out, 0);
		}
		int keptMethodCount = 0;
		for (boolean r : reachableMethod) {
			if (r) {
				keptMethodCount++;
			}
		}
		writeU2(out, keptMethodCount);
		for (int i = 0; i < methods.size(); i++) {
			if (!reachableMethod[i]) {
				continue;
			}
			MethodInfo m = methods.get(i);
			writeU2(out, m.access);
			writeU2(out, remap[m.nameIdx]);
			writeU2(out, remap[m.descIdx]);
			writeU2(out, 1); // the single Code attribute
			writeU2(out, remap[m.codeAttrNameIdx]);
			// max_stack + max_locals + code_length + code + exception_table_length +
			// entries + attributes_count; the byte size never changes, so exception-table
			// pc offsets and switch padding stay valid.
			writeU4(out, 2 + 2 + 4 + m.code.length + 2 + 8 * m.exceptionTable.size() + 2);
			writeU2(out, m.maxStack);
			writeU2(out, m.maxLocals);
			writeU4(out, m.code.length);
			writeRaw(out, rewriteCode(m.code, m.cpSites, remap));
			writeU2(out, m.exceptionTable.size());
			for (ExcEntry e : m.exceptionTable) {
				writeU2(out, e.startPc);
				writeU2(out, e.endPc);
				writeU2(out, e.handlerPc);
				writeU2(out, e.catchType == 0 ? 0 : remap[e.catchType]);
			}
			writeU2(out, 0);
		}
		writeU2(out, 0); // class attributes

		byte[] result = out.toByteArray();
		return Arrays.equals(result, classFile) ? classFile : result;
	}

	// --- Method / Code attribute parsing ---

	private static MethodInfo parseMethod(byte[] classFile, int[] p, @Nullable CpEntry[] cp) {
		int access = readU2(classFile, p);
		int nameIdx = readU2(classFile, p);
		int descIdx = readU2(classFile, p);
		int attrCount = readU2(classFile, p);
		if (attrCount != 1) {
			throw new IllegalStateException("JvmClassShaker: expected exactly one method attribute, got " + attrCount);
		}
		int attrNameIdx = readU2(classFile, p);
		if (!"Code".equals(utf8(cp, attrNameIdx))) {
			throw new IllegalStateException("JvmClassShaker: unsupported method attribute " + utf8(cp, attrNameIdx));
		}
		readU4(classFile, p); // attribute_length (recomputed on write)
		int maxStack = readU2(classFile, p);
		int maxLocals = readU2(classFile, p);
		int codeLen = readU4(classFile, p);
		byte[] code = slice(classFile, p[0], p[0] + codeLen);
		p[0] += codeLen;
		int excCount = readU2(classFile, p);
		List<ExcEntry> exceptionTable = new ArrayList<>(excCount);
		for (int i = 0; i < excCount; i++) {
			exceptionTable.add(new ExcEntry(readU2(classFile, p), readU2(classFile, p), readU2(classFile, p),
					readU2(classFile, p)));
		}
		int codeAttrCount = readU2(classFile, p);
		if (codeAttrCount != 0) {
			throw new IllegalStateException("JvmClassShaker: unsupported Code sub-attribute");
		}
		return new MethodInfo(access, nameIdx, descIdx, attrNameIdx, maxStack, maxLocals, code, exceptionTable,
				scanCpSites(code));
	}

	// --- Instruction scanning ---

	/**
	 * Walks the bytecode and returns every constant-pool index immediate. Every
	 * CP-bearing instruction must be enumerated here (the rewrite pass patches exactly
	 * these sites); an unrecognized opcode throws rather than risk a stale index.
	 */
	private static List<CpSite> scanCpSites(byte[] code) {
		List<CpSite> sites = new ArrayList<>();
		int pc = 0;
		while (pc < code.length) {
			int op = code[pc] & 0xff;
			int operand = pc + 1;
			switch (op) {
				case 0x12 -> { // ldc: u1 index
					sites.add(new CpSite(operand, 1));
					pc = operand + 1;
				}
				case 0x13, 0x14 -> { // ldc_w / ldc2_w
					sites.add(new CpSite(operand, 2));
					pc = operand + 2;
				}
				case 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1 -> {
					// get/putstatic, get/putfield, invokevirtual/special/static,
					// new, anewarray, checkcast, instanceof
					sites.add(new CpSite(operand, 2));
					pc = operand + 2;
				}
				case 0xB9 -> { // invokeinterface: u2 index + count + 0
					sites.add(new CpSite(operand, 2));
					pc = operand + 4;
				}
				case 0xC5 -> { // multianewarray: u2 index + dimensions
					sites.add(new CpSite(operand, 2));
					pc = operand + 3;
				}
				default -> pc = operand + operandLength(code, op, operand);
			}
		}
		if (pc != code.length) {
			throw new IllegalStateException("JvmClassShaker: bytecode overruns the Code attribute");
		}
		return sites;
	}

	// Operand byte count of a non-CP-bearing instruction whose opcode is at operand - 1.
	private static int operandLength(byte[] code, int op, int operand) {
		if (op <= 0x0F || (op >= 0x1A && op <= 0x35) || (op >= 0x3B && op <= 0x83) || (op >= 0x85 && op <= 0x98)
				|| (op >= 0xAC && op <= 0xB1) || op == 0xBE || op == 0xBF || op == 0xC2 || op == 0xC3) {
			return 0; // constants, loads/stores, arithmetic, conversions, returns, ...
		}
		if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3A)) {
			return 1; // load/store with a u1 local index
		}
		if ((op >= 0x99 && op <= 0xA8) || op == 0xC6 || op == 0xC7) {
			return 2; // branches: u2 offset
		}
		return switch (op) {
			case 0x10, 0xA9, 0xBC -> 1; // bipush, ret, newarray
			case 0x11, 0x84 -> 2; // sipush, iinc
			case 0xC8, 0xC9 -> 4; // goto_w, jsr_w
			case 0xC4 -> (code[operand] & 0xff) == 0x84 ? 5 : 3; // wide (iinc |
																	// load/store/ret)
			case 0xAA -> { // tableswitch
				int base = operand + pad(operand);
				int low = s4(code, base + 4);
				int high = s4(code, base + 8);
				yield base - operand + 12 + 4 * (high - low + 1);
			}
			case 0xAB -> { // lookupswitch
				int base = operand + pad(operand);
				int npairs = s4(code, base + 4);
				yield base - operand + 8 + 8 * npairs;
			}
			default -> throw new IllegalStateException(String.format("JvmClassShaker: unhandled opcode 0x%02X", op));
		};
	}

	// Alignment padding after a tableswitch/lookupswitch opcode (operands 4-byte aligned
	// relative to the start of the code array).
	private static int pad(int operand) {
		return (4 - (operand & 3)) & 3;
	}

	// Rewrites each recorded constant-pool index immediate to its remapped value. Widths
	// never change: a u2 stays a u2, and a u1 (ldc) index only shrinks because the
	// compaction preserves entry order.
	private static byte[] rewriteCode(byte[] code, List<CpSite> sites, int[] remap) {
		if (sites.isEmpty()) {
			return code;
		}
		byte[] rewritten = code.clone();
		for (CpSite site : sites) {
			int neu = remap[readIndex(code, site)];
			if (site.width == 1) {
				if (neu > 0xFF) {
					throw new IllegalStateException("JvmClassShaker: remapped ldc index exceeds one byte: " + neu);
				}
				rewritten[site.offset] = (byte) neu;
			}
			else {
				rewritten[site.offset] = (byte) (neu >>> 8);
				rewritten[site.offset + 1] = (byte) neu;
			}
		}
		return rewritten;
	}

	private static int readIndex(byte[] code, CpSite site) {
		return site.width == 1 ? code[site.offset] & 0xff : u2(code, site.offset);
	}

	// --- Constant pool helpers ---

	private static CpEntry entry(@Nullable CpEntry[] cp, int idx) {
		CpEntry e = idx > 0 && idx < cp.length ? cp[idx] : null;
		if (e == null) {
			throw new IllegalStateException("JvmClassShaker: invalid constant pool index " + idx);
		}
		return e;
	}

	private static void markCp(@Nullable CpEntry[] cp, boolean[] marked, int idx) {
		if (idx == 0 || marked[idx]) {
			return;
		}
		marked[idx] = true;
		CpEntry e = entry(cp, idx);
		switch (e.tag) {
			case TAG_CLASS, TAG_STRING -> markCp(cp, marked, u2(e.body, 0));
			case TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE -> {
				markCp(cp, marked, u2(e.body, 0));
				markCp(cp, marked, u2(e.body, 2));
			}
			default -> {
			}
		}
	}

	// Decodes a CONSTANT_Utf8 entry (modified UTF-8, as DataInput defines it).
	private static String utf8(@Nullable CpEntry[] cp, int idx) {
		CpEntry e = entry(cp, idx);
		if (e.tag != TAG_UTF8) {
			throw new IllegalStateException("JvmClassShaker: constant " + idx + " is not Utf8");
		}
		try {
			return new DataInputStream(new ByteArrayInputStream(e.body)).readUTF();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// The binary class name behind a CONSTANT_Class entry.
	private static String className(@Nullable CpEntry[] cp, int classIdx) {
		return utf8(cp, u2(entry(cp, classIdx).body, 0));
	}

	// Identity key for a method or field: "name:descriptor". Constant-pool entries are
	// not deduplicated by the writer, so identity is by decoded string, never by index.
	private static String methodKey(@Nullable CpEntry[] cp, int nameIdx, int descIdx) {
		return utf8(cp, nameIdx) + ":" + utf8(cp, descIdx);
	}

	// The name:descriptor key of a Fieldref/Methodref/InterfaceMethodref entry.
	private static String refKey(@Nullable CpEntry[] cp, CpEntry ref) {
		CpEntry nat = entry(cp, u2(ref.body, 2));
		return methodKey(cp, u2(nat.body, 0), u2(nat.body, 2));
	}

	// --- Byte helpers ---

	private static int u2(byte[] buf, int off) {
		return (buf[off] & 0xff) << 8 | (buf[off + 1] & 0xff);
	}

	private static int s4(byte[] buf, int off) {
		return (buf[off] & 0xff) << 24 | (buf[off + 1] & 0xff) << 16 | (buf[off + 2] & 0xff) << 8
				| (buf[off + 3] & 0xff);
	}

	private static int readU2(byte[] buf, int[] p) {
		int v = u2(buf, p[0]);
		p[0] += 2;
		return v;
	}

	private static int readU4(byte[] buf, int[] p) {
		int v = s4(buf, p[0]);
		p[0] += 4;
		return v;
	}

	private static byte[] slice(byte[] src, int from, int to) {
		return Arrays.copyOfRange(src, from, to);
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

	private static void writeRaw(ByteArrayOutputStream out, byte[] bytes) {
		out.write(bytes, 0, bytes.length);
	}

}
