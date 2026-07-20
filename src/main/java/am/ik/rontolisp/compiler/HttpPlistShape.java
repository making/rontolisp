package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.wit.WitDocument;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitType;
import org.jspecify.annotations.Nullable;

/**
 * The single source of truth for the HTTP plist shapes — the request plist a
 * {@code rontolisp:http-handler} handler receives and the response plist it returns,
 * which is also the plist a {@code rontolisp:fetch} future settles to. The shape is
 * declared ONCE, as the WIT {@code record} pair below (the house {@code record} = keyword
 * plist convention, {@link WitTypeMapper.Rep#PLIST}), and every backend derives its plist
 * builders and readers from the parsed fields instead of hand-writing the shape:
 *
 * <ul>
 * <li><strong>Interpreter</strong> — {@code LispEvaluator.invokeHttpHandler} and the
 * {@code Environment} fetch runtime loop over {@link #requestFields()} /
 * {@link #responseFields()} when assembling and reading the plists.</li>
 * <li><strong>JVM</strong> — {@code JvmHttpHandlerRuntimeBuilder} and
 * {@code JvmAsyncRuntimeBuilder} iterate the fields at codegen time, so the emitted
 * bytecode carries the derived keywords in record order.</li>
 * <li><strong>WASM component</strong> — {@code eval/HttpLibrary} splices
 * {@link #lispHelpersSource()}, the generated builder/accessor defuns {@code http.lisp}
 * calls in place of literal {@code (list :method ...)} forms.</li>
 * </ul>
 *
 * <p>
 * A consumer that switches over the fields must throw on an unknown field name, and a
 * by-key reader guards itself with {@link #requireResponseHandled(Set)} — so adding a
 * field to a record here fails each backend loudly at build/compile time until its
 * per-backend value extraction (the one part that cannot be derived) is supplied.
 *
 * <p>
 * Documented deviations from the settled WIT mapping ({@link WitTypeMapper}), all
 * predating it and user-facing: {@code headers} crosses as an alist of dotted
 * {@code (name . value)} conses, not the positional 2-lists a
 * {@code list<tuple<string, string>>} would map to; a missing response {@code status}
 * defaults to {@link #RESPONSE_STATUS_DEFAULT} and a missing response {@code body} to
 * {@link #RESPONSE_BODY_DEFAULT}; a response {@code body} may also be an eager string,
 * not only a stream. Defaults live here (WIT cannot express them) so they too are written
 * once.
 */
public final class HttpPlistShape {

	/**
	 * The WIT declaration of the two plist shapes. This is rontolisp's own record pair —
	 * {@code wasi:http@0.3.0} models request/response as resources with accessor methods,
	 * so the plist shape needs its own authoritative record — and it is never emitted
	 * into a component: it exists to be parsed, here, as the one place the shape is
	 * written.
	 */
	static final String WIT = """
			package rontolisp:http-plist;

			interface plist {
			  /// The request plist a rontolisp:http-handler handler receives.
			  record request {
			    method: string,
			    path: string,
			    query: option<string>,
			    headers: list<tuple<string, string>>,
			    body: stream<u8>,
			  }

			  /// The response plist a handler returns, and the plist a rontolisp:fetch
			  /// future settles to.
			  record response {
			    status: u16,
			    headers: list<tuple<string, string>>,
			    body: stream<u8>,
			  }
			}
			""";

	/** The response {@code status} used when the plist has none. */
	public static final int RESPONSE_STATUS_DEFAULT = 200;

	/** The response {@code body} used when the plist has none. */
	public static final String RESPONSE_BODY_DEFAULT = "";

	private static final List<Field> REQUEST_FIELDS;

	private static final List<Field> RESPONSE_FIELDS;

	static {
		WitDocument document = WitParser.parse(WIT);
		WitItem.InterfaceDef plist = null;
		for (WitItem item : document.items()) {
			if (item instanceof WitItem.InterfaceDef iface && "plist".equals(iface.name())) {
				plist = iface;
			}
		}
		if (plist == null) {
			throw new IllegalStateException("http-plist WIT lacks the plist interface");
		}
		REQUEST_FIELDS = fieldsOf(plist, "request");
		RESPONSE_FIELDS = fieldsOf(plist, "response");
	}

	private HttpPlistShape() {
	}

	/**
	 * One plist entry derived from a WIT record field: the field name, the keyword it
	 * crosses as (the component record-marshalling rule, {@code ":" + upcased name}) and
	 * the WIT type.
	 *
	 * @param name the WIT field name
	 * @param keyword the plist keyword ({@code :name})
	 * @param type the WIT field type
	 */
	public record Field(String name, String keyword, WitType type) {
	}

