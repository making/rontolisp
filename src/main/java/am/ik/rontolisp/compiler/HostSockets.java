package am.ik.rontolisp.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The boundary a {@code --no-wasi} module's sockets cross ({@code --host-sockets}): the
 * import module the socket provider's host variant names ({@code runner-sockets.lisp},
 * {@code #+rontolisp-host-sockets}), its fields with their shapes, and which of them its
 * host answers by suspending the module. Three packages that may not import each other
 * need them -- {@code eval} splices the provider, {@code codegen.wasm} reads the imports
 * to guard the exports, and {@link HostGlueEmitter} writes the host half over
 * {@code connect()} of {@code cloudflare:sockets} -- so they live here, as
 * {@link ReactorEnvelope}'s do.
 */
public final class HostSockets {

	/** The import module. */
	public static final String MODULE = "tcp";

	/**
	 * The fields a JavaScript host answers with a promise: the one wait every read and
	 * connect waits in, a write's completion and a close, which sends what was written
	 * first. Every other field answers at once.
	 */
	public static final Set<String> SUSPENDING = Set.of("wait", "flush", "close");

	/**
	 * One field's shape.
	 *
	 * @param params the parameter types
	 * @param result the result type
	 */
	public record Shape(List<BoundaryType> params, BoundaryType result) {
	}

	/** Every field with its shape, in the order the provider declares them. */
	public static final Map<String, Shape> FIELDS = fields();

	private HostSockets() {
	}

	private static Map<String, Shape> fields() {
		BoundaryType number = BoundaryType.S32;
		Map<String, Shape> fields = new LinkedHashMap<>();
		fields.put("socket", new Shape(List.of(), number));
		fields.put("begin", new Shape(List.of(number, BoundaryType.STRING, number), number));
		fields.put("end", new Shape(List.of(number), number));
		fields.put("upgrade", new Shape(List.of(number, BoundaryType.STRING), number));
		fields.put("shutdown", new Shape(List.of(number, number), number));
		fields.put("option", new Shape(List.of(number, number, number), number));
		fields.put("read", new Shape(List.of(number, number), BoundaryType.BYTES));
		fields.put("write", new Shape(List.of(number, BoundaryType.OCTETS), number));
		fields.put("flush", new Shape(List.of(number), number));
		fields.put("close", new Shape(List.of(number), BoundaryType.VOID));
		fields.put("localPort", new Shape(List.of(number), number));
		fields.put("localAddress", new Shape(List.of(number), BoundaryType.STRING));
		fields.put("peerAddress", new Shape(List.of(number), BoundaryType.STRING));
		fields.put("peerPort", new Shape(List.of(number), number));
		fields.put("readable", new Shape(List.of(number), number));
		fields.put("poll", new Shape(List.of(BoundaryType.OCTETS), number));
		fields.put("wait", new Shape(List.of(BoundaryType.OCTETS, number), number));
		fields.put("lastError", new Shape(List.of(), BoundaryType.STRING));
		return java.util.Collections.unmodifiableMap(fields);
	}

	/**
	 * Whether an import is one of the provider's: the module, a field of it and the
	 * field's shape. A program's own import of the same module and another shape is not.
	 * @param module the import module
	 * @param field the import field
	 * @param params the declared parameter types
	 * @param result the declared result type
	 * @return {@code true} for the provider's import
	 */
	public static boolean declares(String module, String field, List<BoundaryType> params, BoundaryType result) {
		Shape shape = MODULE.equals(module) ? FIELDS.get(field) : null;
		return shape != null && shape.params().equals(params) && shape.result() == result;
	}

}
