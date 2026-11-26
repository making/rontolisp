package am.ik.rontolisp.java;

import java.util.List;

import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The host boundary as the Java lowering sees it while a program lowers: the members of
 * the WIT interface a {@code @WitImport} interface names, the exports of the WIT world a
 * {@code @WitExport} class implements, and what the target's host is. This package reads
 * no WIT and sees no compiler type, so the implementation lives in {@code eval}, over the
 * compiler's own WIT front end: a member is named, and a type classified, exactly as the
 * {@code rontolisp:wit-import} or {@code rontolisp:wit-export} the lowering emits names
 * and classifies it. Handed in by whoever drives the lowering, like {@link JavaFiles}.
 */
public interface JavaBoundary {

	/**
	 * No boundary: a program declaring a {@code @WitImport} interface is told the read
	 * has none. A read through the source-language seam always has one.
	 */
	JavaBoundary NONE = new JavaBoundary() {
		@Override
		public @Nullable WitInterface importInterface(String wit, List<String> paths, String iface) {
			throw new LispReadException("@WitImport needs the host boundary, which this read was given none of "
					+ "(read the program through the source-language seam)");
		}

		@Override
		public @Nullable WitWorld exportWorld(String wit, List<String> paths, String world) {
			throw new LispReadException("@WitExport needs the host boundary, which this read was given none of "
					+ "(read the program through the source-language seam)");
		}

		@Override
		public boolean hostImports() {
			return false;
		}

		@Override
		public boolean component() {
			return false;
		}

		@Override
		public boolean reactorModule() {
			return false;
		}

		@Override
		public boolean reportsUncaught() {
			return true;
		}

		@Override
		public boolean hostLogs() {
			return false;
		}
	};

	/**
	 * The members a {@code rontolisp:wit-import} of the interface may bind, read from the
	 * first of the paths that names a file or a directory.
	 * @param wit the WIT as the annotation writes it, for a message
	 * @param paths where the WIT file, or the directory of a package as published, may
	 * be, as the host names a file ({@link JavaFiles#libraryPath}), in the order looked
	 * at
	 * @param iface the interface reference as written: its id, without the version or
	 * bare
	 * @return the interface, or {@code null} when no path names a file or a directory
	 * @throws LispReadException when the WIT cannot be read or the interface cannot be
	 * bound, naming the WIT file and line
	 */
	@Nullable WitInterface importInterface(String wit, List<String> paths, String iface);

	/**
	 * The exports of a WIT world a {@code rontolisp:wit-export} of it checks a program
	 * against, read from the first of the paths that names a file or a directory, after
	 * every check that does not read the program: the world exists and exports something,
	 * each label is one, each type is one a component's export carries.
	 * @param wit the WIT as the annotation writes it, for a message
	 * @param paths where the WIT may be, as {@link #importInterface} reads them
	 * @param world the world's name
	 * @return the world, or {@code null} when no path names a file or a directory
	 * @throws LispReadException when the WIT cannot be read or the world cannot be
	 * exported, naming the WIT file and line
	 */
	@Nullable WitWorld exportWorld(String wit, List<String> paths, String world);

	/**
	 * Whether the target's host provides every {@code @WitImport} interface -- the WASM
	 * backends, whose modules import it -- rather than the program, as on the
	 * interpreter, the JVM and a JDK.
	 * @return {@code true} on a WASM target
	 */
	boolean hostImports();

	/**
	 * Whether the target's host is a component's, whose import takes an unsigned integer
	 * as its value; a core module's takes a {@code u32} as the {@code s32} of its bits.
	 * @return {@code true} under {@code --component}
	 */
	boolean component();

	/**
	 * Whether the target is a core module with no WASI ({@code --no-wasi}), a reactor
	 * whose entry points are the exports its host calls, where a program with no
	 * {@code main} and no {@code @WitExport} class exports the public static methods of
	 * its entry class.
	 * @return {@code true} for a {@code --no-wasi} module, not a component
	 */
	boolean reactorModule();

	/**
	 * Whether the target's host reads what a program writes of an exception nothing
	 * caught: a command's or a component's standard error. A reactor's does not: a
	 * {@code --no-wasi} module's standard error is a sink, and a {@code --no-gc} one has
	 * no condition to write a line from, its error a trap with nothing written. There the
	 * uncaught exception is the bare trap, and the exports of a {@code @WitExport} world
	 * are lowered with no report.
	 * @return {@code false} for a reactor, a component's included
	 */
	boolean reportsUncaught();

