package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.Version;
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
 * The one REQUEST fact every backend shares lives here too — {@link #USER_AGENT_HEADER}
 * and {@link #defaultUserAgent()}, the header fetch adds when the caller set none — for
 * the same reason: three transports that each default differently are three different
 * requests.
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
 * {@link #RESPONSE_BODY_DEFAULT}. Defaults live here (WIT cannot express them) so they
 * too are written once. The {@code stream<u8>} body is now what every backend really
 * answers: the {@code --host-fetch} reactor's eager string was the last exception and
 * went when its body left the JSON envelope for an import of its own
 * ({@code eval/HostFetchLibrary}).
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
			  /// its body left OUT, a second import (env.readResponseBody) carrying the
			  /// octets, so the reply's `body` key there is a fallback a host may fill
			  /// rather than the way a body normally crosses.
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

	/**
	 * The import-object key the {@code --host-fetch} transport lives under -- the reactor
	 * boundary's own module, so a host writes one {@code env} object and not two
	 * ({@link ReactorEnvelope#HOST_MODULE}).
	 */
	public static final String HOST_IMPORT_MODULE = ReactorEnvelope.HOST_MODULE;

	/**
	 * The field carrying the request and the reply HEAD: the {@code request} record above
	 * as JSON in, the {@code response} record (or the {@link #HOST_ENVELOPE_ERROR_KEY}
	 * arm) as JSON out. Both shapes are fixed by this class, which is what lets a
	 * generated host implement it rather than be asked for it.
	 */
	public static final String HOST_IMPORT_FIELD = "fetch";

	/**
	 * The field carrying the reply BODY, declared only on the streaming boundary
	 * ({@code --host-boundary=streaming}): {@code (ptr, cap) -> i32}, the same
	 * {@code read(2)} shape as the reactor's own body imports, with a NEGATIVE count
	 * reporting a transfer that failed after the head crossed. Its absence in a module is
	 * how a reader tells that the {@code body} key above carries the whole reply.
	 */
	public static final String HOST_BODY_IMPORT_FIELD = "readResponseBody";

	/**
	 * The reply head's REPLY-IDENTITY key, written only by a {@code --reentrant}
	 * streaming host: the id the host minted for THIS reply's body, which the module
	 * hands back on every {@code readResponseBody(id, ptr, cap)} pull. Overlapped calls
	 * (and a second fetch inside one call) then each drain their own reply, so nothing is
	 * superseded and the serialised boundary's one-cursor counter does not exist. A
	 * reserved key beside {@link #HOST_ENVELOPE_ERROR_KEY} rather than a field of the
	 * {@code response} record: the id is transport plumbing of one boundary shape, not
	 * part of the plist a {@code rontolisp:fetch} caller sees on any backend.
	 */
	public static final String HOST_BODY_ID_KEY = "body-id";

	/** The response {@code status} used when the plist has none. */
	public static final int RESPONSE_STATUS_DEFAULT = 200;

	/** The response {@code body} used when the plist has none. */
	public static final String RESPONSE_BODY_DEFAULT = "";

	/**
	 * The one request header {@code rontolisp:fetch} sets on the caller's behalf, so a
	 * caller-silent request goes out IDENTICALLY on every backend instead of carrying
	 * whatever the transport underneath happens to default to — the JDK's
	 * {@code Java-http-client/<jdk>} on the interpreter/JVM, nothing at all on the
	 * component (which some origins answer with a 4xx). The value is
	 * {@link #defaultUserAgent()}: the version AND the commit, so a request identifies
	 * the build that made it.
	 *
	 * <p>
	 * A caller who set the field owns it, in any spelling ({@link #isUserAgentHeader};
	 * HTTP field names are case-insensitive) and with any value, the empty string
	 * included: only the ABSENT case is filled in. Suppressing the field entirely is
	 * deliberately not offered — the JDK path cannot express it (with no field set the
	 * client writes its own), so a suppression option would be one more thing that means
	 * something different per backend.
	 */
	public static final String USER_AGENT_HEADER = "User-Agent";

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
	 * The {@link #USER_AGENT_HEADER} value a caller-silent request carries:
	 * {@code rontolisp/<version> (<git-commit>)} — the same version and abbreviated
	 * commit {@code rontolisp --version} and {@code rontolisp:version} report, so what an
	 * origin logs names the build that reached it and not just the release.
	 * @return the default user-agent value
	 */
	public static String defaultUserAgent() {
		return userAgent(Version.getVersion(), Version.getGitCommit());
	}

	/**
	 * Builds the user-agent value out of a version and a commit id — the seam
	 * {@link #defaultUserAgent()} reads {@link Version} through, so the composition is
	 * pinnable without a build that has to sit on a given commit. The commit is an RFC
	 * 9110 comment after the product token, and is left off entirely (rather than read as
	 * {@code (unknown)}) when the build had no git repository to take it from.
	 * @param version the project version
	 * @param commit the abbreviated git commit id, or {@link Version#UNKNOWN} when there
	 * was none
	 * @return the user-agent value
	 */
	static String userAgent(String version, String commit) {
		String product = "rontolisp/" + version;
		return isCommitId(commit) ? product + " (" + commit + ")" : product;
	}

	// Only a plain hash reaches the header. The build writes one, or Version.UNKNOWN when
	// it had no git repository to read (an unexpanded property lands there too), and
	// anything else is left OFF rather than escaped: a parenthesis would end the header
	// comment, and the same string is baked into generated Lisp source, where a quote
	// would end the literal.
	private static boolean isCommitId(String commit) {
		if (commit.isEmpty()) {
			return false;
		}
		for (int i = 0; i < commit.length(); i++) {
			char c = commit.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a request-header name is the {@link #USER_AGENT_HEADER} field. HTTP field
	 * names are case-insensitive, so this is the test each backend applies to the
	 * caller's headers before adding the default.
	 * @param name the request-header name
	 * @return {@code true} when the name is the user-agent field
	 */
	public static boolean isUserAgentHeader(String name) {
		return USER_AGENT_HEADER.equalsIgnoreCase(name);
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
	 * fields, in record order) and a per-field accessor applying the declared default,
	 * plus {@code %http-user-agent-header} / {@code %http-default-user-agent} —
	 * {@link #USER_AGENT_HEADER} and {@link #defaultUserAgent()} as literals, so the
	 * component spells neither itself (and has no {@link Version} to read at run time).
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
		source.append("(defun %http-user-agent-header ()\n  \"").append(USER_AGENT_HEADER).append("\")\n");
		source.append("(defun %http-default-user-agent ()\n  \"").append(defaultUserAgent()).append("\")\n");
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
