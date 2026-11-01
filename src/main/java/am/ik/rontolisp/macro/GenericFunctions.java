package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The generic-function protocol runtime ({@code generic-functions.lisp}): generic
 * function and method METAOBJECTS ({@code ensure-generic-function}, {@code add-method},
 * {@code remove-method}, {@code find-method}, closer-mop's {@code ensure-method} and the
 * readers), the run-time method tables every dispatcher consults, and the
 * {@code add-method} a {@code defmethod} reaches on a generic whose {@code defgeneric}
 * named a generic-function class.
 *
 * <p>
 * Lisp source read with the real {@link LispReader}, like {@link MopProtocol}: the
 * compile paths prepend {@link #forms()} from
 * {@code LispMacroExpander.expandTopLevelDefinitions} when a program names the protocol
 * ({@code LispMacroExpander.usesGenericFunctionProtocol}); the interpreter evaluates the
 * same forms at the first use.
 */
public final class GenericFunctions {

	/** The {@code cl} names of the protocol. */
	private static final Set<String> CL_NAMES = Set.of(LispNames.ENSURE_GENERIC_FUNCTION, LispNames.ADD_METHOD,
			LispNames.REMOVE_METHOD, LispNames.FIND_METHOD, LispNames.METHOD_QUALIFIERS);

	/** The {@code closer-mop} member names of the protocol. */
	private static final Set<String> CLOSER_MOP_NAMES = Set.of("ENSURE-METHOD", "GENERIC-FUNCTION-NAME",
			"GENERIC-FUNCTION-METHODS", "GENERIC-FUNCTION-METHOD-CLASS", "GENERIC-FUNCTION-ARGUMENT-PRECEDENCE-ORDER",
			"GENERIC-FUNCTION-METHOD-COMBINATION", "GENERIC-FUNCTION-DECLARATIONS", "METHOD-SPECIALIZERS",
			"METHOD-LAMBDA-LIST", "METHOD-FUNCTION", "METHOD-GENERIC-FUNCTION", "INTERN-EQL-SPECIALIZER",
			"EQL-SPECIALIZER-OBJECT");

	/** The {@code closer-mop} package. */
	private static final String CLOSER_MOP = "CLOSER-MOP";

	/**
	 * The function names the runtime defines and a program reaches the protocol through,
	 * as the interpreter binds them ({@code cl} names bare, the closer-mop ones
	 * {@code CLOSER-MOP:}-qualified).
	 */
	public static final List<String> ENTRY_NAMES = entryNames();

	@Nullable private static volatile List<LispVal> forms;

	private GenericFunctions() {
	}

	private static List<String> entryNames() {
		List<String> names = new java.util.ArrayList<>(CL_NAMES);
		for (String member : CLOSER_MOP_NAMES) {
			names.add(CLOSER_MOP + ":" + member);
		}
		return List.copyOf(names);
	}

	/**
	 * Whether a symbol name is one of the protocol's operators -- a {@code cl} name or a
	 * {@code closer-mop} one -- the reference that brings the runtime into a program.
	 * @param name the symbol name as spelled
	 * @return true for a protocol operator
	 */
	public static boolean isProtocolName(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		if (qualified == null) {
			return CL_NAMES.contains(name);
		}
		return CLOSER_MOP.equals(qualified.pkg()) ? CLOSER_MOP_NAMES.contains(qualified.member())
				: "COMMON-LISP".equals(qualified.pkg()) && CL_NAMES.contains(qualified.member());
	}

	/**
	 * Whether a generic-function name a {@code defmethod} spells is {@code add-method} or
	 * {@code remove-method}: a user method on one needs the system method first.
	 * @param name the generic's name
	 * @return true for the two
	 */
	public static boolean isMethodSetGenericName(LispVal name) {
		return name instanceof LispSymbol sym
				&& (LispNames.ADD_METHOD.equals(sym.name()) || LispNames.REMOVE_METHOD.equals(sym.name()));
	}

	/**
	 * The runtime's definition forms, in definition order. Parsed once and cached; the
	 * list is immutable.
	 * @return the definitions
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (GenericFunctions.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource()));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = GenericFunctions.class.getResourceAsStream("generic-functions.lisp")) {
			if (in == null) {
				throw new IllegalStateException("generic-functions.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
