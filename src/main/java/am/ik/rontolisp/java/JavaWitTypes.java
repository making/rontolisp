package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import am.ik.rontolisp.java.JavaBoundary.Rep;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping of a WIT type onto a Java type, which the two annotations share: a
 * {@code @WitImport} interface's methods and a {@code @WitExport} class's are checked
 * against it ({@link Table}), and the values of the boundary -- the settled Common Lisp
 * tier, {@code rontolisp.internal.Wit} -- are converted by it, in generated Java source
 * ({@link Converters}): into Java what the host answers an import and what it hands an
 * export, out of Java what the program hands an import and what an export answers.
 *
 * <p>
 * The named types of a WIT scope -- an interface, or a world's own -- are the member
 * types of one Java class: the {@code @WitImport} interface, the {@code @WitExport} class
 * for its world, its member class for an interface the world exports.
 */
final class JavaWitTypes {

	private JavaWitTypes() {
	}

	/** Why a tuple is refused in an error arm a {@code WitException} carries. */
	static final String TUPLE_ARM = "a tuple, which is a record of the program's where a type is written "
			+ "for it, and none is written for an error arm thrown: the method answers a WitResult of it";

	/** The library class of the boundary's values. */
	static final String VALUES = "rontolisp.internal.Wit";

	/** What a WIT {@code stream<u8>} the host answers is. */
	static final String INPUT_STREAM = "java.io.InputStream";

	// ---- the check

	/**
	 * The declared Java types of one WIT scope, checked against the mapping: each named
	 * type's definition once.
	 */
	abstract static class Table {

		/** The class whose member types the scope's named types are. */
		final JavaClass owner;

		/** The WIT scope as a message names it: an interface's id, {@code world w}. */
		final String scope;

		/** What declares the member types, as a message names it. */
		private final String declarer;

		/** Where a declaration with no source of its own is reported. */
		private final Source source;

		/** The named types checked, by the Java class standing for each. */
		private final Set<JavaClass> checked = new java.util.HashSet<>();

		Table(JavaClass owner, String scope, String declarer, Source source) {
			this.owner = owner;
			this.scope = scope;
			this.declarer = declarer;
			this.source = source;
		}

		/**
		 * Checks the interface standing for a resource of the scope, whose methods are
		 * the resource's.
		 */
		abstract void resource(String name, JavaClass named);

		/**
		 * Checks what a method answers: nothing for no result; for a {@code result} its
		 * {@code WitResult}, or its ok arm with the error arm a {@code WitException}'s
		 * value ({@link JavaWitImports#thrown}); else the result's type.
		 */
		void expectAnswer(JavaBoundary.@Nullable Type result, JavaClass.Method method, String what) {
			if (result == null) {
				expectVoid(method, what);
			}
			else if (JavaWitImports.thrown(result, method.returnType)) {
				JavaBoundary.Type ok = result.element();
				if (ok == null) {
					expectVoid(method, what);
				}
				else {
					expect(ok, method.returnType, false, method, what + ": its result's ok arm");
				}
				JavaBoundary.Type error = result.error();
				if (error != null) {
					// thrown as a WitException's value: the Java type is the mapping's
					// own
					expect(error, null, true, method, what + ": its result's error arm");
				}
			}
			else {
				expect(result, method.returnType, false, method, what + ": its result");
			}
		}

		void expectVoid(JavaClass.Method method, String what) {
			if (method.returnType != Primitive.VOID) {
				throw at(method, what + " answers nothing, so it is void, not " + method.returnType);
			}
		}

		/**
		 * Checks a declared type against the WIT type's mapping.
		 * @param declared the type, or {@code null} where none is written (an error arm):
		 * then what the mapping names is checked to exist
		 * @param boxed whether the place takes a reference type (a type argument)
		 */
		void expect(JavaBoundary.Type wit, @Nullable JavaType declared, boolean boxed, JavaClass.Method method,
				String what) {
			String refusal = refusal(wit, declared == null);
			if (refusal != null) {
				throw at(method, what + " is " + wit.wit() + ", " + refusal);
			}
			if (declared != null && !matches(wit, declared, boxed, method, what)) {
				throw at(method, what + " is " + wit.wit() + ", which is "
						+ javaText(this.owner, wit, boxed, declared, true) + " in Java, not " + declared);
			}
			definitions(wit, declared, method, what);
		}

