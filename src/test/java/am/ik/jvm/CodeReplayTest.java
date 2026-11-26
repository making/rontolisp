package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.LineNumberInfo;
import java.lang.classfile.attribute.LineNumberTableAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A method body's records played into the class-file writer: a branch that does not reach
 * is written long -- only that one, and one another pushed out of reach -- the handlers
 * and line numbers keep their instructions when the writer changes a width, a
 * {@code wide} local survives, and a body past the format limit is refused naming the
 * method.
 */
class CodeReplayTest {

	// A branch past the signed 16-bit reach over a `wide astore`/`wide aload` of slot
	// 300: the writer places it in its goto_w form, measuring the wide instructions
	// around it, and the line entry of the instruction the branch lands on rides that
	// instruction.
	@Test
	void aLongBranchOverWideLocalsIsWrittenInItsGotoWForm() throws Exception {
		Fixture f = new Fixture("WideLocal");
		int slot = 300;
		MethodCode c = new MethodCode();
		MethodCode.Label far = c.newLabel();
		c.ldc(f.cp.stringEntry("ok")).astore(slot).iconst_0().ifeq(far);
		for (int i = 0; i < 40_000; i++) {
			c.nop();
		}
		c.labelBinding(far);
		int target = c.position();
		c.aload(slot).areturn();
		f.definition.lineNumberTableName(f.cp.utf8Entry("LineNumberTable"));
		f.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, f.cp.utf8Entry("run"),
				f.cp.utf8Entry("()Ljava/lang/Object;"), c, List.of(new ClassDefinition.Line(target, 7)));
		byte[] written = f.write();

