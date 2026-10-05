package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.jvm.ConstantPool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code METHOD_NAMES} rosters the runtime-helper gates are recognized by.
 * <p>
 * {@code JvmLispCompiler} decides whether a gate under-predicted by matching an
 * unresolved own-class call against these sets: a name that a builder emits but the
 * roster omits turns a recoverable under-prediction into a hard compile error, and a name
 * in the roster that no builder emits would force a gate on for a call the gate cannot
 * satisfy. Both directions are checked here against what the builders actually produce,
 * so the rosters cannot drift away from them silently ({@code .kb/adjustable-arrays.md}).
 */
class JvmRuntimeGroupNamesTest {

	private static final String TO_STRING_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

	@Test
	void theArrayRuntimeRosterIsExactlyWhatTheBuilderEmits() {
		ConstantPool cp = new ConstantPool();
		ClassEntry selfClass = cp.classEntry("Test");
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		MethodRefEntry lispToString = selfMethod(cp, selfClass, "_lispToString", TO_STRING_DESC);
		MethodRefEntry lispToDisplayString = selfMethod(cp, selfClass, "_lispToDisplayString", TO_STRING_DESC);

		List<JvmArrayRuntimeBuilder.ArrayMethod> emitted = new ArrayList<>(
				JvmArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, selfClass, false,
						new JvmOperandTypeRuntime.SubseqRuntime(cp, selfClass, null, new ArrayList<>())));
		emitted.addAll(JvmArrayRuntimeBuilder.buildToStringMethods(cp, lispToString, lispToDisplayString, selfClass,
				new JvmRuntimeBuilder.RenderGuardRefs(cp.fieldRef(selfClass, "_renderPath", "[Ljava/lang/Object;"),
						cp.fieldRef(selfClass, "_renderDepth", "I"), objectClass, cp.stringEntry("#"))));

		assertThat(emitted.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, JvmArrayRuntimeBuilder.METHOD_NAMES));
	}

	@Test
	void theHashRuntimeRosterIsExactlyWhatTheBuilderEmits() {
		ConstantPool cp = new ConstantPool();
		ClassEntry selfClass = cp.classEntry("Test");
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry equal = selfMethod(cp, selfClass, "_equal", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodRefEntry eqv = selfMethod(cp, selfClass, "_eqv", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodRefEntry strv = selfMethod(cp, selfClass, "_strv", "(Ljava/lang/Object;)Ljava/lang/Object;");
		ClassEntry stringArrayClass = cp.classEntry("[Ljava/lang/String;");

		List<JvmHashRuntimeBuilder.HashMethod> emitted = JvmHashRuntimeBuilder.build(cp, selfClass, objectClass,
				objectArrayClass, longValueOf, equal, eqv, strv, stringArrayClass, false, false, null, null);

		assertThat(emitted.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, JvmHashRuntimeBuilder.METHOD_NAMES));
	}

	@Test
	void theEqualpFoldRosterIsExactlyWhatTheBuilderAddsForIt() {
		ConstantPool cp = new ConstantPool();
		ClassEntry selfClass = cp.classEntry("Test");
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry equal = selfMethod(cp, selfClass, "_equal", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodRefEntry eqv = selfMethod(cp, selfClass, "_eqv", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodRefEntry strv = selfMethod(cp, selfClass, "_strv", "(Ljava/lang/Object;)Ljava/lang/Object;");
		ClassEntry stringArrayClass = cp.classEntry("[Ljava/lang/String;");

		List<JvmHashRuntimeBuilder.HashMethod> folding = JvmHashRuntimeBuilder.build(cp, selfClass, objectClass,
				objectArrayClass, longValueOf, equal, eqv, strv, stringArrayClass, true, false, null, null);

		Set<String> names = new java.util.LinkedHashSet<>(JvmHashRuntimeBuilder.METHOD_NAMES);
		names.addAll(JvmHashRuntimeBuilder.EQUALP_METHOD_NAMES);
		assertThat(folding.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, names));
	}

	@Test
	void theIdentityRosterIsExactlyWhatTheBuilderAddsForIt() {
		ConstantPool cp = new ConstantPool();
		ClassEntry selfClass = cp.classEntry("Test");
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry equal = selfMethod(cp, selfClass, "_equal", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodRefEntry eqv = selfMethod(cp, selfClass, "_eqv", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodRefEntry strv = selfMethod(cp, selfClass, "_strv", "(Ljava/lang/Object;)Ljava/lang/Object;");
		ClassEntry stringArrayClass = cp.classEntry("[Ljava/lang/String;");

		List<JvmHashRuntimeBuilder.HashMethod> identity = JvmHashRuntimeBuilder.build(cp, selfClass, objectClass,
				objectArrayClass, longValueOf, equal, eqv, strv, stringArrayClass, false, true, null, null);

		Set<String> names = new java.util.LinkedHashSet<>(JvmHashRuntimeBuilder.METHOD_NAMES);
		names.addAll(JvmHashRuntimeBuilder.IDENTITY_METHOD_NAMES);
		assertThat(identity.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, names));
	}

	private static MethodRefEntry selfMethod(ConstantPool cp, ClassEntry selfClass, String name, String desc) {
		return cp.methodRef(selfClass, name, desc);
	}

	// The pool de-duplicates, so re-adding a name yields the very index the builder used.
	private static Set<Integer> indicesOf(ConstantPool cp, Set<String> names) {
		return names.stream().map(name -> cp.utf8Entry(name).index()).collect(Collectors.toSet());
	}

}