		/** Whether a declared type is the one the WIT type maps onto. */
		boolean matches(JavaBoundary.Type wit, JavaType declared, boolean boxed, JavaClass.Method method, String what) {
			return switch (wit.rep()) {
				case INT, BIGNUM_INT, FLOAT, BOOLEAN, CHARACTER ->
					boxed ? isClass(declared, box(scalar(wit))) : declared == scalar(wit);
				case STRING -> isClass(declared, "java.lang.String");
				case BYTE_STRING -> declared.equals(new ArrayType(Primitive.BYTE));
				case LIST, NIL_OR_VALUE -> declared instanceof ClassType type
						&& type.declaration().name
							.equals(wit.rep() == Rep.LIST ? "java.util.List" : "java.util.Optional")
						&& type.arguments().size() == 1
						&& matches(Objects.requireNonNull(wit.element()), type.arguments().get(0), true, method, what);
				case TUPLE_LIST -> {
					JavaClass record = declared instanceof ClassType type && type.arguments().isEmpty()
							? type.declaration() : null;
					List<JavaClass.Field> components = record != null && record.isRecord ? components(record)
							: List.of();
					boolean all = record != null && record.isRecord && components.size() == wit.parts().size();
					for (int i = 0; all && i < components.size(); i++) {
						all = matches(Objects.requireNonNull(wit.parts().get(i).type()), components.get(i).type, false,
								method, what);
					}
					yield all;
				}
				case RESULT ->
					declared instanceof ClassType type && type.declaration().name.equals(JavaWitImports.WIT_RESULT)
							&& type.arguments().size() == 2 && arm(wit.element(), type.arguments().get(0), method, what)
							&& arm(wit.error(), type.arguments().get(1), method, what);
				case KEYWORD_LIST -> declared instanceof ClassType type
						&& type.declaration().name.equals("java.util.Set") && type.arguments().size() == 1
						&& type.arguments().get(0).equals(new ClassType(member(wit, method, what), List.of()));
				case STREAM_HANDLE -> isU8(wit.element()) ? isClass(declared, INPUT_STREAM)
						: declared instanceof ClassType type
								&& type.declaration().name.equals(JavaWitImports.WIT_STREAM)
								&& type.arguments().size() == 1 && matches(Objects.requireNonNull(wit.element()),
										type.arguments().get(0), true, method, what);
				case FUTURE_HANDLE -> declared instanceof ClassType type
						&& type.declaration().name.equals(JavaWitImports.WIT_FUTURE) && type.arguments().size() == 1
						&& futureMatches(Objects.requireNonNull(wit.element()), type.arguments().get(0), method, what);
				case PLIST, TAGGED_LIST, KEYWORD, HANDLE ->
					declared.equals(new ClassType(member(wit, method, what), List.of()));
				default -> false;
			};
		}

		/**
		 * Whether a type argument is a result's arm: {@code Void} for one carrying
		 * nothing.
		 */
		private boolean arm(JavaBoundary.@Nullable Type wit, JavaType declared, JavaClass.Method method, String what) {
			return wit == null ? isClass(declared, "java.lang.Void") : matches(wit, declared, true, method, what);

		}

		/**
		 * Whether a future's value type is what its payload maps onto: a result's ok arm
		 * ({@code Void} for none), or the payload.
		 */
		private boolean futureMatches(JavaBoundary.Type payload, JavaType declared, JavaClass.Method method,
				String what) {
			if (payload.rep() != Rep.RESULT) {
				return matches(payload, declared, true, method, what);
			}
			JavaBoundary.Type ok = payload.element();
			return ok == null ? isClass(declared, "java.lang.Void") : matches(ok, declared, true, method, what);
		}

		/**
		 * Checks each named type a type holds against its definition, once: the member
		 * class of its name, a record's components, a variant's cases, an enum's
		 * constants, a resource's methods.
		 */
		private void definitions(JavaBoundary.Type wit, @Nullable JavaType declared, JavaClass.Method method,
				String what) {
			switch (wit.rep()) {
				case LIST, NIL_OR_VALUE, STREAM_HANDLE ->
					definitions(Objects.requireNonNull(wit.element()), argument(declared), method, what);
				case RESULT -> {
					if (wit.element() != null) {
						definitions(wit.element(), argument(declared, 0), method, what);
					}
					if (wit.error() != null) {
						definitions(wit.error(), argument(declared, 1), method, what);
					}
				}
				case FUTURE_HANDLE -> {
					JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
					if (payload.rep() != Rep.RESULT) {
						definitions(payload, argument(declared), method, what);
						break;
					}
					if (payload.element() != null) {
						definitions(payload.element(), argument(declared), method, what);
					}
					if (payload.error() != null) {
						// thrown by get() as a WitException's value, as a result's error
						// arm is
						expect(payload.error(), null, true, method, what + ": its future's error arm");
					}
				}
				case TUPLE_LIST -> {
					if (declared == null) {
						throw at(method, what + " is " + wit.wit() + ", " + TUPLE_ARM);
					}
					List<JavaClass.Field> components = components(((ClassType) declared).declaration());
					for (int i = 0; i < components.size(); i++) {
						definitions(Objects.requireNonNull(wit.parts().get(i).type()), components.get(i).type, method,
								what);
					}
				}
				case PLIST, TAGGED_LIST, KEYWORD, KEYWORD_LIST, HANDLE ->
					definition(wit, member(wit, method, what), method);
				default -> {
				}
			}
		}

		/**
		 * The class standing for a named type the scope takes from another, or
		 * {@code null} for a type of the scope's own, its owner's member type.
		 */
		@Nullable JavaClass used(JavaBoundary.Type wit, JavaClass.Method method, String what) {
			return null;
		}

