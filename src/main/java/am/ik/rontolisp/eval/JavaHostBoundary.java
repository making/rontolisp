package am.ik.rontolisp.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.compiler.WitSource;
import am.ik.rontolisp.compiler.WitTypeMapper;
import am.ik.rontolisp.java.JavaBoundary;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The host boundary the Java lowering consults while a program lowers
 * ({@code @WitImport}, {@code @WitExport}), over the compiler's own front end:
 * {@link WitImportDirective#describe} for an interface's members and
 * {@link WitExportDirective#describe} for a world's exports, read through the one WIT
 * reader every directive reads with ({@link WitFiles}). So a member a Java interface
 * names is the member the emitted {@code rontolisp:wit-import} binds, under the same
 * name, an export a Java method implements the one the emitted
 * {@code rontolisp:wit-export} checks, and no WIT is read in a dialect of its own.
 */
public final class JavaHostBoundary implements JavaBoundary {

	private final @Nullable SourceLoader loader;

	private final boolean hostImports;

	private final boolean component;

	private final boolean reactor;

	private final boolean hostLogs;

	private final @Nullable String entry;

	/**
	 * A boundary reading through a loader.
	 * @param loader where the WIT is read from, or {@code null} for none
	 * @param hostImports whether the target is a WASM backend, whose host provides every
	 * imported interface
	 * @param component whether that backend's output is a component
	 * @param reactor whether that output is a reactor ({@code --no-wasi} or
	 * {@code --no-gc}), a component or a core module
	 * @param hostLogs whether the build hands the program's log records to its host's log
	 * @param entry the file the program starts at, whose directory every later pass
	 * resolves a directive's path against, or {@code null} for none
	 */
	public JavaHostBoundary(@Nullable SourceLoader loader, boolean hostImports, boolean component, boolean reactor,
			boolean hostLogs, @Nullable String entry) {
		this.loader = loader;
		this.hostImports = hostImports;
		this.component = component;
		this.reactor = reactor;
		this.hostLogs = hostLogs;
		this.entry = entry;
	}

	@Override
	public boolean hostImports() {
		return this.hostImports;
	}

	@Override
	public boolean component() {
		return this.component;
	}

	@Override
	public boolean reactorModule() {
		return this.reactor && !this.component;
	}

	@Override
	public boolean reportsUncaught() {
		return !this.reactor;
	}

	@Override
	public boolean hostLogs() {
		return this.hostLogs;
	}

	@Override
	public @Nullable WitInterface importInterface(String wit, List<String> paths, String iface) {
		WitSource source = read(wit, paths);
		if (source == null) {
			return null;
		}
		String path = source.path();
		WitImportDirective.Description described;
		try {
			described = WitImportDirective.describe(new WitImportDirective.Directive(path,
					iface.toLowerCase(Locale.ROOT), null, null, WitImportDirective.FieldStyle.CAMEL), source);
		}
		catch (UnsupportedOperationException ex) {
			throw new LispReadException(String.valueOf(ex.getMessage()));
		}
		List<Function> members = new ArrayList<>();
		for (WitImportDirective.Member member : described.members()) {
			List<Param> params = new ArrayList<>();
			for (WitTypeMapper.Param param : member.params()) {
				params.add(new Param(param.name(), Objects.requireNonNull(type(param.shape()))));
			}
			members.add(new Function(member.name(), kind(member), member.resource(), member.function(),
					List.copyOf(params), type(member.result()), member.async(), member.at()));
		}
		return new WitInterface(described.id(), path, List.copyOf(members));
	}

	@Override
	public @Nullable WitWorld exportWorld(String wit, List<String> paths, String world) {
		WitSource source = read(wit, paths);
		if (source == null) {
			return null;
		}
		WitExportDirective.WorldDescription described;
		try {
			described = WitExportDirective.describe(new WitExportDirective.Directive(source.path(), world), source);
		}
		catch (UnsupportedOperationException ex) {
			throw new LispReadException(String.valueOf(ex.getMessage()));
		}
		List<Export> exports = new ArrayList<>();
		for (WitExportDirective.Export export : described.exports()) {
			List<Param> params = new ArrayList<>();
			for (WitTypeMapper.Param param : export.params()) {
				params.add(new Param(param.name(), Objects.requireNonNull(type(param.shape()))));
			}
			exports.add(new Export(export.name(), export.iface(), export.key(), List.copyOf(params),
					type(export.result()), export.async(), export.at(), export.canonical()));
		}
		return new WitWorld(described.name(), source.path(), List.copyOf(exports));
	}

	/**
	 * The WIT the first of the paths that names a file or a directory holds, shown as the
	 * directive the lowering emits names it ({@link #directivePath}), or {@code null}
	 * when none does.
	 */
	private @Nullable WitSource read(String wit, List<String> paths) {
		SourceLoader files = this.loader;
		if (files == null) {
			throw new LispReadException("cannot read WIT " + wit + " (the program was read without files)");
		}
		String resolved = null;
		for (String path : paths) {
			if (WitFiles.exists(files, path)) {
				resolved = path;
				break;
			}
		}
		if (resolved == null) {
			return null;
		}
		try {
			return WitFiles.read(files, resolved).shownAs(directivePath(files, resolved));
		}
		catch (IOException ex) {
			throw new LispReadException("cannot read WIT " + wit + ": " + ex.getMessage());
		}
	}

	/**
	 * The path the emitted directive names a WIT by: every later pass resolves it against
	 * the entry's directory, so it is the path from there -- what a program's directive
	 * beside the WIT writes, and what a message names; a jar's entry is named by the
	 * jar's absolute path and the entry's in it ({@link WitFiles}). A host with no
	 * working directory (the playground) keeps the loader's own spelling, which it
	 * resolves no further.
	 */
	private String directivePath(SourceLoader files, String resolved) {
		if (files.workingDirectory() == null) {
			return resolved;
		}
		int entry = resolved.indexOf(WitFiles.ENTRY);
		if (entry >= 0) {
			// an entry of a jar, which lies where its repository keeps it, not beside the
			// program: the jar's absolute path, then the entry's in it
			return Path.of(resolved.substring(0, entry)).toAbsolutePath().normalize() + resolved.substring(entry);
		}
		Path wit = Path.of(resolved).toAbsolutePath().normalize();
		String directory = this.entry == null ? null : SourceLoader.parentDir(this.entry);
		try {
			return Path.of(directory == null ? "" : directory).toAbsolutePath().normalize().relativize(wit).toString();
		}
		catch (IllegalArgumentException ex) {
			// no path climbs from one root to another
			return wit.toString();
		}
	}

	private static Kind kind(WitImportDirective.Member member) {
		return switch (member.origin()) {
			case DROP -> Kind.DROP;
			case ASYNC_BUILTIN, TASK_RETURN -> Kind.BUILTIN;
			case FUNCTION -> switch (member.kind()) {
				case CONSTRUCTOR -> Kind.CONSTRUCTOR;
				case STATIC -> Kind.STATIC;
				case PLAIN -> member.resource() == null ? Kind.FUNCTION : Kind.METHOD;
			};
		};
	}

	/**
	 * A compiler shape as the Java lowering's type, every nested shape with it: the
	 * representation by name (the two enums spell the same members).
	 */
	static @Nullable Type type(WitTypeMapper.@Nullable Shape shape) {
		if (shape == null) {
			return null;
		}
		List<Part> parts = new ArrayList<>();
		for (WitTypeMapper.Part part : shape.parts()) {
			parts.add(new Part(part.label(), type(part.shape())));
		}
		return new Type(Rep.valueOf(shape.rep().name()), type(shape.element()), shape.wit(), type(shape.error()),
				List.copyOf(parts), shape.definition(), shape.borrow());
	}

}
