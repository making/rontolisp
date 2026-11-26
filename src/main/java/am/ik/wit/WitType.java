package am.ik.wit;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A WIT type use — the right-hand side of a parameter, result, field, case payload or
 * {@code type} alias. Type <em>definitions</em> ({@code record}, {@code variant}, ...)
 * are {@link WitItem}s; this hierarchy only references them by name via {@link Named}.
 */
public sealed interface WitType {

	/**
	 * A primitive type: {@code bool}, {@code u8}..{@code u64}, {@code s8}..{@code s64},
	 * {@code f32}, {@code f64}, {@code char} or {@code string}.
	 *
	 * @param name the primitive's WIT name
	 */
	record Prim(String name) implements WitType {
	}

	/**
	 * A reference to a named type defined elsewhere (a {@code record}, {@code variant},
	 * {@code enum}, {@code flags}, {@code resource} or {@code type} alias, possibly
	 * brought in by {@code use}).
	 *
	 * @param name the referenced type name (a {@code %}-escape is kept verbatim)
	 */
	record Named(String name) implements WitType {
	}

	/**
	 * {@code list<T>}.
	 *
	 * @param element the element type
	 */
	record ListOf(WitType element) implements WitType {
	}

	/**
	 * {@code option<T>}.
	 *
	 * @param element the payload type
	 */
	record OptionOf(WitType element) implements WitType {
	}

	/**
	 * {@code result}, {@code result<T>}, {@code result<_, E>} or {@code result<T, E>}.
	 *
	 * @param ok the ok-arm payload, or {@code null} when absent ({@code result} /
	 * {@code result<_, E>})
	 * @param err the error-arm payload, or {@code null} when absent ({@code result} /
	 * {@code result<T>})
	 */
	record ResultOf(@Nullable WitType ok, @Nullable WitType err) implements WitType {
	}

	/**
	 * {@code tuple<A, B, ...>}.
	 *
	 * @param elements the element types in order
	 */
	record TupleOf(List<WitType> elements) implements WitType {
	}

	/**
	 * {@code stream} or {@code stream<T>} (WASI 0.3 async byte/element streams).
	 *
	 * @param element the element type, or {@code null} for the unparameterized form
	 */
	record StreamOf(@Nullable WitType element) implements WitType {
	}

	/**
	 * {@code future} or {@code future<T>}.
	 *
	 * @param element the payload type, or {@code null} for the unparameterized form
	 */
	record FutureOf(@Nullable WitType element) implements WitType {
	}

	/**
	 * {@code borrow<R>} — a borrowed resource handle.
	 *
	 * @param resource the resource type name
	 */
	record BorrowOf(String resource) implements WitType {
	}

	/**
	 * {@code own<R>} — an owned resource handle.
	 *
	 * @param resource the resource type name
	 */
	record OwnOf(String resource) implements WitType {
	}

}