		/** The class a named type is: the interface's member type of its name. */
		private JavaClass member(JavaBoundary.Type wit, JavaClass.Method method, String what) {
			JavaClass used = used(wit, method, what);
			if (used != null) {
				return used;
			}
			String definition = Objects.requireNonNull(wit.definition());
			String name = JavaWitImports.upperCamel(definition);
			JavaClass named = this.owner.members.get(name);
			String kind = switch (wit.rep()) {
				case PLIST -> "a record";
				case TAGGED_LIST -> "a sealed interface";
				case KEYWORD, KEYWORD_LIST -> "an enum";
				default -> "an interface";
			};
			if (named == null) {
				throw at(method, what + " is " + wit.wit() + ", which is " + this.owner.simpleName() + "." + name
						+ " in Java, " + kind + " " + this.declarer + " declares; it declares none of that name");
			}
			return named;
		}

		/** Checks the class a named type is against its definition, once. */
		private void definition(JavaBoundary.Type wit, JavaClass named, JavaClass.Method method) {
			if (!this.checked.add(named)) {
				return;
			}
			String definition = Objects.requireNonNull(wit.definition());
			String what = this.owner.simpleName() + "." + named.simpleName() + " (" + definition + " of " + this.scope
					+ ")";
			switch (wit.rep()) {
				case PLIST -> {
					if (!named.isRecord) {
						throw at(named, what + " is a record in Java");
					}
					Map<String, JavaBoundary.Part> fields = new LinkedHashMap<>();
					for (JavaBoundary.Part part : wit.parts()) {
						fields.put(JavaWitImports.lowerCamel(part.label()), part);
					}
					List<JavaClass.Field> components = components(named);
					if (!fields.keySet().equals(names(components))) {
						throw at(named, what + " has the components " + String.join(", ", fields.keySet()) + ", not "
								+ String.join(", ", names(components)));
					}
					for (JavaClass.Field component : components) {
						JavaBoundary.Part field = Objects.requireNonNull(fields.get(component.name));
						expect(Objects.requireNonNull(field.type()), component.type, false, method,
								what + ": its field " + field.label());
					}
				}
				case TAGGED_LIST -> {
					if (!named.isInterface || !named.isSealed) {
						throw at(named, what + " is a variant: a sealed interface in Java, a record each case");
					}
					Map<String, JavaBoundary.Part> cases = new LinkedHashMap<>();
					for (JavaBoundary.Part part : wit.parts()) {
						cases.put(JavaWitImports.upperCamel(part.label()), part);
					}
					Set<String> permitted = new LinkedHashSet<>();
					for (JavaClass subclass : named.permits) {
						permitted.add(subclass.simpleName());
					}
					if (!cases.keySet().equals(permitted)) {
						throw at(named, what + " permits the records " + String.join(", ", cases.keySet())
								+ ", one a case, not " + String.join(", ", permitted));
					}
					for (JavaClass subclass : named.permits) {
						JavaBoundary.Part found = Objects.requireNonNull(cases.get(subclass.simpleName()));
						List<JavaClass.Field> components = subclass.isRecord ? components(subclass) : List.of();
						int wanted = found.type() == null ? 0 : 1;
						if (!subclass.isRecord || components.size() != wanted) {
							throw at(subclass, what + ": its case " + found.label() + " is a record of "
									+ (wanted == 0 ? "no component" : "one component, its payload"));
						}
						if (found.type() != null) {
							expect(found.type(), components.get(0).type, false, method,
									what + ": the payload of its case " + found.label());
						}
					}
				}
				case KEYWORD, KEYWORD_LIST -> {
					if (!named.isEnum) {
						throw at(named, what + " is an enum in Java");
					}
					Set<String> labels = new LinkedHashSet<>();
					for (JavaBoundary.Part part : wit.parts()) {
						labels.add(JavaWitImports.constant(part.label()));
					}
					Set<String> constants = new LinkedHashSet<>();
					for (JavaClass.Field field : named.fields) {
						if (field.isConstant) {
							constants.add(field.name);
						}
					}
					if (!labels.equals(constants)) {
						throw at(named, what + " has the constants " + String.join(", ", labels) + ", not "
								+ String.join(", ", constants));
					}
				}
				case HANDLE -> resource(definition, named);
				default -> throw new IllegalStateException(wit.rep() + " is no named type");
			}
		}

		LispReadException at(JavaClass.Method method, String message) {
			JavaSyntax.Method written = method.declaration;
			if (written != null && method.owner.source != null) {
				return JavaClasses.error(method.owner.source, written.pos(), message);
			}
			return at(this.owner, message);
		}

		LispReadException at(JavaClass named, String message) {
			JavaSyntax.ClassDeclaration written = named.declaration;
			return JavaClasses.error(named.source == null ? this.source : named.source,
					written == null ? 0 : written.pos(), message);
		}

	}

	// ---- the conversions

	/**
	 * The conversions of one WIT scope's values, written as static methods of a class of
	 * generated Java source: a scalar's inline, a composite type's a method written once.
	 */
	abstract static class Converters {

		/** The class whose member types the scope's named types are. */
		final JavaClass owner;

		/** The WIT scope as a message names it. */
		final String scope;

		/** The generated class the converters are static methods of, as Java names it. */
		final String binding;

		/**
		 * Whether the boundary takes a {@code u32} as its value -- the canonical ABI's, a
		 * component's or a {@code --no-wasi} module's -- and not as the {@code s32} of
		 * its bits, a Preview 1 module's import.
		 */
		private final boolean unsignedValue;

