package am.ik.jvm;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer against hand-built definitions, where every entry and every instruction is
 * known: a chain of static methods too large for one tiny class spreads over parts and
 * still runs, what cannot move stays, what the roots do not reach is not written, and an
 * unresolved own call is reported. The whole compiler's use of it -- programs, output
 * shapes, the forced split -- is {@code JvmLispCompilerSplitTest}.
 */
class JvmClassSplitterTest {

	private static final int CHAIN = 30;

	// Each class small enough that the chain needs several: every step brings its own
	// name, a string and the reference to the next step.
	private static final int TINY_BUDGET = 40;

	@Test
	void aChainTooLargeForOneClassRunsAcrossParts() throws Exception {
		ClassDefinition definition = chain(new ConstantPool());
		JvmClassSplitter.Split split = split(definition, null, named(definition, "result"), TINY_BUDGET);
		assertThat(split.parts()).as("the chain needs several classes").hasSizeGreaterThan(2);
		assertThat(split.parts().keySet()).allSatisfy(name -> assertThat(name).startsWith("SplitMe$Part"));

		Map<String, byte[]> classes = classes(split);
		for (byte[] bytes : classes.values()) {
			// Each class's own count stays within what it was filled to: the budget, plus
			// the Class entry of the next part its last step calls into.
			assertThat(poolCount(bytes)).isLessThanOrEqualTo(TINY_BUDGET + 2);
		}
		Class<?> main = new Loader(classes).loadClass("SplitMe");
		main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		assertThat(main.getMethod("result").invoke(null)).as("every step ran, whichever class holds it")
			.isEqualTo(CHAIN);
		// The main class is filled first, the parts take the rest in order: every step is
		// declared exactly once, and a moved method is no longer private.
		assertThat(declared(main)).contains("main", "result");
		List<String> steps = new ArrayList<>(declared(main).stream().filter(name -> name.startsWith("step")).toList());
		for (String part : split.parts().keySet()) {
			Class<?> partClass = new Loader(classes).loadClass(part.replace('/', '.'));
			for (Method method : partClass.getDeclaredMethods()) {
				assertThat(method.getName()).startsWith("step");
				assertThat(Modifier.isPrivate(method.getModifiers())).as("a part's methods are package-visible")
					.isFalse();
				steps.add(method.getName());
			}
		}
		assertThat(steps).hasSize(CHAIN).doesNotHaveDuplicates();
	}

	// Started past 65535, every index is one a class file cannot carry: the writer
	// re-mints each entry in its class's own pool.
	@Test
	void indexesPastTheFormatLimitAreRepointedIntoEachClassesPool() throws Exception {
		ClassDefinition definition = chain(ConstantPool.startingAt(70_000));
		JvmClassSplitter.Split split = split(definition, null, named(definition, "result"), TINY_BUDGET);
		Class<?> main = new Loader(classes(split)).loadClass("SplitMe");
		main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		assertThat(main.getMethod("result").invoke(null)).isEqualTo(CHAIN);
	}

