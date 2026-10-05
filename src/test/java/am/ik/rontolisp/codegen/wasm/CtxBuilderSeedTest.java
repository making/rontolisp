package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.SymbolPrintTable;
import am.ik.wasm.WasmWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@code Ctx.builder(proto)} hands a fresh context EVERYTHING its prototype was built
 * from. Every synchronous top-level chunk and every async resume body is compiled in a
 * context made this way, so a field the copy missed would make a form answer differently
 * at the top level than inside a defun -- the shape of every bug the old field-by-field
 * copy produced. The pin is reflective, so a field added to {@code Ctx.Builder} is
 * checked without this test being edited.
 */
class CtxBuilderSeedTest {

	/** Set by the caller of a seeded builder, never inherited. */
	private static final Set<String> NOT_INHERITED = Set.of("writer", "bodyStream");

	/**
	 * Values for the field types that have no generic distinct value: their builder
	 * default is {@code null} or a shared constant, which a missed copy would reproduce.
	 */
	private static final Map<Class<?>, Supplier<Object>> DISTINCT = Map.of(WasmLispCompiler.StringTable.class,
			() -> new WasmLispCompiler.StringTable(0, false, false), WasmOperandTypes.Operators.class,
			() -> new WasmOperandTypes.Operators(Map.of(), 7, Set.of(), null, null), WasmUncaughtLocations.Module.class,
			() -> new WasmUncaughtLocations.Module(WasmReportLocations.values()[0], List.of(), false, false),
			SymbolPrintTable.class,
			() -> new SymbolPrintTable(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), false),
			ClosRegistry.class, ClosRegistry::new, am.ik.rontolisp.macro.BakedSymbolAccess.class,
			() -> am.ik.rontolisp.macro.BakedSymbolAccess.of(new am.ik.rontolisp.PackageResolver()));

	@Test
	void aSeededBuilderCarriesEveryFieldOfItsPrototype() throws Exception {
		ByteArrayOutputStream protoBody = new ByteArrayOutputStream();
		WasmLispCompiler.Ctx.Builder original = WasmLispCompiler.Ctx.builder()
			.writer(new WasmWriter(protoBody))
			.bodyStream(protoBody);
		List<Field> fields = builderFields();
		assertThat(fields).hasSizeGreaterThan(50);
		int ordinal = 0;
		for (Field field : fields) {
			if (!NOT_INHERITED.contains(field.getName())) {
				field.set(original, distinctValue(field, field.get(original), ++ordinal));
			}
		}
		WasmLispCompiler.Ctx proto = original.build();

		WasmLispCompiler.Ctx.Builder seeded = WasmLispCompiler.Ctx.builder(proto);
		for (Field field : fields) {
			if (NOT_INHERITED.contains(field.getName())) {
				assertThat(field.get(seeded)).as(field.getName()).isNull();
				continue;
			}
			Object expected = field.get(original);
			Object actual = field.get(seeded);
			if (field.getType().isPrimitive()) {
				assertThat(actual).as(field.getName()).isEqualTo(expected);
			}
			else {
				assertThat(actual).as(field.getName()).isSameAs(expected);
			}
		}

		// And the context the seeded builder makes is the prototype's twin.
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmLispCompiler.Ctx fresh = seeded.writer(new WasmWriter(body)).bodyStream(body).build();
		for (Field field : fields) {
			if (NOT_INHERITED.contains(field.getName())) {
				continue;
			}
			Field ctxField = WasmLispCompiler.Ctx.class.getDeclaredField(field.getName());
			ctxField.setAccessible(true);
			if (field.getType().isPrimitive()) {
				assertThat(ctxField.get(fresh)).as(field.getName()).isEqualTo(ctxField.get(proto));
			}
			else {
				assertThat(ctxField.get(fresh)).as(field.getName()).isSameAs(ctxField.get(proto));
			}
		}
	}

	private static List<Field> builderFields() {
		List<Field> fields = new ArrayList<>();
		for (Field field : WasmLispCompiler.Ctx.Builder.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
				continue;
			}
			field.setAccessible(true);
			fields.add(field);
		}
		return fields;
	}

	/**
	 * A value no fresh builder holds: a primitive moved off its default, a NEW collection
	 * or array (compared by identity), another enum constant, or a fabricated instance.
	 */
	private static Object distinctValue(Field field, Object current, int ordinal) {
		Class<?> type = field.getType();
		if (type == boolean.class) {
			return !(Boolean) current;
		}
		if (type == int.class) {
			return (Integer) current + 1000 + ordinal;
		}
		if (type.isArray()) {
			return Array.newInstance(type.getComponentType(), 1);
		}
		if (type == Set.class) {
			return new HashSet<>();
		}
		if (type == Map.class) {
			return new HashMap<>();
		}
		if (type == List.class) {
			return new ArrayList<>();
		}
		if (type.isEnum()) {
			for (Object constant : type.getEnumConstants()) {
				if (constant != current) {
					return constant;
				}
			}
		}
		Supplier<Object> supplier = DISTINCT.get(type);
		if (supplier != null) {
			return supplier.get();
		}
		if (current != null && current != freshDefault(field)) {
			// Each builder allocates its own (a memo, a quote-global allocator): the
			// instance already is one no other builder holds.
			return current;
		}
		return fail("no distinct value for Ctx.Builder.%s (%s): add one to DISTINCT", field.getName(), type.getName());
	}

	private static Object freshDefault(Field field) {
		try {
			return field.get(WasmLispCompiler.Ctx.builder());
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