		/** The converters' text. */
		final StringBuilder converters = new StringBuilder();

		/** The converters written, by what they convert. */
		private final Map<String, String> written = new HashMap<>();

		Converters(JavaClass owner, String scope, String binding, boolean unsignedValue) {
			this.owner = owner;
			this.scope = scope;
			this.binding = binding;
			this.unsignedValue = unsignedValue;
		}

		/** The class a resource's handle is in the generated source, by WIT name. */
		abstract String handleClass(String resource);

		/** The class reading a stream or a future the host answers. */
		abstract String asyncClass(JavaBoundary.Type wit, @Nullable JavaType declared);

		/**
		 * An expression of the Java value a boundary value is.
		 * @param declared the type written for it, or {@code null} for an error arm's
		 * value, whose type the mapping names
		 */
		String in(JavaBoundary.Type wit, @Nullable JavaType declared, String value) {
			String v = VALUES;
			return switch (wit.rep()) {
				case BOOLEAN -> v + ".inBoolean(" + value + ")";
				case INT, BIGNUM_INT -> switch (scalar(wit)) {
					case LONG -> v + ".inLong(" + value + ")";
					case INT -> "(int) " + v + ".inLong(" + value + ")";
					case SHORT -> "(short) " + v + ".inLong(" + value + ")";
					default -> "(byte) " + v + ".inLong(" + value + ")";
				};
				case FLOAT -> (scalar(wit) == Primitive.FLOAT ? "(float) " : "") + v + ".inDouble(" + value + ")";
				case CHARACTER -> v + ".inCodePoint(" + value + ")";
				case STRING -> v + ".inString(" + value + ")";
				case BYTE_STRING -> v + ".inBytes(" + value + ")";
				default -> converter("in", wit, declared) + "(" + value + ")";
			};
		}

		/** An expression of the boundary value a Java value is. */
		String out(JavaBoundary.Type wit, @Nullable JavaType declared, String value) {
			String v = VALUES;
			return switch (wit.rep()) {
				case BOOLEAN -> v + ".outBoolean(" + value + ")";
				// an unsigned integer as the value its bits are: an import through the
				// canonical ABI (a component's, a --no-wasi module's) takes the value; a
				// Preview 1 module's takes a u32 as the s32 of its bits, which the int
				// holds, and neither has a value of a u64 past a long's range, which
				// traps
				// there
				case INT, BIGNUM_INT -> switch (Objects.requireNonNull(wit.definition())) {
					case "u8" -> v + ".outUnsigned(" + value + ", 8)";
					case "u16" -> v + ".outUnsigned(" + value + ", 16)";
					case "u32" ->
						this.unsignedValue ? v + ".outUnsigned(" + value + ", 32)" : v + ".outSigned(" + value + ")";
					default -> v + ".outSigned(" + value + ")";
				};
				case FLOAT -> v + ".outDouble(" + value + ")";
				case CHARACTER -> v + ".outCodePoint(" + value + ")";
				case STRING -> v + ".outString(" + value + ")";
				case BYTE_STRING -> v + ".outBytes(" + value + ")";
				default -> converter("out", wit, declared) + "(" + value + ")";
			};
		}