	/**
	 * The request plist fields, in record (= plist) order.
	 * @return the fields
	 */
	public static List<Field> requestFields() {
		return REQUEST_FIELDS;
	}

	/**
	 * The response plist fields, in record (= plist) order.
	 * @return the fields
	 */
	public static List<Field> responseFields() {
		return RESPONSE_FIELDS;
	}

	/**
	 * The response field of the given name.
	 * @param name the field name
	 * @return the field
	 * @throws IllegalStateException when the record has no such field
	 */
	public static Field responseField(String name) {
		for (Field field : RESPONSE_FIELDS) {
			if (field.name().equals(name)) {
				return field;
			}
		}
		throw new IllegalStateException("The http-plist response record has no field '" + name + "'");
	}

	/**
	 * Asserts that a by-key response reader handles exactly the record's fields — the
	 * guard a reader that cannot loop (each field needs structurally different code)
	 * places next to its field handling, so a record change fails it at build time.
	 * @param handled the field names the reader handles
	 * @throws IllegalStateException when the handled set and the record disagree
	 */
	public static void requireResponseHandled(Set<String> handled) {
		Set<String> expected = new LinkedHashSet<>();
		for (Field field : RESPONSE_FIELDS) {
			expected.add(field.name());
		}
		if (!expected.equals(new HashSet<>(handled))) {
			throw new IllegalStateException("A response-plist reader handles " + handled
					+ " but the http-plist response record declares " + expected);
		}
	}

	/**
	 * The Lisp helper defuns the WASM component path uses in place of hand-written plist
	 * forms, generated from the records: a positional builder per record (parameters
	 * named after the fields, in record order) and a per-field accessor applying the
	 * declared default. {@code eval/HttpLibrary} splices them next to {@code http.lisp};
	 * unreferenced helpers are dropped by its reachability walk.
	 * @return the generated Lisp source
	 */
	public static String lispHelpersSource() {
		StringBuilder source = new StringBuilder();
		appendBuilder(source, "%http-request-plist", REQUEST_FIELDS);
		appendBuilder(source, "%http-response-plist", RESPONSE_FIELDS);
		for (Field field : REQUEST_FIELDS) {
			appendAccessor(source, "%http-request-" + field.name(), field, null);
		}
		for (Field field : RESPONSE_FIELDS) {
			appendAccessor(source, "%http-response-" + field.name(), field, responseDefaultExpr(field.name()));
		}
		return source.toString();
	}

	// The Lisp literal for a response field's absent-key default, or null when nil (the
	// plain getf result) is the right absence value.
	private static @Nullable String responseDefaultExpr(String name) {
		return switch (name) {
			case "status" -> Integer.toString(RESPONSE_STATUS_DEFAULT);
			case "body" -> "\"" + RESPONSE_BODY_DEFAULT + "\"";
			default -> null;
		};
	}

	private static void appendBuilder(StringBuilder source, String name, List<Field> fields) {
		source.append("(defun ").append(name).append(" (");
		for (int i = 0; i < fields.size(); i++) {
			if (i > 0) {
				source.append(' ');
			}
			source.append(fields.get(i).name());
		}
		source.append(")\n  (list");
		for (Field field : fields) {
			source.append(' ').append(field.keyword()).append(' ').append(field.name());
		}
		source.append("))\n");
	}

	private static void appendAccessor(StringBuilder source, String name, Field field, @Nullable String defaultExpr) {
		source.append("(defun ").append(name).append(" (plist)\n  ");
		String get = "(getf plist " + field.keyword() + ")";
		if (defaultExpr == null) {
			source.append(get);
		}
		else {
			source.append("(or ").append(get).append(' ').append(defaultExpr).append(')');
		}
		source.append(")\n");
	}

	private static List<Field> fieldsOf(WitItem.InterfaceDef iface, String recordName) {
		for (WitItem item : iface.items()) {
			if (item instanceof WitItem.RecordDef record && recordName.equals(record.name())) {
				List<Field> fields = new ArrayList<>();
				for (WitItem.Field field : record.fields()) {
					// Classifiability sanity check: every field type must map to a
					// settled representation.
					WitTypeMapper.rep(field.type());
					// The plist keyword is the UPCASED field name: the reader upcases
					// user spellings ((getf resp :status) reads :STATUS), so every
					// backend emits/reads the upcased key; the generated Lisp helpers
					// and internal sources spell it uppercase literally.
					fields.add(new Field(field.name(), ":" + field.name().toUpperCase(java.util.Locale.ROOT),
							field.type()));
				}
				return List.copyOf(fields);
			}
		}
		throw new IllegalStateException("http-plist WIT lacks the " + recordName + " record");
	}

}