		assertThat(f.load(written).getMethod("run").invoke(null)).isEqualTo("ok");
		CodeAttribute run = code(written, "run");
		assertThat(opcodes(run)).contains(Opcode.GOTO_W, Opcode.ALOAD_W, Opcode.ASTORE_W);
		assertThat(run.codeLength()).isEqualTo(c.size());
		assertThat(lines(run)).extracting(LineNumberInfo::startPc).containsExactly(pcOf(run, Opcode.ALOAD_W));
	}

	// The writer's own relaxation would rewrite EVERY forward branch of the method long
	// once one does not reach -- +5 bytes for each of these thousand short ones, and a
	// method near 64 KB outgrows the limit that way. Only the branch that does not reach
	// is widened.
	@Test
	void onlyTheBranchThatDoesNotReachIsWrittenLong() throws Exception {
		Fixture f = new Fixture("OnlyFar");
		MethodCode c = new MethodCode();
		MethodCode.Label far = c.newLabel();
		c.iconst_0().ifeq(far);
		for (int k = 0; k < 1000; k++) {
			MethodCode.Label next = c.newLabel();
			c.iconst_1().ifeq(next);
			c.labelBinding(next);
		}
		for (int i = 0; i < 40_000; i++) {
			c.nop();
		}
		c.labelBinding(far);
		c.return_();
		f.add("run", "()V", c);
		byte[] written = f.write();

		CodeAttribute run = code(written, "run");
		assertThat(run.codeLength()).as("the one conditional branch grew by five bytes, as measured")
			.isEqualTo(1 + 8 + 1000 * 4 + 40_000 + 1)
			.isEqualTo(c.size());
		assertThat(opcodes(run)).filteredOn(op -> op == Opcode.GOTO_W).hasSize(1);
		f.load(written).getMethod("run").invoke(null);
	}

	// Widening moves every later instruction: a branch that reached before a branch
	// between it and its target was widened may not reach after, and is widened in turn.
	// The measure counts the first, whose label showed it far; the writer finds the
	// second.
	@Test
	void aBranchPushedOutOfReachByAnotherIsWidenedToo() throws Exception {
		Fixture f = new Fixture("Cascade");
		MethodCode c = new MethodCode();
		MethodCode.Label near = c.newLabel();
		MethodCode.Label far = c.newLabel();
		// iload_0; ifne NEAR -- reaching exactly 32767 bytes on as measured
		c.iload(0);
		int nearPc = c.size();
		c.ifne(near);
		// Between it and NEAR: iload_0; ifeq FAR, far past NEAR.
		c.iload(0);
		int farPc = c.size();
		c.ifeq(far);
		while (c.size() < nearPc + Short.MAX_VALUE) {
			c.nop();
		}
		c.labelBinding(near);
		while (c.size() < farPc + 40_000) {
			c.nop();
		}
		c.labelBinding(far);
		c.return_();
		f.add("run", "(I)V", c);
		byte[] written = f.write();

		CodeAttribute run = code(written, "run");
		assertThat(opcodes(run)).filteredOn(op -> op == Opcode.GOTO_W)
			.as("the far branch, and the one it pushed out of reach")
			.hasSize(2);
		assertThat(run.codeLength()).isEqualTo(c.size() + 5);
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
		StringEntry hello = f.cp.stringEntry("hello");
		for (int k = 0; k < 200; k++) {
			f.add("f" + k, "()V", new MethodCode().ldc(f.cp.stringEntry("filler-" + k)).pop().return_());
		}
		// iconst_0; ifne NULL; ldc hello; areturn; NULL: aconst_null; areturn
		MethodCode c = new MethodCode();
		MethodCode.Label isNull = c.newLabel();
		c.iconst_0().ifne(isNull).ldc(hello).areturn();
		c.labelBinding(isNull);
		c.aconst_null().areturn();
		f.add("run", "()Ljava/lang/Object;", c);
		assertThat(hello.index()).isLessThan(256);
		byte[] written = f.write();

		assertThat(opcodes(code(written, "run"))).contains(Opcode.LDC_W).doesNotContain(Opcode.LDC);
		assertThat(f.load(written).getMethod("run").invoke(null)).isEqualTo("hello");
	}

	// ... and the other way: a constant past index 255 in the master pool becomes an
	// ldc when it lands below 256 in the class's own pool.
	@Test
	void anLdcWhoseMasterIndexIsPast255NarrowsInTheClass() throws Exception {
		Fixture f = new Fixture("Narrow");
		for (int k = 0; k < 300; k++) {
			f.cp.entries().utf8Entry("unreferenced-" + k);
		}
		StringEntry late = f.cp.stringEntry("late");
		assertThat(late.index()).isGreaterThan(255);
		MethodCode c = new MethodCode().ldc(late).areturn();
		assertThat(c.size()).as("measured by the master index").isEqualTo(3 + 1);
		f.add("run", "()Ljava/lang/Object;", c);
		byte[] written = f.write();

		assertThat(opcodes(code(written, "run"))).containsExactly(Opcode.LDC, Opcode.ARETURN);
		assertThat(f.load(written).getMethod("run").invoke(null)).isEqualTo("late");
	}

	// A local past slot 255 in an iinc takes the wide form with a two-byte increment.
	@Test
	void aWideIincKeepsItsSlotAndIncrement() throws Exception {
		Fixture f = new Fixture("WideIinc");
		int slot = 400;
		f.add("run", "()I", new MethodCode().iconst_0().istore(slot).iinc(slot, 1000).iload(slot).ireturn());
		assertThat(f.load(f.write()).getMethod("run").invoke(null)).isEqualTo(1000);
	}

	@Test
	void aBodyPastTheFormatLimitIsRefusedNamingTheMethod() {
		Fixture f = new Fixture("Huge");
		MethodCode c = new MethodCode();
		for (int i = 0; i < 70_000; i++) {
			c.nop();
		}
		f.add("huge", "()V", c.return_());
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
		ClassEntry runtimeException = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry ctor = cp.methodRef(runtimeException, "<init>", "(Ljava/lang/String;)V");
		// Through Throwable, so the catch-any handler (whose stack top is a Throwable)
		// passes the receiver check too.
		MethodRefEntry getMessage = cp.methodRef("java/lang/Throwable", "getMessage", "()Ljava/lang/String;");
		MethodCode c = new MethodCode();
		MethodCode.Label start = c.newBoundLabel();
		c.new_(runtimeException).dup().ldc(cp.stringEntry("boom")).invokespecial(ctor).athrow();
		MethodCode.Label handler = c.newBoundLabel();
		c.invokevirtual(getMessage).areturn();
		c.exceptionCatch(start, handler, handler, typed ? runtimeException : null);
		f.add("run", "()Ljava/lang/String;", c);
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
	private static List<Opcode> opcodes(CodeAttribute code) {
		List<Opcode> opcodes = new ArrayList<>();
		for (CodeElement element : code.elementList()) {
			if (element instanceof Instruction instruction && instruction.opcode() != Opcode.NOP) {
				opcodes.add(instruction.opcode());
			}
		}
		return opcodes;
	}

	private static int pcOf(CodeAttribute code, Opcode wanted) {
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
					this.cp.classEntry(name), this.cp.classEntry("java/lang/Object"), this.cp.utf8Entry("Code"));
		}

		void add(String method, String descriptor, MethodCode code) {
			this.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, this.cp.utf8Entry(method),
					this.cp.utf8Entry(descriptor), code);
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