		/** The static method converting a value of a composite type, written once. */
		private String converter(String direction, JavaBoundary.Type wit, @Nullable JavaType declared) {
			String key = direction + " " + wit + " " + declared;
			String known = this.written.get(key);
			if (known != null) {
				return this.binding + "." + known;
			}
			String name = direction + this.written.size();
			this.written.put(key, name);
			String javaType = javaText(this.owner, wit, false, declared, false);
			String v = VALUES;
			StringBuilder body = new StringBuilder();
			boolean in = direction.equals("in");
			body.append("    static ")
				.append(in ? javaType : "Object")
				.append(' ')
				.append(name)
				.append('(')
				.append(in ? "Object" : javaType)
				.append(" v) {\n");
			switch (wit.rep()) {
				case LIST -> {
					JavaBoundary.Type element = Objects.requireNonNull(wit.element());
					JavaType declaredElement = argument(declared);
					String elementText = javaText(this.owner, element, true, declaredElement, false);
					if (in) {
						body.append("        java.util.ArrayList<")
							.append(elementText)
							.append("> out = new java.util.ArrayList<>();\n")
							.append("        for (Object p = v; p != null; p = ")
							.append(v)
							.append(".rest(p)) {\n            out.add(")
							.append(in(element, declaredElement, v + ".first(p)"))
							.append(");\n        }\n        return out;\n");
					}
					else {
						body.append("        Object out = null;\n        for (")
							.append(elementText)
							.append(" e : v) {\n            out = ")
							.append(v)
							.append(".cons(")
							.append(out(element, declaredElement, "e"))
							.append(", out);\n        }\n        return ")
							.append(v)
							.append(".reversed(out);\n");
					}
				}
				case NIL_OR_VALUE -> {
					JavaBoundary.Type element = Objects.requireNonNull(wit.element());
					JavaType declaredElement = argument(declared);
					if (in) {
						body.append("        return v == null ? java.util.Optional.empty() : java.util.Optional.of(")
							.append(in(element, declaredElement, "v"))
							.append(");\n");
					}
					else {
						body.append("        return v.isPresent() ? ")
							.append(out(element, declaredElement, "v.get()"))
							.append(" : null;\n");
					}
				}
				case TUPLE_LIST -> {
					JavaClass record = ((ClassType) Objects.requireNonNull(declared)).declaration();
					List<JavaClass.Field> components = components(record);
					if (in) {
						body.append("        Object p = v;\n");
						List<String> made = new ArrayList<>();
						for (int i = 0; i < components.size(); i++) {
							body.append("        Object e")
								.append(i)
								.append(" = ")
								.append(v)
								.append(".first(p);\n        p = ")
								.append(v)
								.append(".rest(p);\n");
							made.add(in(Objects.requireNonNull(wit.parts().get(i).type()), components.get(i).type,
									"e" + i));
						}
						body.append("        return new ")
							.append(record.name)
							.append('(')
							.append(String.join(", ", made))
							.append(");\n");
					}
					else {
						StringBuilder list = new StringBuilder("null");
						for (int i = components.size() - 1; i >= 0; i--) {
							list.insert(0,
									v + ".cons("
											+ out(Objects.requireNonNull(wit.parts().get(i).type()),
													components.get(i).type, "v." + components.get(i).name + "()")
											+ ", ")
								.append(')');
						}
						body.append("        return ").append(list).append(";\n");
					}
				}
				case PLIST -> {
					JavaClass record = memberClass(wit);
					Map<String, JavaBoundary.Part> fields = new LinkedHashMap<>();
					for (JavaBoundary.Part part : wit.parts()) {
						fields.put(JavaWitImports.lowerCamel(part.label()), part);
					}
					if (in) {
						List<String> made = new ArrayList<>();
						for (JavaClass.Field component : components(record)) {
							JavaBoundary.Part field = Objects.requireNonNull(fields.get(component.name));
							made.add(in(Objects.requireNonNull(field.type()), component.type,
									v + ".field(v, \"" + field.label() + "\")"));
						}
						body.append("        return new ")
							.append(record.name)
							.append('(')
							.append(String.join(", ", made))
							.append(");\n");
					}
					else {
						Map<String, JavaClass.Field> byName = new HashMap<>();
						components(record).forEach(component -> byName.put(component.name, component));
						StringBuilder list = new StringBuilder("null");
						List<JavaBoundary.Part> parts = wit.parts();
						for (int i = parts.size() - 1; i >= 0; i--) {
							JavaBoundary.Part field = parts.get(i);
							JavaClass.Field component = Objects
								.requireNonNull(byName.get(JavaWitImports.lowerCamel(field.label())));
							list.insert(0,
									v + ".cons(" + v + ".label(\"" + field.label() + "\"), " + v + ".cons("
											+ out(Objects.requireNonNull(field.type()), component.type,
													"v." + component.name + "()")
											+ ", ")
								.append("))");
						}
						body.append("        return ").append(list).append(";\n");
					}
				}
				case TAGGED_LIST -> {
					JavaClass sealed = memberClass(wit);
					if (in) {
						body.append("        Object tag = ").append(v).append(".tag(v);\n");
					}
					for (JavaBoundary.Part part : wit.parts()) {
						JavaClass record = caseClass(sealed, part.label());
						JavaBoundary.Type payload = part.type();
						if (in) {
							body.append("        if (")
								.append(v)
								.append(".is(tag, \"")
								.append(part.label())
								.append("\")) {\n            return new ")
								.append(record.name)
								.append('(');
							if (payload != null) {
								body.append(in(payload, components(record).get(0).type, v + ".payload(v)"));
							}
							body.append(");\n        }\n");
						}
						else {
							body.append("        if (v instanceof ")
								.append(record.name)
								.append(") {\n            return ");
							if (payload == null) {
								body.append(v).append(".label(\"").append(part.label()).append("\")");
							}
							else {
								JavaClass.Field component = components(record).get(0);
								body.append(v)
									.append(".cons(")
									.append(v)
									.append(".label(\"")
									.append(part.label())
									.append("\"), ")
									.append(out(payload, component.type,
											"((" + record.name + ") v)." + component.name + "()"))
									.append(')');
							}
							body.append(";\n        }\n");
						}
					}
					body.append(in ? "        throw new IllegalStateException(\"no case of " + wit.definition() + " of "
							+ this.scope + "\");\n" : "        throw new NullPointerException();\n");
				}
				case RESULT -> {
					// the envelope (:ok . value) or (:error . value), the value of an arm
					// carrying nothing nil
					String arguments = javaType.substring(JavaWitImports.WIT_RESULT.length());
					String ok = JavaWitImports.WIT_RESULT + ".Ok" + arguments;
					String err = JavaWitImports.WIT_RESULT + ".Err" + arguments;
					JavaBoundary.Type okArm = wit.element();
					JavaBoundary.Type errorArm = wit.error();
					if (in) {
						String payload = v + ".payload(v)";
						body.append("        if (")
							.append(v)
							.append(".failed(v)) {\n            return new ")
							.append(err)
							.append('(')
							.append(errorArm == null ? "null" : in(errorArm, argument(declared, 1), payload))
							.append(");\n        }\n        return new ")
							.append(ok)
							.append('(')
							.append(okArm == null ? "null" : in(okArm, argument(declared, 0), payload))
							.append(");\n");
					}
					else {
						body.append("        if (v instanceof ")
							.append(JavaWitImports.WIT_RESULT)
							.append(".Ok) {\n            return ")
							.append(v)
							.append(".cons(")
							.append(v)
							.append(".label(\"ok\"), ")
							.append(okArm == null ? "null"
									: out(okArm, argument(declared, 0), "((" + ok + ") v).value()"))
							.append(");\n        }\n        return ")
							.append(v)
							.append(".cons(")
							.append(v)
							.append(".label(\"error\"), ")
							.append(errorArm == null ? "null"
									: out(errorArm, argument(declared, 1), "((" + err + ") v).error()"))
							.append(");\n");
					}
				}
				case KEYWORD -> {
					JavaClass named = memberClass(wit);
					for (JavaBoundary.Part part : wit.parts()) {
						if (in) {
							body.append("        if (")
								.append(v)
								.append(".is(v, \"")
								.append(part.label())
								.append("\")) {\n            return ")
								.append(named.name)
								.append('.')
								.append(JavaWitImports.constant(part.label()))
								.append(";\n        }\n");
						}
						else {
							body.append("        if (v == ")
								.append(named.name)
								.append('.')
								.append(JavaWitImports.constant(part.label()))
								.append(") {\n            return ")
								.append(v)
								.append(".label(\"")
								.append(part.label())
								.append("\");\n        }\n");
						}
					}
					body.append(in ? "        throw new IllegalStateException(\"no case of " + wit.definition() + " of "
							+ this.scope + "\");\n" : "        throw new NullPointerException();\n");
				}
				case KEYWORD_LIST -> {
					JavaClass named = memberClass(wit);
					if (in) {
						body.append("        java.util.Set<")
							.append(named.name)
							.append("> out = java.util.EnumSet.noneOf(")
							.append(named.name)
							.append(".class);\n        for (Object p = v; p != null; p = ")
							.append(v)
							.append(".rest(p)) {\n            Object k = ")
							.append(v)
							.append(".first(p);\n");
						for (JavaBoundary.Part part : wit.parts()) {
							body.append("            if (")
								.append(v)
								.append(".is(k, \"")
								.append(part.label())
								.append("\")) {\n                out.add(")
								.append(named.name)
								.append('.')
								.append(JavaWitImports.constant(part.label()))
								.append(");\n            }\n");
						}
						body.append("        }\n        return out;\n");
					}
					else {
						body.append("        Object out = null;\n");
						List<JavaBoundary.Part> parts = wit.parts();
						for (int i = parts.size() - 1; i >= 0; i--) {
							body.append("        if (v.contains(")
								.append(named.name)
								.append('.')
								.append(JavaWitImports.constant(parts.get(i).label()))
								.append(")) {\n            out = ")
								.append(v)
								.append(".cons(")
								.append(v)
								.append(".label(\"")
								.append(parts.get(i).label())
								.append("\"), out);\n        }\n");
						}
						body.append("        return out;\n");
					}
				}
				case HANDLE -> {
					String handle = handleClass(Objects.requireNonNull(wit.definition()));
					if (in) {
						body.append("        return new ")
							.append(handle)
							.append('(')
							.append(v)
							.append(".inHandle(v));\n");
					}
					else {
						body.append("        if (!(v instanceof ")
							.append(handle)
							.append(")) {\n            throw new IllegalArgumentException(\"a ")
							.append(wit.definition())
							.append(" of ")
							.append(this.scope)
							.append(" the host did not make\");\n        }\n        ")
							.append(handle)
							.append(" h = (")
							.append(handle)
							.append(") v;\n        int held = h.live();\n");
						if (!wit.borrow()) {
							// an own handle passes to the callee
							body.append("        h.handle = -1;\n");
						}
						body.append("        return ").append(v).append(".outHandle(held);\n");
					}
				}
				case STREAM_HANDLE, FUTURE_HANDLE -> {
					if (!in) {
						throw new IllegalStateException(wit.wit() + " is no value the program hands the host");
					}
					body.append("        return new ")
						.append(asyncClass(wit, declared))
						.append('(')
						.append(v)
						.append(".inHandle(v));\n");
				}
				default -> throw new IllegalStateException(wit.rep() + " has no converter");
			}
			body.append("    }\n");
			this.converters.append(body);
			return this.binding + "." + name;
		}

