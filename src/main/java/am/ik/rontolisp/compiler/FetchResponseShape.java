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
 * The single source of truth for the CLIENT-side HTTP result shape: the
 * {@code (:status :headers :body)} plist a {@code rontolisp:fetch} future settles to. The
 * shape is declared ONCE, as the WIT {@code record} below (the house {@code record} =
 * keyword plist convention, {@link WitTypeMapper.Rep#PLIST}), and every backend derives
 * its plist builder and readers from the parsed fields instead of hand-writing the shape:
 *
 * <ul>
 * <li><strong>Interpreter</strong> — the {@code Environment} fetch runtime loops over
 * {@link #responseFields()} when assembling the plist.</li>
 * <li><strong>JVM</strong> — {@code JvmAsyncRuntimeBuilder} iterates the fields at
 * codegen time, so the emitted bytecode carries the derived keywords in record
 * order.</li>
 * <li><strong>WASM component</strong> — {@code eval/HttpLibrary} splices
 * {@link #lispHelpersSource()}, the generated builder/accessor defuns {@code http.lisp}'s
 * fetch half calls in place of literal {@code (list :status ...)} forms.</li>
 * </ul>
 *
 * <p>
 * This class is the fetch-only remainder of the pre-Clack {@code HttpPlistShape}: the
 * SERVER side ({@code rontolisp:http-handler}) no longer has a plist shape of its own —
 * since the Clack cutover a handler receives the Clack environment ({@link ClackEnv}) and
 * returns the Clack response list, and {@code http-server.lisp} owns that contract.
 * {@code rontolisp:fetch} deliberately keeps this plist unchanged: it is the client side,
 * a different thing.
 *
 * <p>
 * A consumer that switches over the fields must throw on an unknown field name, and a
 * by-key reader guards itself with {@link #requireResponseHandled(Set)} — so adding a
 * field to the record here fails each backend loudly at build/compile time until its
 * per-backend value extraction (the one part that cannot be derived) is supplied.
 *
 * <p>
 * Documented deviations from the settled WIT mapping ({@link WitTypeMapper}), all
 * predating it and user-facing: {@code headers} crosses as an alist of dotted
 * {@code (name . value)} conses, not the positional 2-lists a
 * {@code list<tuple<string, string>>} would map to; a missing {@code status} defaults to
 * {@link #RESPONSE_STATUS_DEFAULT} and a missing {@code body} to
 * {@link #RESPONSE_BODY_DEFAULT}; a {@code body} may also be an eager string, not only a
 * stream. Defaults live here (WIT cannot express them) so they too are written once.
 */
public final class FetchResponseShape {

	/**
	 * The WIT declaration of the plist shape. This is rontolisp's own record —
	 * {@code wasi:http@0.3.0} models the response as a resource with accessor methods, so
	 * the plist shape needs its own authoritative record — and it is never emitted into a
	 * component: it exists to be parsed, here, as the one place the shape is written.
	 */
	static final String WIT = """
			package rontolisp:http-plist;

			interface plist {
			  /// The response plist a rontolisp:fetch future settles to.
			  record response {
			    status: u16,
			    headers: list<tuple<string, string>>,
			    body: stream<u8>,
			  }

			  /// The request a rontolisp:fetch call states: the fetch options plus the
			  /// url. On a --no-wasi --host-fetch reactor this record IS the JSON the
			  /// injected env.fetch import carries (field name = JSON key; headers as an
			  /// array of [name, value] pairs; an absent option crosses as an absent key),
			  /// and the response record above is the success arm of what comes back --
			  /// with the body an eager string there, the whole reply having arrived when
			  /// the import call returned.
			  record request {
			    url: string,
			    method: string,
			    headers: list<tuple<string, string>>,
			    body: option<string>,
			  }
			}
			""";

	/**
	 * The error arm of the {@code --host-fetch} envelope: a host whose transport failed
	 * answers {@code {"<this key>": "<reason>"}} instead of the response record, and the
	 * reactor fetch runtime signals that reason (WIT would spell the pair as a
	 * {@code result<response, string>}; JSON has no variant, so the arm is this reserved
	 * key).
	 */
	public static final String HOST_ENVELOPE_ERROR_KEY = "error";

	/** The response {@code status} used when the plist has none. */
	public static final int RESPONSE_STATUS_DEFAULT = 200;

	/** The response {@code body} used when the plist has none. */
	public static final String RESPONSE_BODY_DEFAULT = "";

	private static final List<Field> RESPONSE_FIELDS;

	private static final List<Field> REQUEST_FIELDS;

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
		RESPONSE_FIELDS = fieldsOf(plist, "response");
		REQUEST_FIELDS = fieldsOf(plist, "request");
	}

	private FetchResponseShape() {
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
	 * The response plist fields, in record (= plist) order.
	 * @return the fields
	 */
	public static List<Field> responseFields() {
		return RESPONSE_FIELDS;
	}

	/**
	 * The request record's fields, in record (= envelope) order — what a
	 * {@code --host-fetch} request JSON carries.
	 * @return the fields
	 */
	public static List<Field> requestFields() {
		return REQUEST_FIELDS;
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
	 * forms, generated from the record: a positional builder (parameters named after the
	 * fields, in record order) and a per-field accessor applying the declared default.
	 * {@code eval/HttpLibrary} splices them next to {@code http.lisp}; unreferenced
	 * helpers are dropped by its reachability walk.
	 * @return the generated Lisp source
	 */
	public static String lispHelpersSource() {
		StringBuilder source = new StringBuilder();
		appendBuilder(source, "%http-response-plist", RESPONSE_FIELDS);
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
