package am.ik.rontolisp.compiler;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * How much of a {@code --no-wasi} reactor's HTTP boundary crosses OUT OF BAND — the CLI's
 * {@code --host-boundary}, and the single vocabulary the reactor bridge
 * ({@code eval/HttpReactorInliner}), the {@code --host-fetch} lowering
 * ({@code eval/HostFetchLibrary}) and the reader's feature set all read.
 *
 * <p>
 * <strong>Both shapes are the same protocol; they differ in where the BODIES
 * ride.</strong> The head is the JSON envelope either way ({@code .kb/clack.md}) — what
 * the mode decides is whether a body is a key inside it or a stream of octets through an
 * import of its own.
 *
 * <ul>
 * <li>{@link #STREAMING} (the default, and what every reactor built before this flag got)
 * takes both request and reply bodies out of the envelope: four host functions
 * ({@code env.readRequestBody}, {@code env.writeResponseBody}, and under
 * {@code --host-fetch} {@code env.readResponseBody} beside {@code env.fetch}) and the
 * host-side cursor each of them needs. What that buys is real — a BINARY body crosses
 * exactly, a large one never doubles linear memory, and a Worker can forward a streamed
 * upstream reply chunk at a time.</li>
 * <li>{@link #ENVELOPE} keeps every body inside the envelope's own {@code "body"} key, in
 * both directions and on both sides of a {@code --host-fetch} call. A program that
 * fetches one JSON document and answers one JSON document has no use for a cursor, and
 * this is the shape with nothing in it: no body imports, no host-side reader state, and —
 * because what is left is fixed by the transport rather than chosen by the program — a
 * host half the build can WRITE ({@code --emit-js-glue}, {@link HostGlueEmitter}).</li>
 * </ul>
 *
 * <p>
 * <strong>It is a MODULE decision, not a glue decision</strong>, which is why it is a
 * flag of its own rather than a value on {@code --emit-js-glue}: the mode changes which
 * functions the {@code .wasm} imports, so a host written in something other than
 * JavaScript — or by hand — has to be able to ask for it without also asking for a
 * generated file it will not use.
 *
 * <p>
 * The names are the {@code .kb} files' own vocabulary (in band / out of band, "the
 * envelope is an API now"). Deliberately NOT {@code simple}/{@code complex}: one of those
 * is a value judgement and the other is wrong — streaming is not more complicated, it
 * carries more.
 *
 * <p>
 * On a COMPILE, a mode that would be a no-op is REFUSED rather than ignored
 * ({@code RontoLispCli}): a reactor component is in band already (its host functions
 * cross the canonical ABI, so there is no {@code :bytes} import to take a body out
 * through), {@code --no-gc} imports nothing at all, and a WASI command module has no host
 * to import from. Only the {@code --no-wasi} wasm-GC core module has two shapes to choose
 * between. Without {@code -o} the flag is inert and unparsed, like every other
 * module-shape flag ({@code --optimize}, {@code --component}): there is no module for it
 * to shape.
 *
 * @see #parse(String)
 */
public enum HostBoundary {

	/**
	 * {@code --host-boundary=streaming}, the default: the bodies leave the envelope
	 * through {@code :bytes} imports of their own.
	 */
	STREAMING("streaming"),

	/**
	 * {@code --host-boundary=envelope}: every body rides the envelope's {@code "body"}
	 * key, and the module imports nothing but what the program itself declared.
	 */
	ENVELOPE("envelope");

	private final String spelling;

	HostBoundary(String spelling) {
		this.spelling = spelling;
	}

	/**
	 * The {@code --host-boundary=} value that selects this shape.
	 * @return the spelling
	 */
	public String spelling() {
		return this.spelling;
	}

	/**
	 * Whether a reactor built this way takes its bodies OUT of the envelope. The one
	 * question every caller of this enum is really asking, spelled as a predicate so a
	 * call site reads as the invariant rather than as an equality test.
	 * @return {@code true} for {@link #STREAMING}
	 */
	public boolean bodiesOutOfBand() {
		return this == STREAMING;
	}

	/**
	 * Resolves a {@code --host-boundary} option value. Unlike {@code --optimize} there is
	 * no bare form: the flag exists to name one of two shapes, and an empty value names
	 * neither.
	 * @param value the option value as the CLI parsed it, or {@code null} when the flag
	 * is absent
	 * @return the selected boundary, {@link #STREAMING} when the flag is absent
	 * @throws IllegalArgumentException if the value names no boundary
	 */
	public static HostBoundary parse(@Nullable String value) {
		if (value == null) {
			return STREAMING;
		}
		for (HostBoundary boundary : values()) {
			if (value.equals(boundary.spelling)) {
				return boundary;
			}
		}
		throw new IllegalArgumentException("unknown --host-boundary '" + value + "' (accepted: " + spellings() + ")");
	}

	/**
	 * The accepted {@code --host-boundary=} values, comma-separated, for a help text or
	 * an error message.
	 * @return every spelling
	 */
	public static String spellings() {
		return Arrays.stream(values()).map(HostBoundary::spelling).collect(Collectors.joining(", "));
	}

}
