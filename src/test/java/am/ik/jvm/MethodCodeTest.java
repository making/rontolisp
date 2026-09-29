package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The typed layer: a body written through it is written, and runs, as the class writer
 * makes it -- labels bound before and after their branches, a branch past the 16-bit
 * offset, locals past slot 255, handlers -- and a layer over a compile context's body
 * keeps the context's operand-stack model in step.
 */
class MethodCodeTest {

	// An array store and a return chosen by kind, as CodeBuilder's arrayStore/return_:
	// char[] { c } through castore, its element back through ireturn.
	@Test
	void anArrayStoreAndAReturnByKindWriteTheKindsInstruction() throws Exception {
		Fixture f = new Fixture("Kinds");
		MethodCode c = new MethodCode();
		c.iconst_1().newarray(TypeKind.CHAR).astore(1);
		c.aload(1).iconst_0().iload(0).arrayStore(TypeKind.CHAR);
		c.aload(1).iconst_0().caload().return_(TypeKind.CHAR);
		f.add("run", "(I)C", c);
		assertThat(opcodes(f.write(), "run")).contains(Opcode.CASTORE, Opcode.IRETURN);
		assertThat(f.load().getMethod("run", int.class).invoke(null, (int) 'q')).isEqualTo('q');
	}

	@Test
	void aLoopBranchesForwardAndBackToItsLabels() throws Exception {
		Fixture f = new Fixture("Loop");
		// int run(int n) { int s = 0; for (int i = 0; i < n; i++) s += i; return s; }
		MethodCode c = new MethodCode();
		c.iconst_0().istore(1).iconst_0().istore(2);
		MethodCode.Label top = c.newBoundLabel();
		MethodCode.Label done = c.newLabel();
		c.iload(2).iload(0).if_icmpge(done);
		c.iload(1).iload(2).iadd().istore(1);
		c.iinc(2, 1).goto_(top);
		c.labelBinding(done);
		c.iload(1).ireturn();
		f.add("run", "(I)I", c);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 10)).isEqualTo(45);
	}

	@Test
	void aBranchPastTheSignedOffsetIsRecordedLongAndWrittenGotoW() throws Exception {
		Fixture f = new Fixture("Far");
		MethodCode c = new MethodCode();
		MethodCode.Label far = c.newLabel();
		c.iload(0).ifeq(far);
		for (int i = 0; i < 40_000; i++) {
			c.code().add(0x00);
		}
		c.labelBinding(far);
		c.loadConstant(7).ireturn();
		assertThat(c.longBranches()).singleElement().satisfies(branch -> assertThat(branch.pc()).isEqualTo(2));
		f.add("run", "(I)I", c);
		assertThat(opcodes(f.write(), "run")).contains(Opcode.GOTO_W);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 0)).isEqualTo(7);
	}

	// Past slot 255 a load, a store and an iinc take the wide form, emitted and written.
	@Test
	void aLocalPastSlot255TakesTheWideForm() throws Exception {
		Fixture f = new Fixture("Wide");
		MethodCode c = new MethodCode();
		c.iload(0).istore(300).iinc(300, 1000).iload(300).ireturn();
		assertThat(c.code()).startsWith(0x15, 0x00, 0xC4, 0x36, 0x01, 0x2C, 0xC4, 0x84, 0x01, 0x2C, 0x03, 0xE8);
		f.add("run", "(I)I", c);
		assertThat(opcodes(f.write(), "run")).contains(Opcode.ISTORE_W, Opcode.IINC_W, Opcode.ILOAD_W);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 5)).isEqualTo(1005);
	}

	// A local is emitted in the explicit-slot form the byte emitters use, so moving a
	// sequence onto this layer measures what it measured; the writer writes the
	// shortest form.
	@Test
	void aLowLocalIsEmittedExplicitAndWrittenShortest() {
		Fixture f = new Fixture("Low");
		MethodCode c = new MethodCode();
		c.aload(0).areturn();
		assertThat(c.code()).containsExactly(0x19, 0x00, 0xB0);
		f.add("run", "(Ljava/lang/Object;)Ljava/lang/Object;", c);
		assertThat(opcodes(f.write(), "run")).containsExactly(Opcode.ALOAD_0, Opcode.ARETURN);
	}

	@Test
	void aBranchToALabelNeverBoundIsRefused() {
		MethodCode c = new MethodCode();
		c.iconst_0().ifeq(c.newLabel()).return_();
		assertThatIllegalStateException().isThrownBy(c::checkComplete).withMessageContaining("never bound");
	}

	@Test
	void aLabelIsBoundOnce() {
		MethodCode c = new MethodCode();
		MethodCode.Label label = c.newBoundLabel();
		assertThatIllegalStateException().isThrownBy(() -> c.labelBinding(label));
	}

	@Test
	void anIntPastTheShortRangeIsNotAPush() {
		assertThatIllegalArgumentException().isThrownBy(() -> new MethodCode().loadConstant(40_000))
			.withMessageContaining("ldc");
	}

	// invokeinterface carries the argument slots, the receiver included; a long takes
	// two.
	@Test
	void invokeinterfaceCountsTheArgumentSlots() {
		ConstantPool cp = new ConstantPool();
		MethodCode c = new MethodCode();
		c.invokeinterface(cp.interfaceMethodRef("java/util/Map", "put",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		c.invokeinterface(cp.interfaceMethodRef("java/util/function/LongConsumer", "accept", "(J)V"));
		assertThat(c.code().get(3)).isEqualTo(3);
		assertThat(c.code().get(8)).isEqualTo(3);
	}

	@Test
	void aHandlerCatchesWhatItsRangeThrows() throws Exception {
		Fixture f = new Fixture("Catch");
		ClassEntry arithmetic = f.cp.classEntry("java/lang/ArithmeticException");
		// int run(int d) { try { return 10 / d; } catch (ArithmeticException e) { return
		// -1; } }
		MethodCode c = new MethodCode();
		MethodCode.Label start = c.newBoundLabel();
		c.loadConstant(10).iload(0).idiv();
		MethodCode.Label end = c.newBoundLabel();
		c.ireturn();
		MethodCode.Label handler = c.newBoundLabel();
		c.pop().iconst_m1().ireturn();
		c.exceptionCatch(start, end, handler, arithmetic);
		f.add("run", "(I)I", c);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 5)).isEqualTo(2);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 0)).isEqualTo(-1);
	}

	// A block assembled as a body of its own keeps its branches wherever it is spliced:
	// the dispatch tables splice their case bodies into a search tree this way.
	@Test
	void aSplicedBlockKeepsItsBranches() throws Exception {
		Fixture f = new Fixture("Splice");
		// int run(Object x) { return x == null ? -1 : 1; }, the test inside the block
		MethodCode block = new MethodCode();
		MethodCode.Label notNull = block.newLabel();
		block.aload(0).ifnonnull(notNull).iconst_m1().ireturn();
		block.labelBinding(notNull);
		block.iconst_1().ireturn();
		MethodCode c = new MethodCode();
		c.iconst_0().pop().append(block);
		assertThat(c.size()).isEqualTo(2 + block.size());
		f.add("run", "(Ljava/lang/Object;)I", c);
		assertThat(f.load().getMethod("run", Object.class).invoke(null, (Object) null)).isEqualTo(-1);
		assertThat(f.load().getMethod("run", Object.class).invoke(null, "x")).isEqualTo(1);
	}

	@Test
	void onlyAPlainBlockIsSpliced() {
		MethodCode waiting = new MethodCode();
		waiting.iconst_0().ifeq(waiting.newLabel());
		assertThatIllegalStateException().isThrownBy(() -> new MethodCode().append(waiting));
		MethodCode caught = new MethodCode();
		MethodCode.Label start = caught.newBoundLabel();
		caught.iconst_0();
		MethodCode.Label end = caught.newBoundLabel();
		caught.ireturn();
		caught.exceptionCatch(start, end, end, null);
		assertThatIllegalArgumentException().isThrownBy(() -> new MethodCode().append(caught));
		MethodCode context = new MethodCode(new ArrayList<>(), new OperandStack(new ConstantPool()), new ArrayList<>(),
				new ArrayList<>());
		assertThatIllegalArgumentException().isThrownBy(() -> context.append(new MethodCode().iconst_0()));
	}

	// Over a compile context's body, every byte reaches its operand-stack model, and a
	// branch patched at its label reconciles the model the way the byte emitter's does.
	@Test
	void aContextLayerKeepsTheOperandStackModelInStep() {
		ConstantPool cp = new ConstantPool();
		List<Integer> code = new ArrayList<>();
		OperandStack stack = new OperandStack(cp);
		MethodCode c = new MethodCode(code, stack, new ArrayList<>(), new ArrayList<>());
		MethodRefEntry valueOf = cp.methodRef("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		c.lconst_1().invokestatic(valueOf);
		assertThat(stack.snapshot()).containsExactly(OperandStack.Slot.REF);
		MethodCode.Label other = c.newLabel();
		MethodCode.Label join = c.newLabel();
		c.iconst_0().ifeq(other).pop().aconst_null().goto_(join);
		c.labelBinding(other);
		assertThat(stack.snapshot()).as("the branch's shape, established at its label")
			.containsExactly(OperandStack.Slot.REF);
		c.pop().aconst_null();
		c.labelBinding(join);
		assertThat(stack.snapshot()).containsExactly(OperandStack.Slot.REF);
		c.loadLocal(TypeKind.LONG, 2).storeLocal(TypeKind.LONG, 4);
		assertThat(stack.snapshot()).containsExactly(OperandStack.Slot.REF);
	}

	private static List<Opcode> opcodes(byte[] classFile, String method) {
		MethodModel model = ClassFile.of()
			.parse(classFile)
			.methods()
			.stream()
			.filter(m -> m.methodName().equalsString(method))
			.findFirst()
			.orElseThrow();
		CodeAttribute code = model.findAttribute(Attributes.code()).orElseThrow();
		List<Opcode> opcodes = new ArrayList<>();
		for (CodeElement element : code.elementList()) {
			if (element instanceof Instruction instruction && instruction.opcode() != Opcode.NOP) {
				opcodes.add(instruction.opcode());
			}
		}
		return opcodes;
	}

	/** {@code class <name> extends Object}, its methods written through MethodCode. */
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

		void add(String method, String descriptor, MethodCode code) {
			code.addTo(this.definition, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, this.cp.addUtf8(method),
					this.cp.addUtf8(descriptor));
		}

		byte[] write() {
			return JvmClassSplitter
				.write(this.definition.build(), null, method -> true, ConstantPool.MAX_INDEX,
						JvmClassSplitter.Target.of(61))
				.mainClass();
		}

		Class<?> load() throws ClassNotFoundException {
			byte[] bytes = this.write();
			return new ClassLoader(MethodCodeTest.class.getClassLoader()) {
				@Override
				protected Class<?> findClass(String className) throws ClassNotFoundException {
					if (!className.equals(Fixture.this.name)) {
						throw new ClassNotFoundException(className);
					}
					return defineClass(className, bytes, 0, bytes.length);
				}
			}.loadClass(this.name);
		}

	}

}
