package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The exports of a program with no {@code main} and no {@code @WitExport} class compiled
 * to a module with no WASI ({@code --no-wasi}): the public static methods its entry class
 * declares, each a {@code rontolisp:wasm-export} named as the method is, so a host calls
 * them as it calls a Lisp reactor's ({@code .kb/java-frontend.md}, "A reactor of public
 * static methods"). No annotation and no dependency: under a JDK the class is an ordinary
 * library. A method's parameters and result are those the export boundary carries,
 * anything else refused at the method; a name is exported once.
 */
final class JavaReactorExports {

	/**
	 * The class whose methods are the exports, or {@code null} for a program with a main.
	 */
	@Nullable JavaClass entry;

	/**
	 * The directive exporting each public static method of the entry class, in the order
	 * the class declares them.
	 * @throws LispReadException where a method crosses no export boundary, or two share a
	 * name, at the method
	 */
	List<LispVal> directives() {
		JavaClass declaration = Objects.requireNonNull(this.entry);
		Map<String, JavaClass.Method> named = new LinkedHashMap<>();
		List<LispVal> forms = new ArrayList<>();
		for (JavaClass.Method method : declaration.methods) {
			JavaSyntax.Method written = method.declaration;
			if (written == null || !method.isStatic || method.isConstructor() || method.isPrivate
					|| !(declaration.isInterface || written.modifiers().contains("public"))) {
				continue;
			}
			String what = method.name + " is an export of the reactor " + declaration.simpleName()
					+ ", a public static method";
			JavaClass.Method other = named.put(method.name, method);
			if (other != null) {
				throw at(method,
						what + ", and so is " + other.name + other.parameterList() + ": a module exports a name once");
			}
			List<LispVal> params = new ArrayList<>();
			for (int i = 0; i < method.parameters.size(); i++) {
				JavaType type = method.parameters.get(i);
				LispSymbol crossing = boundary(type);
				if (crossing == null) {
					throw at(method, what + ": its parameter " + written.parameters().get(i).name() + " is " + type
							+ ", which no export carries (byte, short, int, long, double, boolean or String)");
				}
				params.add(crossing);
			}
			List<LispVal> form = new ArrayList<>(List.of(new LispSymbol("RONTOLISP:WASM-EXPORT"),
					JavaForms.quote(JavaNames.function(method)), new LispSymbol(":AS"), JavaForms.string(method.name),
					new LispSymbol(":PARAMS"), JavaForms.quote(JavaForms.list(params))));
			if (method.returnType != Primitive.VOID) {
				LispSymbol result = boundary(method.returnType);
				if (result == null) {
					throw at(method, what + ": its result is " + method.returnType
							+ ", which no export carries (void, byte, short, int, long, double, boolean or String)");
				}
				form.add(new LispSymbol(":RETURNS"));
				form.add(result);
			}
			forms.add(JavaForms.list(form));
		}
		if (forms.isEmpty()) {
			throw JavaClasses.error(declaration.source, Objects.requireNonNull(declaration.declaration).pos(),
					"no main method found in " + declaration.simpleName() + ", and no public static method, which a "
							+ "module compiled --no-wasi would export");
		}
		return forms;
	}

	/** The boundary type a Java type crosses an export as, or {@code null} for none. */
	private static @Nullable LispSymbol boundary(JavaType type) {
		String designator;
		if (type instanceof Primitive primitive) {
			designator = switch (primitive) {
				case BYTE -> ":S8";
				case SHORT -> ":S16";
				case INT -> ":S32";
				case LONG -> ":S64";
				case DOUBLE -> ":FLOAT";
				case BOOLEAN -> ":BOOL";
				default -> null;
			};
		}
		else {
			designator = type instanceof ClassType named && named.declaration().name.equals("java.lang.String")
					&& named.arguments().isEmpty() ? ":STRING" : null;
		}
		return designator == null ? null : new LispSymbol(designator);
	}

	private static LispReadException at(JavaClass.Method method, String message) {
		return JavaClasses.error(method.owner.source, Objects.requireNonNull(method.declaration).pos(), message);
	}

}
