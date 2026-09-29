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
import java.util.function.LongConsumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The typed layer: a body written through it is written, and runs, as the class writer
 * makes it -- labels bound before and after their branches, a branch past the 16-bit
 * offset, locals past slot 255, handlers -- its measure is the written size, and a layer
 * over a compile context's body keeps the context's operand-stack model in step.
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
		assertThat(top.position()).isEqualTo(4);
		assertThat(done.position()).isEqualTo(13);
		f.add("run", "(I)I", c);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 10)).isEqualTo(45);
	}

	// A branch whose offset does not fit 16 bits is written goto_w, and the measure
	// counts
	// its long form once the label that shows it is bound: an inverted short branch over
	// a
	// goto_w, five bytes more.
	@Test
	void aBranchPastTheSignedOffsetIsMeasuredLongAndWrittenGotoW() throws Exception {
		Fixture f = new Fixture("Far");
		MethodCode c = new MethodCode();
		MethodCode.Label far = c.newLabel();
		c.iload(0).ifeq(far);
		for (int i = 0; i < 40_000; i++) {
			c.nop();
		}
		assertThat(c.size()).isEqualTo(1 + 3 + 40_000);
		c.labelBinding(far);
		assertThat(c.size()).isEqualTo(1 + 8 + 40_000);
		c.loadConstant(7).ireturn();
		f.add("run", "(I)I", c);
		byte[] written = f.write();
		assertThat(opcodes(written, "run")).contains(Opcode.GOTO_W);
		assertThat(codeLength(written, "run")).isEqualTo(c.size());
		assertThat(f.load().getMethod("run", int.class).invoke(null, 0)).isEqualTo(7);
	}

	// A backward branch knows its offset at once: a goto_w, two bytes more.
	@Test
	void aBackwardBranchPastTheSignedOffsetIsMeasuredLongAtOnce() throws Exception {
		Fixture f = new Fixture("Back");
		// int run(int n) { do { n--; } while (n > 0) -- the loop body padded past 32 KB
		MethodCode c = new MethodCode();
		MethodCode.Label top = c.newBoundLabel();
		MethodCode.Label done = c.newLabel();
		c.iinc(0, -1).iload(0).ifle(done);
		for (int i = 0; i < 40_000; i++) {
			c.nop();
		}
		int before = c.size();
		c.goto_(top);
		assertThat(c.size()).isEqualTo(before + 5);
		c.labelBinding(done);
		c.iload(0).ireturn();
		f.add("run", "(I)I", c);
		byte[] written = f.write();
		assertThat(opcodes(written, "run")).contains(Opcode.GOTO_W);
		assertThat(codeLength(written, "run")).isEqualTo(c.size());
		assertThat(f.load().getMethod("run", int.class).invoke(null, 3)).isEqualTo(0);
	}

	// Past slot 255 a load, a store and an iinc take the wide form.
	@Test
	void aLocalPastSlot255TakesTheWideForm() throws Exception {
		Fixture f = new Fixture("Wide");
		MethodCode c = new MethodCode();
		c.iload(0).istore(300).iinc(300, 1000).iload(300).ireturn();
		assertThat(c.size()).isEqualTo(1 + 4 + 6 + 4 + 1);
		f.add("run", "(I)I", c);
		byte[] written = f.write();
		assertThat(opcodes(written, "run")).contains(Opcode.ISTORE_W, Opcode.IINC_W, Opcode.ILOAD_W);
		assertThat(codeLength(written, "run")).isEqualTo(c.size());
		assertThat(f.load().getMethod("run", int.class).invoke(null, 5)).isEqualTo(1005);
	}

	// The measure is the written size: a local's load and store in the shortest form for
	// its slot, an iinc in its narrow form when both operands fit a byte, an int in the
	// shortest push.
	@Test
	void theMeasureIsTheWrittenSize() {
		Fixture f = new Fixture("Low");
		MethodCode c = new MethodCode();
		c.aload(0).astore(3).aload(3).astore(4).aload(4).astore(255).iconst_0().istore(5);
		c.iinc(5, -128).iinc(5, 128).loadConstant(-1).loadConstant(100).loadConstant(1000);
		c.pop().pop().pop().aload(255).areturn();
		assertThat(c.size()).isEqualTo(1 + 1 + 1 + 2 + 2 + 2 + 1 + 2 + 3 + 6 + 1 + 2 + 3 + 1 + 1 + 1 + 2 + 1);
		f.add("run", "(Ljava/lang/Object;)Ljava/lang/Object;", c);
		byte[] written = f.write();
		assertThat(codeLength(written, "run")).isEqualTo(c.size());
		assertThat(opcodes(written, "run")).startsWith(Opcode.ALOAD_0, Opcode.ASTORE_3, Opcode.ALOAD_3, Opcode.ASTORE,
				Opcode.ALOAD, Opcode.ASTORE, Opcode.ICONST_0, Opcode.ISTORE, Opcode.IINC, Opcode.IINC_W,
				Opcode.ICONST_M1, Opcode.BIPUSH, Opcode.SIPUSH);
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

	// invokeinterface's count operand -- the argument slots, the receiver included, a
	// long taking two -- is derived by the writer from the descriptor.
	@Test
	void invokeinterfaceCountsTheArgumentSlots() throws Exception {
		Fixture f = new Fixture("Iface");
		MethodCode c = new MethodCode();
		c.aload(0)
			.lconst_1()
			.invokeinterface(f.cp.interfaceMethodRef("java/util/function/LongConsumer", "accept", "(J)V"));
		c.return_();
		assertThat(c.size()).isEqualTo(1 + 1 + 5 + 1);
		f.add("run", "(Ljava/util/function/LongConsumer;)V", c);
		long[] seen = new long[1];
		LongConsumer sink = value -> seen[0] = value;
		f.load().getMethod("run", LongConsumer.class).invoke(null, sink);
		assertThat(seen[0]).isEqualTo(1L);
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

	// A fragment built apart runs where it is appended: its branch and its handler range
	// move with it.
	@Test
	void anAppendedFragmentKeepsItsBranchesAndHandlers() throws Exception {
		Fixture f = new Fixture("Append");
		ClassEntry arithmetic = f.cp.classEntry("java/lang/ArithmeticException");
		// int run(int d) { int r = 1; try { r = 10 / d; } catch (ArithmeticException e) {
		// r = -1; } if (r > 3) r = 3; return r; } -- the try and the if in a fragment.
		MethodCode fragment = new MethodCode();
		MethodCode.Label start = fragment.newBoundLabel();
		fragment.loadConstant(10).iload(0).idiv().istore(1);
		MethodCode.Label end = fragment.newBoundLabel();
		MethodCode.Label after = fragment.newLabel();
		fragment.goto_(after);
		MethodCode.Label handler = fragment.newBoundLabel();
		fragment.pop().iconst_m1().istore(1);
		fragment.labelBinding(after);
		MethodCode.Label small = fragment.newLabel();
		fragment.iload(1).iconst_3().if_icmple(small).iconst_3().istore(1);
		fragment.labelBinding(small);
		fragment.exceptionCatch(start, end, handler, arithmetic);
		MethodCode c = new MethodCode();
		c.iconst_1().istore(1);
		c.append(fragment);
		c.iload(1).ireturn();
		assertThat(c.handlers()).singleElement().satisfies(h -> {
			assertThat(h.start()).isEqualTo(2);
			assertThat(h.end()).isEqualTo(6);
			assertThat(h.handler()).isEqualTo(7);
		});
		f.add("run", "(I)I", c);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 5)).isEqualTo(2);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 1)).isEqualTo(3);
		assertThat(f.load().getMethod("run", int.class).invoke(null, 0)).isEqualTo(-1);
	}

	// A block assembled as a body of its own keeps its branches wherever it is spliced,
	// as often as it is: the dispatch tables splice their case bodies into a search tree
	// this way.
	@Test
	void aSplicedBlockKeepsItsBranches() throws Exception {
		Fixture f = new Fixture("Splice");
		// int run(Object x, int which) { return x == null ? -1 : 1; }, the test inside
		// the
		// block -- spliced twice, `which` choosing the copy that runs
		MethodCode block = new MethodCode();
		MethodCode.Label notNull = block.newLabel();
		block.aload(0).ifnonnull(notNull).iconst_m1().ireturn();
		block.labelBinding(notNull);
		block.iconst_1().ireturn();
		MethodCode c = new MethodCode();
		MethodCode.Label second = c.newLabel();
		c.iload(1).ifne(second).append(block);
		c.labelBinding(second);
		c.append(block);
		assertThat(c.size()).isEqualTo(1 + 3 + 2 * block.size());
		f.add("run", "(Ljava/lang/Object;I)I", c);
		Class<?> splice = f.load();
		for (int which = 0; which < 2; which++) {
			assertThat(splice.getMethod("run", Object.class, int.class).invoke(null, null, which)).isEqualTo(-1);
			assertThat(splice.getMethod("run", Object.class, int.class).invoke(null, "x", which)).isEqualTo(1);
		}
	}

	@Test
	void onlyACompleteBlockIsAppendedToAPlainBody() {
		MethodCode waiting = new MethodCode();
		waiting.iconst_0().ifeq(waiting.newLabel());
		assertThatIllegalStateException().isThrownBy(() -> new MethodCode().append(waiting))
			.withMessageContaining("never bound");
		MethodCode context = new MethodCode(new OperandStack());
		assertThatIllegalArgumentException().isThrownBy(() -> context.append(new MethodCode().iconst_0()));
	}

	// Over a compile context's body, every instruction reaches its operand-stack model,
	// and a branch whose label is bound reconciles the model.
	@Test
	void aContextLayerKeepsTheOperandStackModelInStep() {
		ConstantPool cp = new ConstantPool();
		OperandStack stack = new OperandStack();
		MethodCode c = new MethodCode(stack);
		MethodRefEntry valueOf = cp.methodRef("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
		c.lconst_1().invokestatic(valueOf);
		assertThat(stack.snapshot()).containsExactly(OperandStack.Slot.REF);
		MethodCode.Label other = c.newLabel();
		MethodCode.Label join = c.newLabel();
		c.iconst_0().ifeq(other).pop().aconst_null().goto_(join);
		assertThat(stack.isReachable()).isFalse();
		c.labelBinding(other);
		assertThat(stack.snapshot()).as("the branch's shape, established at its label")
			.containsExactly(OperandStack.Slot.REF);
		c.pop().aconst_null();
		c.labelBinding(join);
		assertThat(stack.snapshot()).containsExactly(OperandStack.Slot.REF);
		c.loadLocal(TypeKind.LONG, 2).storeLocal(TypeKind.LONG, 4);
		c.ldc(cp.entries().doubleEntry(1.5)).ldc(cp.stringEntry("s"));
		assertThat(stack.snapshot()).containsExactly(OperandStack.Slot.REF, OperandStack.Slot.DOUBLE,
				OperandStack.Slot.REF);
		assertThat(stack.maxDepth()).isEqualTo(4);
	}

	// Two paths reaching one label with different stacks make a class the verifier
	// rejects: refused where the label is bound.
	@Test
	void aJoinReachedWithTwoShapesIsRefused() {
		MethodCode c = new MethodCode(new OperandStack());
		MethodCode.Label join = c.newLabel();
		c.iconst_0().ifeq(join).iconst_1();
		assertThatIllegalStateException().isThrownBy(() -> c.labelBinding(join)).withMessageContaining("mismatch");
	}

	private static List<Opcode> opcodes(byte[] classFile, String method) {
		List<Opcode> opcodes = new ArrayList<>();
		for (CodeElement element : code(classFile, method).elementList()) {
			if (element instanceof Instruction instruction && instruction.opcode() != Opcode.NOP) {
				opcodes.add(instruction.opcode());
			}
		}
		return opcodes;
	}

	private static int codeLength(byte[] classFile, String method) {
		return code(classFile, method).codeLength();
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

	/** {@code class <name> extends Object}, its methods written through MethodCode. */
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
