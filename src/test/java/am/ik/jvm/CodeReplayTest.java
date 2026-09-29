package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LineNumberInfo;
import java.lang.classfile.attribute.LineNumberTableAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A method body written as code bytes, played into the class-file writer: the branches,
 * handlers and line numbers keep their targets when the writer changes an instruction's
 * width, a {@code wide} local survives, and a body past the format limit is refused
 * naming the method.
 */
class CodeReplayTest {

	// A branch whose target is past the signed 16-bit reach, recorded as a long branch
	// with placeholder offset bytes, over a `wide astore`/`wide aload` of slot 300: the
	// writer places it in its goto_w form, measuring the wide instructions around it, and
	// a line entry after the branch rides the instruction it labels.
	@Test
	void aLongBranchOverWideLocalsIsWrittenInItsGotoWForm() throws Exception {
		Fixture f = new Fixture("WideLocal");
		int slot = 300;
		Code code = new Code().ldc(f.cp.addString("ok")).op(Opcode.WIDE).op(Opcode.ASTORE).u2(slot).op(Opcode.ICONST_0);
		int branchPc = code.bytes.size();
		code.op(Opcode.IFEQ).u2(0);
		for (int i = 0; i < 40_000; i++) {
			code.op(Opcode.NOP);
		}
		int targetPc = code.bytes.size();
		code.op(Opcode.WIDE).op(Opcode.ALOAD).u2(slot).op(Opcode.ARETURN);
		f.definition.lineNumberTableName(f.cp.addUtf8("LineNumberTable"));
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("run"),
				f.cp.addUtf8("()Ljava/lang/Object;"), code.bytes, List.of(),
				List.of(new ClassDefinition.Line(targetPc, 7)),
				List.of(new ClassDefinition.Branch(branchPc, targetPc)));
		byte[] written = f.write();

