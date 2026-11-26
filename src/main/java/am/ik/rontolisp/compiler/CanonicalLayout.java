package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How a WIT value lies in linear memory and in core values under the canonical ABI -- the
 * facts the JavaScript glue needs to lift and lower one ({@link HostGlueEmitter}),
 * computed by the WASM backend from the same layout calculator its own marshalling uses,
 * so the two halves of the boundary cannot drift. Sizes, alignments, offsets and
 * discriminant widths are the canonical ABI's; {@link #flat()} is the value's flattening,
 * each core type spelled {@code i32} / {@code i64} / {@code f32} / {@code f64}.
 */
public sealed interface CanonicalLayout {

	/**
	 * The core value types the value flattens to, in order.
	 * @return the flat types
	 */
	List<String> flat();

	/**
	 * The value's byte size in memory.
	 * @return the size
	 */
	int size();

	/**
	 * The value's byte alignment in memory.
	 * @return the alignment
	 */
	int align();

	/**
	 * A scalar: {@code bool}, the integers, {@code f32}, {@code f64}, {@code char}.
	 *
	 * @param kind the WIT name of its type
	 */
	record Scalar(String kind) implements CanonicalLayout {

		@Override
		public List<String> flat() {
			return switch (this.kind) {
				case "s64", "u64" -> List.of("i64");
				case "f32" -> List.of("f32");
				case "f64" -> List.of("f64");
				default -> List.of("i32");
			};
		}

		@Override
		public int size() {
			return switch (this.kind) {
				case "bool", "s8", "u8" -> 1;
				case "s16", "u16" -> 2;
				case "s64", "u64", "f64" -> 8;
				default -> 4;
			};
		}

		@Override
		public int align() {
			return size();
		}

	}

	/** A {@code string}: its UTF-8 octets, a pointer and a length. */
	record Text() implements CanonicalLayout {

		@Override
		public List<String> flat() {
			return List.of("i32", "i32");
		}

		@Override
		public int size() {
			return 8;
		}

		@Override
		public int align() {
			return 4;
		}

	}

	/** A {@code list<u8>}: its octets, a pointer and a length. */
	record Octets() implements CanonicalLayout {

		@Override
		public List<String> flat() {
			return List.of("i32", "i32");
		}

		@Override
		public int size() {
			return 8;
		}

		@Override
		public int align() {
			return 4;
		}

	}

	/**
	 * Any other {@code list<T>}: an array of its elements, a pointer and a length.
	 *
	 * @param element the element's layout
	 */
	record ListOf(CanonicalLayout element) implements CanonicalLayout {

		@Override
		public List<String> flat() {
			return List.of("i32", "i32");
		}

		@Override
		public int size() {
			return 8;
		}

		@Override
		public int align() {
			return 4;
		}

	}

	/**
	 * A value of cases -- a {@code variant}, an {@code enum}, an {@code option}, a
	 * {@code result} -- despecialized: a discriminant, then the case's payload at one
	 * offset; flattened, the discriminant and the cases' payloads joined position by
	 * position.
	 *
	 * @param kind {@code variant}, {@code enum}, {@code option} or {@code result}: which
	 * JavaScript value it is
	 * @param names the cases, in order
	 * @param payloads each case's payload, or {@code null} for none
	 * @param discSize the discriminant's byte width
	 * @param payloadOffset where the payload starts
	 * @param size the byte size
	 * @param align the byte alignment
	 * @param flat the discriminant and the joined payload types
	 */
	record Cases(String kind, List<String> names, List<@Nullable CanonicalLayout> payloads, int discSize,
			int payloadOffset, int size, int align, List<String> flat) implements CanonicalLayout {
	}

	/**
	 * A value of fields -- a {@code record}, a {@code tuple} -- each at its offset;
	 * flattened, the fields' flattenings in order.
	 *
	 * @param kind {@code record} or {@code tuple}
	 * @param names a record's field names as a JavaScript object's keys (camelCase), a
	 * tuple's positions
	 * @param types the fields' layouts
	 * @param offsets the fields' offsets
	 * @param size the byte size
	 * @param align the byte alignment
	 * @param flat the fields' flat types
	 */
	record Fields(String kind, List<String> names, List<CanonicalLayout> types, List<Integer> offsets, int size,
			int align, List<String> flat) implements CanonicalLayout {
	}

	/**
	 * A resource handle: an index into the table of the resource's values the host keeps.
	 *
	 * @param resource the resource, as its defining interface's canonical id, {@code #},
	 * and its name ({@code wasi:keyvalue/store@0.2.0-draft2#bucket})
	 * @param own whether the handle is owned ({@code own<R>}, a bare {@code R}) rather
	 * than borrowed
	 */
	record Handle(String resource, boolean own) implements CanonicalLayout {

		@Override
		public List<String> flat() {
			return List.of("i32");
		}

		@Override
		public int size() {
			return 4;
		}

		@Override
		public int align() {
			return 4;
		}

	}

}