	// A method whose identity is its class stays there whatever the budget: an instance
	// method, a synchronized one (the class is its monitor), one asking MethodHandles for
	// a lookup (the class is the lookup's), and the class initializer.
	@Test
	void whatCannotMoveStaysInTheMainClass() throws Exception {
		ConstantPool cp = new ConstantPool();
		Builder b = new Builder(cp);
		ConstantPool.MethodrefConstant lookup = cp.addMethodref(
				cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles")),
				cp.addNameAndType(cp.addUtf8("lookup"), cp.addUtf8("()Ljava/lang/invoke/MethodHandles$Lookup;")));
		b.method(AccessFlag.ACC_STATIC, "<clinit>", "()V", new MethodCode().return_());
		b.method(AccessFlag.ACC_PUBLIC, "instance", "()V", new MethodCode().return_());
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED, "locked", "()V",
				new MethodCode().return_());
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "looksUp", "()V",
				new MethodCode().invokestatic(lookup.entry()).pop().return_());
		for (int i = 0; i < 20; i++) {
			b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "free" + i, "()V",
					new MethodCode().ldc(cp.stringEntry("filler-" + i)).pop().return_());
		}
		JvmClassSplitter.Split split = split(b.build(), null, method -> false, TINY_BUDGET);
		assertThat(split.parts()).isNotEmpty();
		Class<?> main = new Loader(classes(split)).loadClass("SplitMe");
		assertThat(declared(main)).contains("instance", "locked", "looksUp");
		assertThat(declared(main).stream().filter(name -> name.startsWith("free"))).as("what can move did")
			.hasSizeLessThan(20);
	}

	// Asked to shake, the writer drops what the roots do not reach -- the method, and the
	// constants only it referenced -- and a definition that then fits one class is
	// written as one, every kept instruction as it was.
	@Test
	void aShakenDefinitionWritesOnlyWhatItsRootsReach() {
		ConstantPool cp = new ConstantPool();
		Builder b = new Builder(cp);
		ConstantPool.MethodrefConstant used = b.ref("used", "()V");
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				new MethodCode().invokestatic(used.entry()).return_());
		b.method(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, "used", "()V",
				new MethodCode().ldc(cp.stringEntry("kept")).pop().return_());
		b.method(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, "unused", "()V",
				new MethodCode().ldc(cp.stringEntry("dropped")).pop().return_());
		JvmClassSplitter.Split split = split(b.build(), Set.of("main"), method -> false, ConstantPool.MAX_INDEX);
		assertThat(split.parts()).isEmpty();
		assertThat(shape(split.mainClass()))
			.containsSequence("method used:()V", "flags " + (AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC),
					"ldc kept")
			.contains("method main:([Ljava/lang/String;)V")
			.doesNotContain("method unused:()V", "ldc dropped");
		assertThat(new String(split.mainClass(), java.nio.charset.StandardCharsets.ISO_8859_1))
			.doesNotContain("dropped");
	}

	@Test
	void unresolvedOwnCallsAreReportedWithTheirCallers() {
		ConstantPool cp = new ConstantPool();
		Builder b = new Builder(cp);
		ConstantPool.MethodrefConstant missing = b.ref("missing", "(Ljava/lang/Object;)Ljava/lang/Object;");
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				new MethodCode().aconst_null().invokestatic(missing.entry()).pop().return_());
		assertThat(JvmClassSplitter.unresolvedSelfMethods(b.build())).singleElement().satisfies(unresolved -> {
			assertThat(unresolved.name()).isEqualTo("missing");
			assertThat(unresolved.descriptor()).isEqualTo("(Ljava/lang/Object;)Ljava/lang/Object;");
			assertThat(unresolved.callers()).containsExactly("main");
		});
	}

	// main calls step0, each step bumps a private static counter and calls the next, and
	// result() reads the counter back: the chain works only if every cross-class call and
	// every field access from a part resolves.
	private static ClassDefinition chain(ConstantPool cp) {
		Builder b = new Builder(cp);
		ConstantPool.Utf8Constant counterName = cp.addUtf8("counter");
		ConstantPool.Utf8Constant intDesc = cp.addUtf8("I");
		b.definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, counterName, intDesc);
		ConstantPool.FieldrefConstant counter = cp.addFieldref(b.thisClass, cp.addNameAndType(counterName, intDesc));
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				new MethodCode().invokestatic(b.ref("step0", "()V").entry()).return_());
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "result", "()I",
				new MethodCode().getstatic(counter.entry()).ireturn());
		for (int k = 0; k < CHAIN; k++) {
			MethodCode code = new MethodCode().ldc(cp.stringEntry("text-" + k))
				.pop()
				.getstatic(counter.entry())
				.iconst_1()
				.iadd()
				.putstatic(counter.entry());
			if (k + 1 < CHAIN) {
				code.invokestatic(b.ref("step" + (k + 1), "()V").entry());
			}
			b.method(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, "step" + k, "()V", code.return_());
		}
		return b.build();
	}

	private static Predicate<ClassDefinition.Method> named(ClassDefinition definition, String name) {
		return method -> definition.cp().utf8At(method.name().index()).equals(name);
	}

	private static JvmClassSplitter.Split split(ClassDefinition definition, @Nullable Set<String> roots,
			Predicate<ClassDefinition.Method> pinned, int limit) {
		return JvmClassSplitter.write(definition, roots, pinned, limit, JvmClassSplitter.Target.of(61));
	}

	private static Map<String, byte[]> classes(JvmClassSplitter.Split split) {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put("SplitMe", split.mainClass());
		classes.putAll(split.parts());
		return classes;
	}

	// Header, members and each method's instructions, symbolically: what a class
	// declares and runs, whatever order its pool is in.
	private static List<String> shape(byte[] classFile) {
		ClassModel model = ClassFile.of().parse(classFile);
		List<String> shape = new ArrayList<>();
		shape.add("class " + model.thisClass().asInternalName() + " " + model.flags().flagsMask() + " extends "
				+ model.superclass().map(ClassEntry::asInternalName).orElse("-"));
		for (FieldModel field : model.fields()) {
			shape.add("field " + field.fieldName() + ":" + field.fieldType() + " " + field.flags().flagsMask());
		}
		for (MethodModel method : model.methods()) {
			shape.add("method " + method.methodName() + ":" + method.methodType());
			shape.add("flags " + method.flags().flagsMask());
			method.code().ifPresent(code -> code.forEach(element -> {
				if (element instanceof ConstantInstruction.LoadConstantInstruction ldc) {
					shape.add("ldc " + ldc.constantEntry().constantValue());
				}
				else if (element instanceof Instruction instruction) {
					shape.add(instruction.toString());
				}
			}));
		}
		return shape;
	}

	private static int poolCount(byte[] classFile) {
		return ((classFile[8] & 0xFF) << 8 | (classFile[9] & 0xFF)) - 1;
	}

	private static List<String> declared(Class<?> clazz) {
		return Arrays.stream(clazz.getDeclaredMethods()).map(Method::getName).toList();
	}

	/** A definition under construction: class SplitMe extends Object. */
	private static final class Builder {

		final ConstantPool cp;

		final ConstantPool.ClassConstant thisClass;

		final ClassDefinition.Builder definition;

		private final Map<String, ConstantPool.Utf8Constant> utf8 = new HashMap<>();

		Builder(ConstantPool cp) {
			this.cp = cp;
			this.thisClass = cp.addClass(cp.addUtf8("SplitMe"));
			this.definition = ClassDefinition.builder(cp, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, this.thisClass,
					cp.addClass(cp.addUtf8("java/lang/Object")), cp.addUtf8("Code"));
		}

		ConstantPool.MethodrefConstant ref(String name, String desc) {
			return this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(this.utf8(name), this.utf8(desc)));
		}

		void method(int access, String name, String desc, MethodCode code) {
			this.definition.addMethod(access, this.utf8(name), this.utf8(desc), code, List.of());
		}

		ClassDefinition build() {
			return this.definition.build();
		}

		private ConstantPool.Utf8Constant utf8(String s) {
			return this.utf8.computeIfAbsent(s, this.cp::addUtf8);
		}

	}

	/** Defines the written classes, the way a class path would serve them. */
	private static final class Loader extends ClassLoader {

		private final Map<String, byte[]> classes;

		Loader(Map<String, byte[]> classes) {
			super(JvmClassSplitterTest.class.getClassLoader());
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