		assertThat(f.load(written).getMethod("run").invoke(null)).isEqualTo("ok");
		CodeAttribute run = code(written, "run");
		List<java.lang.classfile.Opcode> opcodes = opcodes(run);
		assertThat(opcodes).contains(java.lang.classfile.Opcode.GOTO_W, java.lang.classfile.Opcode.ALOAD_W,
				java.lang.classfile.Opcode.ASTORE_W);
		// The relaxed branch grew the code in front of the target, and the line moved
		// with it: it still starts at the wide aload.
		int aload = pcOf(run, java.lang.classfile.Opcode.ALOAD_W);
		assertThat(aload).isGreaterThan(targetPc);
		assertThat(lines(run)).extracting(LineNumberInfo::startPc).containsExactly(aload);
	}

	// The writer's own relaxation would rewrite EVERY forward branch of the method long
	// once one does not reach -- +5 bytes for each of these thousand short ones, and a
	// method near 64 KB outgrows the limit that way. Only the branch that does not reach
	// is widened.
	@Test
	void onlyTheBranchThatDoesNotReachIsWrittenLong() throws Exception {
		Fixture f = new Fixture("OnlyFar");
		Code code = new Code().op(Opcode.ICONST_0);
		int farPc = code.bytes.size();
		code.op(Opcode.IFEQ).u2(0);
		for (int k = 0; k < 1000; k++) {
			// iconst_1; ifeq to the next instruction
			code.op(Opcode.ICONST_1).op(Opcode.IFEQ).u2(3);
		}
		for (int i = 0; i < 40_000; i++) {
			code.op(Opcode.NOP);
		}
		int targetPc = code.bytes.size();
		code.op(Opcode.RETURN);
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("run"), f.cp.addUtf8("()V"),
				code.bytes, List.of(), List.of(), List.of(new ClassDefinition.Branch(farPc, targetPc)));
		byte[] written = f.write();

		CodeAttribute run = code(written, "run");
		assertThat(run.codeLength()).as("the one conditional branch grew by five bytes")
			.isEqualTo(code.bytes.size() + 5);
		assertThat(opcodes(run)).filteredOn(op -> op == java.lang.classfile.Opcode.GOTO_W).hasSize(1);
		f.load(written).getMethod("run").invoke(null);
	}

	// Widening moves every later instruction: a branch that reached before a branch
	// between it and its target was widened may not reach after, and is widened in turn.
	@Test
	void aBranchPushedOutOfReachByAnotherIsWidenedToo() throws Exception {
		Fixture f = new Fixture("Cascade");
		// iload_0; ifne X -- reaching exactly 32767 bytes on as written
		Code code = new Code().op(Opcode.ILOAD_0);
		int nearPc = code.bytes.size();
		code.op(Opcode.IFNE).u2(Short.MAX_VALUE);
		// Between it and X: iload_0; ifeq FAR, far past X.
		code.op(Opcode.ILOAD_0);
		int farPc = code.bytes.size();
		code.op(Opcode.IFEQ).u2(0);
		while (code.bytes.size() < nearPc + Short.MAX_VALUE) {
			code.op(Opcode.NOP);
		}
		while (code.bytes.size() < farPc + 40_000) {
			code.op(Opcode.NOP);
		}
		int farTarget = code.bytes.size();
		code.op(Opcode.RETURN);
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("run"), f.cp.addUtf8("(I)V"),
				code.bytes, List.of(), List.of(), List.of(new ClassDefinition.Branch(farPc, farTarget)));
		byte[] written = f.write();

		CodeAttribute run = code(written, "run");
		assertThat(opcodes(run)).filteredOn(op -> op == java.lang.classfile.Opcode.GOTO_W)
			.as("the far branch, and the one it pushed out of reach")
			.hasSize(2);
		assertThat(run.codeLength()).isEqualTo(code.bytes.size() + 5 + 5);
		Class<?> cascade = f.load(written);
		cascade.getMethod("run", int.class).invoke(null, 0);
		cascade.getMethod("run", int.class).invoke(null, 1);
	}

	// Pins the exception table: a typed catch and a catch-any, each landing a handler
	// that reads the message of what the protected range threw.
	@Test
	void aTypedCatchAndACatchAnyBothLand() throws Exception {
		assertThat(runCatch("TypedCatch", true)).isEqualTo("boom");
		assertThat(runCatch("CatchAny", false)).isEqualTo("boom");
	}

	// The writer gives every class a pool of its own in the order it writes it, so a
	// constant the master pool had below 256 can land past it: its ldc widens to ldc_w, a
	// byte longer, and the branch over it has to follow.
	@Test
	void anLdcWhoseConstantLandsPastIndex255WidensAndTheBranchOverItFollows() throws Exception {
		Fixture f = new Fixture("Widen");
		ConstantPool.StringConstant hello = f.cp.addString("hello");
		ConstantPool.Utf8Constant voidDesc = f.cp.addUtf8("()V");
		for (int k = 0; k < 200; k++) {
			f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("f" + k), voidDesc,
					new Code().ldc(f.cp.addString("filler-" + k)).op(Opcode.POP).op(Opcode.RETURN).bytes, List.of(),
					List.of(), List.of());
		}
		// iconst_0; ifne +6 (to aconst_null); ldc hello; areturn; aconst_null; areturn
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("run"),
				f.cp.addUtf8("()Ljava/lang/Object;"),
				new Code().op(Opcode.ICONST_0)
					.op(Opcode.IFNE)
					.u2(6)
					.op(Opcode.LDC)
					.op(hello.index())
					.op(Opcode.ARETURN)
					.op(Opcode.ACONST_NULL)
					.op(Opcode.ARETURN).bytes,
				List.of(), List.of(), List.of());
		assertThat(hello.index()).isLessThan(256);
		byte[] written = f.write();

		assertThat(opcodes(code(written, "run"))).contains(java.lang.classfile.Opcode.LDC_W)
			.doesNotContain(java.lang.classfile.Opcode.LDC);
		assertThat(f.load(written).getMethod("run").invoke(null)).isEqualTo("hello");
	}

	// ... and the other way: an ldc_w the master pool needed becomes an ldc when its
	// constant lands below 256 in the class's own pool.
	@Test
	void anLdcWWhoseConstantLandsBelowIndex256Narrows() throws Exception {
		Fixture f = new Fixture("Narrow");
		for (int k = 0; k < 300; k++) {
			f.cp.addUtf8("unreferenced-" + k);
		}
		ConstantPool.StringConstant late = f.cp.addString("late");
		assertThat(late.index()).isGreaterThan(255);
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("run"),
				f.cp.addUtf8("()Ljava/lang/Object;"), new Code().ldc(late).op(Opcode.ARETURN).bytes, List.of(),
				List.of(), List.of());
		byte[] written = f.write();

		assertThat(opcodes(code(written, "run"))).containsExactly(java.lang.classfile.Opcode.LDC,
				java.lang.classfile.Opcode.ARETURN);
		assertThat(f.load(written).getMethod("run").invoke(null)).isEqualTo("late");
	}

	// A local past slot 255 in an iinc takes the wide form with a two-byte increment;
	// the replay reads both back whole.
	@Test
	void aWideIincKeepsItsSlotAndIncrement() throws Exception {
		Fixture f = new Fixture("WideIinc");
		int slot = 400;
		Code code = new Code().op(Opcode.ICONST_0)
			.op(Opcode.WIDE)
			.op(Opcode.ISTORE)
			.u2(slot)
			.op(Opcode.WIDE)
			.op(Opcode.IINC)
			.u2(slot)
			.u2(1000)
			.op(Opcode.WIDE)
			.op(Opcode.ILOAD)
			.u2(slot)
			.op(Opcode.IRETURN);
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("run"), f.cp.addUtf8("()I"),
				code.bytes, List.of(), List.of(), List.of());
		assertThat(f.load(f.write()).getMethod("run").invoke(null)).isEqualTo(1000);
	}

	@Test
	void aBodyPastTheFormatLimitIsRefusedNamingTheMethod() {
		Fixture f = new Fixture("Huge");
		List<Integer> code = new ArrayList<>();
		for (int i = 0; i < 70_000; i++) {
			code.add(Opcode.NOP);
		}
		code.add(Opcode.RETURN);
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.addUtf8("huge"), f.cp.addUtf8("()V"),
				code, List.of(), List.of(), List.of());
		assertThatIllegalArgumentException().isThrownBy(f::write)
			.withMessageContaining("method huge")
			.withMessageContaining("65535-byte limit");
	}

	/**
	 * A static {@code run()} that throws {@code RuntimeException("boom")} inside a
	 * protected range and returns the caught exception's message from the handler.
	 */
	private static String runCatch(String name, boolean typed) throws Exception {
		Fixture f = new Fixture(name);
		ConstantPool cp = f.cp;
		ConstantPool.ClassConstant runtimeException = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant ctor = cp.addMethodref(runtimeException,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		// Through Throwable, so the catch-any handler (whose stack top is a Throwable)
		// passes the receiver check too.
		ConstantPool.MethodrefConstant getMessage = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/Throwable")),
				cp.addNameAndType(cp.addUtf8("getMessage"), cp.addUtf8("()Ljava/lang/String;")));
		// 0: new, 3: dup, 4: ldc_w "boom", 7: invokespecial <init>, 10: athrow,
		// 11 (handler): invokevirtual getMessage, 14: areturn
		Code code = new Code().op(Opcode.NEW)
			.u2(runtimeException.index())
			.op(Opcode.DUP)
			.op(Opcode.LDC_W)
			.u2(cp.addString("boom").index())
			.op(Opcode.INVOKESPECIAL)
			.u2(ctor.index())
			.op(Opcode.ATHROW)
			.op(Opcode.INVOKEVIRTUAL)
			.u2(getMessage.index())
			.op(Opcode.ARETURN);
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, cp.addUtf8("run"),
				cp.addUtf8("()Ljava/lang/String;"), code.bytes,
				List.of(new ClassDefinition.Handler(0, 11, 11, typed ? runtimeException.index() : 0)), List.of(),
				List.of());
		return (String) f.load(f.write()).getMethod("run").invoke(null);
	}

	private static CodeAttribute code(byte[] classFile, String method) {
		MethodModel model = ClassFile.of()
			.parse(classFile)
			.methods()
			.stream()
			.filter(m -> m.methodName().equalsString(method))
			.findFirst()
			.orElseThrow();
		return model.findAttribute(Attributes.code()).orElseThrow();
	}

	/** The instructions' opcodes, the {@code nop} padding left out. */
	private static List<java.lang.classfile.Opcode> opcodes(CodeAttribute code) {
		List<java.lang.classfile.Opcode> opcodes = new ArrayList<>();
		for (CodeElement element : code.elementList()) {
			if (element instanceof Instruction instruction && instruction.opcode() != java.lang.classfile.Opcode.NOP) {
				opcodes.add(instruction.opcode());
			}
		}
		return opcodes;
	}

	private static int pcOf(CodeAttribute code, java.lang.classfile.Opcode wanted) {
		int pc = 0;
		for (CodeElement element : code.elementList()) {
			if (element instanceof Instruction instruction) {
				if (instruction.opcode() == wanted) {
					return pc;
				}
				pc += instruction.sizeInBytes();
			}
		}
		throw new AssertionError(wanted + " not found");
	}

	private static List<LineNumberInfo> lines(CodeAttribute code) {
		return code.findAttribute(Attributes.lineNumberTable())
			.map(LineNumberTableAttribute::lineNumbers)
			.orElse(List.of());
	}

	/** A definition under construction: {@code class <name> extends Object}. */
	private static final class Fixture {

		final String name;

		final ConstantPool cp = new ConstantPool();

		final ClassDefinition.Builder definition;

		Fixture(String name) {
			this.name = name;
			this.definition = ClassDefinition.builder(this.cp, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER,
					this.cp.addClass(this.cp.addUtf8(name)), this.cp.addClass(this.cp.addUtf8("java/lang/Object")),
					this.cp.addUtf8("Code"));
		}

		byte[] write() {
			JvmClassSplitter.Split split = JvmClassSplitter.write(this.definition.build(), null, method -> true,
					ConstantPool.MAX_INDEX, JvmClassSplitter.Target.of(61));
			assertThat(split.parts()).isEmpty();
			return split.mainClass();
		}

		Class<?> load(byte[] classFile) throws ClassNotFoundException {
			return new Loader(Map.of(this.name, classFile)).loadClass(this.name);
		}

	}

	/**
	 * A method body, written the way the generators write one: a u2's high part kept
	 * whole.
	 */
	private static final class Code {

		final List<Integer> bytes = new ArrayList<>();

		Code op(int opcode) {
			this.bytes.add(opcode);
			return this;
		}

		Code u2(int value) {
			this.bytes.add(value >> 8);
			this.bytes.add(value & 0xFF);
			return this;
		}

		Code ldc(ConstantPool.Constant constant) {
			if (constant.index() <= 255) {
				return this.op(Opcode.LDC).op(constant.index());
			}
			return this.op(Opcode.LDC_W).u2(constant.index());
		}

	}

	/** Defines the written classes, the way a class path would serve them. */
	private static final class Loader extends ClassLoader {

		private final Map<String, byte[]> classes;

		Loader(Map<String, byte[]> classes) {
			super(CodeReplayTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = this.classes.get(name.replace('.', '/'));
			if (bytes == null) {
				throw new ClassNotFoundException(name);
			}
			return defineClass(name, bytes, 0, bytes.length);
		}

	}

}