	/**
	 * Whether the build hands a program's log records to its host's log
	 * ({@code --wasi-logging}): the root logger's handler is then the host's, not the
	 * console ({@code .kb/java-frontend.md}, "java.util.logging").
	 * @return {@code true} where the target's host takes them
	 */
	boolean hostLogs();

	/**
	 * The house representation of a WIT type, the compiler's {@code WitTypeMapper.Rep}
	 * spelled member for member (the implementation maps one onto the other by name).
	 */
	enum Rep {

		INT, BIGNUM_INT, FLOAT, BOOLEAN, STRING, CHARACTER, BYTE_STRING, LIST, TUPLE_LIST, NIL_OR_VALUE, RESULT, HANDLE,
		STREAM_HANDLE, FUTURE_HANDLE, PLIST, KEYWORD, TAGGED_LIST, KEYWORD_LIST, UNSUPPORTED

	}

	/**
	 * A WIT type use, with every type nested in it.
	 *
	 * @param rep its house representation
	 * @param element an option's element, a result's ok arm, a list's or a stream's
	 * element or a future's payload, else {@code null}
	 * @param wit the type as the WIT spells it where it is used
	 * @param error a result's error arm, else {@code null}
	 * @param parts a record's fields, a variant's cases, an enum's or a flags' labels, a
	 * tuple's elements, in WIT order; else empty
	 * @param definition the definition a named type or a handle resolves to, else
	 * {@code null}
	 * @param borrow whether it is a {@code borrow<R>} handle
	 */
	record Type(Rep rep, @Nullable Type element, String wit, @Nullable Type error, List<Part> parts,
			@Nullable String definition, boolean borrow) {
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

	/** What a member is. */
	enum Kind {

		/** A function of the interface. */
		FUNCTION,

		/** A method of a resource: its handle comes first. */
		METHOD,

		/** A resource's constructor. */
		CONSTRUCTOR,

		/** A resource's static function. */
		STATIC,

		/** A resource's release, which WIT declares no function for. */
		DROP,

		/** A built-in of a {@code type} alias ({@code --component} only). */
		BUILTIN

	}

	/**
	 * A member of an interface.
	 *
	 * @param name the member's name, what a {@code :names} table maps
	 * ({@code bucket-list-keys})
	 * @param kind what it is
	 * @param resource the resource a method, a constructor, a static function or a drop
	 * belongs to, else {@code null}
	 * @param function the WIT function's own name ({@code list-keys})
	 * @param params its parameters, a method's handle first
	 * @param result its result, or {@code null} for none
	 * @param async whether it is an {@code async func}
	 * @param at the WIT file and line declaring it ({@code path:line})
	 */
	record Function(String name, Kind kind, @Nullable String resource, String function, List<Param> params,
			@Nullable Type result, boolean async, String at) {
	}

	/**
	 * A WIT interface.
	 *
	 * @param id its canonical id ({@code wasi:keyvalue/store@0.2.0-draft})
	 * @param path the WIT as the directive the lowering emits names it, which every later
	 * pass reads it by
	 * @param members every member a lowering may bind, in the lowering's order
	 */
	record WitInterface(String id, String path, List<Function> members) {
	}

	/**
	 * A function a world exports.
	 *
	 * @param name its label ({@code vowel-count}), an interface member's own
	 * @param iface the exported interface it is a member of, or {@code null} for a
	 * function of the world: the interface's canonical id ({@code docs:adder/add@0.1.0}),
	 * or the plain name of one the world declares inline
	 * @param key what the {@code :names} table of the {@code rontolisp:wit-export} the
	 * lowering emits names it by: its label, {@code <iface>#<label>} of an interface's
	 * member
	 * @param params its parameters
	 * @param result its result, or {@code null} for none
	 * @param async whether it is an {@code async func}
	 * @param at the WIT file and line declaring it ({@code path:line})
	 * @param canonical whether a type of it is outside the flat set (a record, a list, a
	 * variant ...), so that the directive lowers it through the canonical ABI and its
	 * function takes and answers the values of the settled mapping
	 */
	record Export(String name, @Nullable String iface, String key, List<Param> params, @Nullable Type result,
			boolean async, String at, boolean canonical) {
	}

	/**
	 * A WIT world.
	 *
	 * @param name its name
	 * @param path the WIT as the directive the lowering emits names it, which every later
	 * pass reads it by
	 * @param exports its exports, in world order
	 */
	record WitWorld(String name, String path, List<Export> exports) {
	}

}