		/** The interface's member class a named type is. */
		private JavaClass memberClass(JavaBoundary.Type wit) {
			return Objects.requireNonNull(
					this.owner.members.get(JavaWitImports.upperCamel(Objects.requireNonNull(wit.definition()))));
		}

		/** A sealed interface's permitted record of a case's name. */
		private JavaClass caseClass(JavaClass sealed, String label) {
			for (JavaClass subclass : sealed.permits) {
				if (subclass.simpleName().equals(JavaWitImports.upperCamel(label))) {
					return subclass;
				}
			}
			throw new IllegalStateException("no case " + label + " of " + sealed);
		}

	}

	// ---- the types

	/**
	 * Why a type has no Java value, itself or a structural type it holds (a named type's
	 * own are its definition's to tell), or {@code null}.
	 * @param arm whether it is a result's error arm, which no type is written for
	 */
	static @Nullable String refusal(JavaBoundary.Type wit, boolean arm) {
		String own = switch (wit.rep()) {
			case STREAM_HANDLE -> wit.element() == null ? "a bare stream, which carries no value" : null;
			case FUTURE_HANDLE -> {
				JavaBoundary.Type payload = wit.element();
				yield payload == null ? "a bare future, which carries no value" : switch (payload.rep()) {
					// the read of a future its writer dropped answers nil, so a
					// value the boundary spells nil cannot be told from it
					case BOOLEAN, LIST, NIL_OR_VALUE, KEYWORD_LIST ->
						"a future of a type one of whose values the boundary cannot tell from a future its "
								+ "writer dropped";
					default -> null;
				};
			}
			case UNSUPPORTED -> "a type with no rontolisp value";
			case TUPLE_LIST -> arm ? TUPLE_ARM : null;
			// the boundary spells none as nil, so an element whose value can be nil
			// cannot be told from it
			case NIL_OR_VALUE -> switch (Objects.requireNonNull(wit.element()).rep()) {
				case BOOLEAN, LIST, KEYWORD_LIST, NIL_OR_VALUE ->
					"an option of a type one of whose values the boundary cannot tell from none";
				default -> null;
			};
			default -> null;
		};
		if (own != null) {
			return own;
		}
		List<JavaBoundary.Type> held = new ArrayList<>();
		if (wit.rep() == Rep.LIST || wit.rep() == Rep.NIL_OR_VALUE || wit.rep() == Rep.STREAM_HANDLE) {
			held.add(Objects.requireNonNull(wit.element()));
		}
		if (wit.rep() == Rep.FUTURE_HANDLE) {
			JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
			if (payload.rep() != Rep.RESULT) {
				held.add(payload);
			}
			else if (payload.element() != null) {
				// the error arm is checked as a result's is (definitions)
				held.add(payload.element());
			}
		}
		if (wit.rep() == Rep.TUPLE_LIST) {
			wit.parts().forEach(part -> held.add(Objects.requireNonNull(part.type())));
		}
		if (wit.rep() == Rep.RESULT) {
			Optional.ofNullable(wit.element()).ifPresent(held::add);
			Optional.ofNullable(wit.error()).ifPresent(held::add);
		}
		for (JavaBoundary.Type inner : held) {
			String refused = refusal(inner, arm);
			if (refused != null) {
				return "holding " + inner.wit() + ", " + refused;
			}
		}
		return null;
	}

