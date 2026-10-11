package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The host boundary as the Clojure lowering sees it while a program lowers, before any
 * backend runs: the type designators a {@code rontolisp.wasm} declaration may name, the
 * members a WIT interface binds ({@code rontolisp.wit/import}) and the exports a WIT
 * world declares ({@code rontolisp.wit/export}). This package reads no WIT and sees no
 * compiler type, so the implementation lives in {@code eval}, over the compiler's own WIT
 * front ends: a member is named, and a type classified, exactly as the lowering the
 * emitted directive reaches names and classifies it. Handed in by whoever drives the
 * lowering, like {@link ClojureFiles}.
 */
public interface ClojureBoundary {

	/**
	 * No boundary: a program declaring a crossing is told the read has none. A read
	 * through the source-language seam always has one.
	 */
	ClojureBoundary NONE = new ClojureBoundary() {
		@Override
		public Set<String> designators() {
			throw unavailable();
		}

		@Override
		public WitInterface importInterface(String witText, String witPath, String iface) {
			throw unavailable();
		}

		@Override
		public WitWorld exportWorld(String witText, String witPath, @Nullable String world) {
			throw unavailable();
		}

		private LispReadException unavailable() {
			return new LispReadException("rontolisp.wasm and rontolisp.wit need the host boundary, "
					+ "which this read was given none of (read the program through the source-language seam)");
		}
	};

	/**
	 * The keyword designators a WASM boundary declaration accepts, upcased the way the
	 * Common Lisp reader leaves them ({@code :S32}, the {@code :INT} alias, ...), the
	 * void result excluded: the one vocabulary {@code rontolisp:wasm-import} and
	 * {@code rontolisp:wasm-export} check against.
	 * @return the designators
	 */
	Set<String> designators();

	/**
	 * The members a {@code rontolisp:wit-import} of the interface binds.
	 * @param witText the WIT text
	 * @param witPath the WIT file path, for a message
	 * @param iface the interface reference as written
	 * @return the interface
	 * @throws LispReadException when the interface cannot be bound, naming the WIT file
	 * and line
	 */
	WitInterface importInterface(String witText, String witPath, String iface);

	/**
	 * The exports of a WIT world, checked as {@code rontolisp:wit-export} checks them
	 * before it reads the program.
	 * @param witText the WIT text
	 * @param witPath the WIT file path, for a message
	 * @param world the world, or {@code null} for the file's only one
	 * @return the world
	 * @throws LispReadException on a contract violation, naming the WIT file and line
	 */
	WitWorld exportWorld(String witText, String witPath, @Nullable String world);

	/**
	 * The house representation of a WIT type, the compiler's {@code WitTypeMapper.Rep}
	 * spelled member for member (the implementation maps one onto the other by name,
	 * which a test pins): which Clojure value carries each is this package's decision.
	 */
	enum Rep {

		INT, BIGNUM_INT, FLOAT, BOOLEAN, STRING, CHARACTER, BYTE_STRING, LIST, TUPLE_LIST, NIL_OR_VALUE, RESULT, HANDLE,
		STREAM_HANDLE, FUTURE_HANDLE, PLIST, KEYWORD, TAGGED_LIST, KEYWORD_LIST, UNSUPPORTED

	}

	/**
	 * A WIT type use, with every type nested in it: what the lowering converts a Clojure
	 * value to and from the boundary's value by.
	 *
	 * @param rep its house representation
	 * @param element an option's element, a result's ok arm or a list's element, else
	 * {@code null}
	 * @param wit the type as the WIT spells it where it is used
	 * @param error a result's error arm, else {@code null}
	 * @param parts a record's fields, a variant's cases, an enum's or a flags' labels, a
	 * tuple's elements, in WIT order; else empty
	 */
	record Type(Rep rep, @Nullable Type element, String wit, @Nullable Type error, List<Part> parts) {
	}

	/**
	 * A labelled member of a {@link Type}.
	 *
	 * @param label the WIT label as written, or a tuple element's position
	 * @param type its type, or {@code null} for a payload-less case and a label
	 */
	record Part(String label, @Nullable Type type) {
	}

	/**
	 * A parameter.
	 *
	 * @param name its WIT name ({@code self} for a resource method's handle)
	 * @param type its type
	 */
	record Param(String name, Type type) {
	}

	/** How a member of an interface comes to be bound. */
	enum Origin {

		/** A WIT function. */
		FUNCTION,

		/** A resource's {@code <resource>-drop}. */
		DROP,

		/**
		 * A stream/future built-in of a {@code type} alias ({@code --component} only).
		 */
		ASYNC_BUILTIN,

		/** An {@code <alias>-task-return} ({@code --component} only). */
		TASK_RETURN

	}

	/**
	 * An interface member or a world export.
	 *
	 * @param name the member's name ({@code bucket-get}) or the export's label
	 * @param origin how it comes to be bound ({@link Origin#FUNCTION} for an export)
	 * @param params its parameters, a method's handle first
	 * @param result its result, or {@code null} for none
	 * @param async whether it is an {@code async func}
	 * @param line the WIT line declaring it
	 */
	record Function(String name, Origin origin, List<Param> params, @Nullable Type result, boolean async, int line) {
	}

	/**
	 * A WIT interface.
	 *
	 * @param id its canonical id ({@code wasi:keyvalue/store@0.2.0})
	 * @param members every member a lowering may bind, in the lowering's order
	 */
	record WitInterface(String id, List<Function> members) {
	}

	/**
	 * A WIT world.
	 *
	 * @param name its name
	 * @param exports its exports, in world order
	 */
	record WitWorld(String name, List<Function> exports) {
	}

}
