package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The runtime of the slot-access protocol ({@code slot-protocol.lisp}): an instance whose
 * class was defined with a {@code :metaclass} reaches its slots through
 * {@code slot-value-using-class} and its three siblings, whose system methods read the
 * instance's own storage -- contextl's special slots are methods on them.
 *
 * <p>
 * Lisp source read with the real {@link LispReader}, like {@link MopProtocol}: the
 * compile paths prepend {@link #forms()} from
 * {@code LispMacroExpander.expandTopLevelDefinitions} when a program carries a
 * {@code :metaclass} defclass and a method on one of the four generics; the interpreter
 * evaluates the same forms when it first meets such a method.
 */
public final class SlotProtocol {

	/** The member names of the protocol's generic functions. */
	private static final Set<String> GENERIC_MEMBERS = Set.of("SLOT-VALUE-USING-CLASS", "SLOT-BOUNDP-USING-CLASS",
			"SLOT-MAKUNBOUND-USING-CLASS");

	/** The package the protocol's generics live in. */
	private static final String PACKAGE = "CLOSER-MOP";

	@Nullable private static volatile List<LispVal> forms;

	private SlotProtocol() {
	}

	/**
	 * The runtime's definition forms, in definition order. Parsed once and cached; the
	 * list is immutable.
	 * @return the definitions
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (SlotProtocol.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource()));
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Whether a generic-function name (as a {@code defmethod} or {@code defgeneric}
	 * spells it, a {@code (setf name)} or its {@code %setf-} writer included) names one
	 * of the protocol's generics: {@code closer-mop:slot-value-using-class} and its setf
	 * function, {@code closer-mop:slot-boundp-using-class},
	 * {@code closer-mop:slot-makunbound-using-class}.
	 * @param name the name
	 * @return true for a protocol generic
	 */
	public static boolean isProtocolGenericName(LispVal name) {
		if (name instanceof LispCons setf && setf.car() instanceof LispSymbol head && "SETF".equals(member(head.name()))
				&& setf.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol place) {
			return isProtocolGenericName(place.name());
		}
		return name instanceof LispSymbol sym && isProtocolGenericName(sym.name());
	}

	/**
	 * The string form of {@link #isProtocolGenericName(LispVal)}.
	 * @param name the generic's name as spelled
	 * @return true for a protocol generic
	 */
	public static boolean isProtocolGenericName(String name) {
		String spelled = name.startsWith(ClosRegistry.SETF_FUNCTION_PREFIX)
				? name.substring(ClosRegistry.SETF_FUNCTION_PREFIX.length()) : name;
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(spelled);
		return qualified != null && PACKAGE.equals(qualified.pkg()) && GENERIC_MEMBERS.contains(qualified.member());
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		return qualified == null ? name : qualified.member();
	}

	private static String readSource() {
		try (InputStream in = SlotProtocol.class.getResourceAsStream("slot-protocol.lisp")) {
			if (in == null) {
				throw new IllegalStateException("slot-protocol.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