	/** A record's components, in order. */
	static List<JavaClass.Field> components(JavaClass record) {
		List<JavaClass.Field> components = new ArrayList<>();
		JavaSyntax.ClassDeclaration written = Objects.requireNonNull(record.declaration);
		for (JavaSyntax.Parameter component : written.components()) {
			for (JavaClass.Field field : record.fields) {
				if (!field.isStatic && field.name.equals(component.name())) {
					components.add(field);
				}
			}
		}
		return components;
	}

	private static Set<String> names(List<JavaClass.Field> fields) {
		Set<String> names = new LinkedHashSet<>();
		fields.forEach(field -> names.add(field.name));
		return names;
	}

	/** Whether a stream's element is a byte: a {@code stream<u8>}. */
	static boolean isU8(JavaBoundary.@Nullable Type element) {
		return element != null && element.rep() == Rep.INT && "u8".equals(element.definition());
	}

	static boolean isClass(JavaType type, String name) {
		return type instanceof ClassType named && named.declaration().name.equals(name) && named.arguments().isEmpty();
	}

	static Primitive scalar(JavaBoundary.Type wit) {
		return scalar(Objects.requireNonNull(wit.definition()));
	}

	/**
	 * The Java type of a WIT scalar: an unsigned integer's the signed one of its width.
	 */
	static Primitive scalar(String wit) {
		return switch (wit) {
			case "bool" -> Primitive.BOOLEAN;
			case "s8", "u8" -> Primitive.BYTE;
			case "s16", "u16" -> Primitive.SHORT;
			case "s32", "u32", "char" -> Primitive.INT;
			case "s64", "u64" -> Primitive.LONG;
			case "f32" -> Primitive.FLOAT;
			case "f64" -> Primitive.DOUBLE;
			default -> throw new IllegalStateException(wit + " is no scalar");
		};
	}

	private static String boxText(Primitive primitive, boolean simple) {
		String box = box(primitive);
		return simple ? box.substring("java.lang.".length()) : box;
	}

	private static String box(Primitive primitive) {
		return switch (primitive) {
			case BOOLEAN -> "java.lang.Boolean";
			case BYTE -> "java.lang.Byte";
			case SHORT -> "java.lang.Short";
			case INT -> "java.lang.Integer";
			case LONG -> "java.lang.Long";
			case FLOAT -> "java.lang.Float";
			case DOUBLE -> "java.lang.Double";
			default -> throw new IllegalStateException(primitive + " has no box");
		};
	}

