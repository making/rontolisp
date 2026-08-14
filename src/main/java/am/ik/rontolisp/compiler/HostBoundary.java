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
 * <li>{@link #STREAMING} takes both request and reply bodies out of the envelope: four
 * host functions ({@code env.readRequestBody}, {@code env.writeResponseBody}, and under
 * {@code --host-fetch} {@code env.readResponseBody} beside {@code env.fetch}) and the
 * host-side cursor each of them needs. What that buys is real — a BINARY body crosses
 * exactly, a large one never doubles linear memory, and a Worker can forward a streamed
 * upstream reply chunk at a time.</li>
 * <li>{@link #ENVELOPE}, the DEFAULT, keeps every body inside the envelope's own
 * {@code "body"} key, in both directions and on both sides of a {@code --host-fetch}
 * call. A program that fetches one JSON document and answers one JSON document — most of
 * them — has no use for a cursor, and this is the shape with nothing in it: no body
 * imports and no host-side reader state.</li>
 * </ul>
 *
 * <p>
 * <strong>The default IS the recommendation, and moving it here broke things on
 * purpose.</strong> An existing {@code --no-wasi} reactor rebuilt without the flag
 * changes shape: a binary body stops crossing exactly (the envelope carries a body as
 * JSON text, so {@code ff fe 41} arrives as the seven bytes {@code ef bf bd ef bf bd 41})
 * and a large one costs linear memory proportional to itself. That is a real regression
 * for the programs it hits, and it is the price of not having a default that disagrees
 * with the advice: the shape most Workers want is the one they get for saying nothing,
 * and the three cases that want the other are named above, in the guides, and in the two
 * shipped examples. A build that needs the split says {@code --host-boundary=streaming}
 * and is byte-for-byte what it was.
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
	 * {@code --host-boundary=streaming}: the bodies leave the envelope through
	 * {@code :bytes} imports of their own. Asked for, never assumed.
	 */
	STREAMING("streaming"),

	/**
	 * {@code --host-boundary=envelope}, the DEFAULT: every body rides the envelope's
	 * {@code "body"} key, and the module imports nothing but what the program itself
	 * declared.
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
	 * @return the selected boundary, {@link #ENVELOPE} when the flag is absent
	 * @throws IllegalArgumentException if the value names no boundary
	 */
	public static HostBoundary parse(@Nullable String value) {
		if (value == null) {
			return ENVELOPE;
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