	/**
	 * The Java type a WIT type is, as Java source names it: fully qualified for the
	 * binding's source, by simple names for a message.
	 * @param owner the {@code @WitImport} interface, whose member types the named types
	 * are
	 * @param declared the type written for it, which a tuple's record is read from
	 */
	static String javaText(JavaClass owner, JavaBoundary.Type wit, boolean boxed, @Nullable JavaType declared,
			boolean simple) {
		return javaText(new Spelling() {
			@Override
			public String named(JavaBoundary.Type named, @Nullable JavaType written, boolean simpleName) {
				// a resource may be another interface's, which the type written for it
				// names
				return named.rep() == Rep.HANDLE && !simpleName && written instanceof ClassType type
						&& type.arguments().isEmpty() ? JavaAnnotations.typeText(type)
								: memberText(owner, named, simpleName);
			}

			@Override
			public String tuple(JavaBoundary.Type tuple, @Nullable JavaType written, boolean simpleName) {
				return written == null ? "a record of " + tuple.parts().size() + " components"
						: simpleName ? written.toString() : JavaAnnotations.typeText(written);
			}
		}, wit, boxed, declared, simple);
	}

	/**
	 * How the types a Java program declares for a WIT scope are named in source: the
	 * declared types of a program being checked, or the ones a scaffold is about to
	 * declare.
	 */
	interface Spelling {

		/**
		 * The class a record, a variant, an enum, flags (its enum) or a resource is.
		 * @param named the type
		 * @param declared the type written for it, if any
		 * @param simple whether by simple names, as a message names it
		 * @return the class as source names it
		 */
		String named(JavaBoundary.Type named, @Nullable JavaType declared, boolean simple);

		/**
		 * The record a tuple is.
		 * @param tuple the type
		 * @param declared the type written for it, if any
		 * @param simple whether by simple names
		 * @return the record as source names it
		 */
		String tuple(JavaBoundary.Type tuple, @Nullable JavaType declared, boolean simple);

	}

	/**
	 * The Java type a WIT type is, as Java source names it, each type the scope declares
	 * named by the spelling.
	 */
	static String javaText(Spelling spelling, JavaBoundary.Type wit, boolean boxed, @Nullable JavaType declared,
			boolean simple) {
		return switch (wit.rep()) {
			case INT, BIGNUM_INT, FLOAT, BOOLEAN, CHARACTER ->
				boxed ? boxText(scalar(wit), simple) : scalar(wit).toString();
			case STRING -> simple ? "String" : "java.lang.String";
			case BYTE_STRING -> "byte[]";
			case LIST, NIL_OR_VALUE -> (simple ? "" : "java.util.") + (wit.rep() == Rep.LIST ? "List<" : "Optional<")
					+ javaText(spelling, Objects.requireNonNull(wit.element()), true, argument(declared), simple) + ">";
			case TUPLE_LIST -> spelling.tuple(wit, declared, simple);
			case RESULT -> (simple ? "WitResult<" : JavaWitImports.WIT_RESULT + "<")
					+ armText(spelling, wit.element(), declared, 0, simple) + ", "
					+ armText(spelling, wit.error(), declared, 1, simple) + ">";
			case KEYWORD_LIST -> (simple ? "Set<" : "java.util.Set<") + spelling.named(wit, null, simple) + ">";
			case PLIST, TAGGED_LIST, KEYWORD, HANDLE -> spelling.named(wit, declared, simple);
			case STREAM_HANDLE -> isU8(wit.element()) ? simple ? "InputStream" : INPUT_STREAM
					: (simple ? "WitStream<" : JavaWitImports.WIT_STREAM + "<") + javaText(spelling,
							Objects.requireNonNull(wit.element()), true, argument(declared), simple) + ">";
			case FUTURE_HANDLE -> {
				JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
				JavaBoundary.Type value = payload.rep() == Rep.RESULT ? payload.element() : payload;
				yield (simple ? "WitFuture<" : JavaWitImports.WIT_FUTURE + "<")
						+ (value == null ? (simple ? "Void" : "java.lang.Void")
								: javaText(spelling, value, true, argument(declared), simple))
						+ ">";
			}
			default -> wit.wit();
		};
	}

	/** A result's arm as Java source names it: {@code Void} for one carrying nothing. */
	private static String armText(Spelling spelling, JavaBoundary.@Nullable Type arm, @Nullable JavaType declared,
			int index, boolean simple) {
		return arm == null ? simple ? "Void" : "java.lang.Void"
				: javaText(spelling, arm, true, argument(declared, index), simple);
	}

	static @Nullable JavaType argument(@Nullable JavaType declared) {
		return declared instanceof ClassType type && type.arguments().size() == 1 ? type.arguments().get(0) : null;
	}

	static @Nullable JavaType argument(@Nullable JavaType declared, int index) {
		return declared instanceof ClassType type && type.arguments().size() > index ? type.arguments().get(index)
				: null;
	}

	/** The name of the member class a named type is. */
	private static String memberText(JavaClass owner, JavaBoundary.Type wit, boolean simple) {
		return (simple ? owner.simpleName() : owner.name) + "."
				+ JavaWitImports.upperCamel(Objects.requireNonNull(wit.definition()));
	}

}
