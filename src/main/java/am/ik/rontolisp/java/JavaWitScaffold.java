package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.java.JavaBoundary.Kind;
import am.ik.rontolisp.java.JavaBoundary.Rep;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The skeleton of a Java program implementing a WIT world
 * ({@code rontolisp --scaffold-wit world.wit -o Greeter.java}): a {@code @WitExport}
 * class whose static methods are the world's exports, each throwing until it is filled
 * in, a {@code @WitImport} member interface for each interface the world imports (and
 * each one whose resource an imported interface takes with {@code use}), and every type
 * they reach, declared where the mapping says it is declared.
 *
 * <p>
 * The text is written from the world as the boundary describes it to the checkers and
 * from the mapping they apply ({@link JavaWitTypes#javaText},
 * {@link JavaWitTypes#refusal}, {@link JavaWitImports#handedRefusal}), so the file passes
 * the check unchanged. A function the check would refuse is left out and its refusal,
 * worded as the check words it, listed instead ({@link JavaWitSkeleton#skipped}); so is
 * an export the boundary refuses ({@link JavaBoundary#outlineWorld}) and an import it
 * cannot bind. A {@code result} is a {@code WitResult}, the spelling the mapping
 * documents first.
 */
final class JavaWitScaffold {

	/** The indent of one level, as the examples write Java. */
	private static final String INDENT = "    ";

	/** A qualified name of a library class the text names, simplified at the end. */
	private static final Pattern QUALIFIED = Pattern.compile(
			"\\b(java\\.lang|java\\.util\\.function|java\\.util|java\\.io|am\\.ik\\.rontolisp\\.wit)\\.([A-Z]\\w*)");

	private final JavaBoundary boundary;

	private final String wit;

	private final List<String> paths;

	private final String className;

	private final Map<String, List<String>> docs;

	private final List<String> skipped = new ArrayList<>();

	/** Every simple name the file declares a type by. */
	private final Set<String> declared = new HashSet<>();

	/** The member interface standing for each imported interface, by canonical id. */
	private final Map<String, Scope> imports = new LinkedHashMap<>();

	/** The member class standing for each exported interface, by its id. */
	private final Map<String, Scope> exported = new LinkedHashMap<>();

	private JavaWitScaffold(JavaBoundary boundary, String wit, List<String> paths, String className,
			Map<String, List<String>> docs) {
		this.boundary = boundary;
		this.wit = wit;
		this.paths = paths;
		this.className = className;
		this.docs = docs;
	}

	/** The skeleton ({@code Java.scaffoldWit}). */
	static JavaWitSkeleton scaffold(JavaBoundary boundary, String wit, List<String> paths, String world,
			String className, Map<String, List<String>> docs) {
		return new JavaWitScaffold(boundary, wit, paths, className, docs).write(world);
	}

	private JavaWitSkeleton write(String worldName) {
		JavaBoundary.WitWorld world = this.boundary.outlineWorld(this.wit, this.paths, worldName);
		if (world == null) {
			throw new LispReadException("cannot read WIT " + this.wit);
		}
		this.skipped.addAll(world.refused());
		this.declared.add(this.className);
		// the exports: the class for the world's own, a member class for each
		// interface, each declaring the types its functions reach and a member class for
		// each resource it declares
		Scope own = new Scope(this.className, "world " + world.name(), "", null, false);
		Map<String, Scope> exported = this.exported;
		for (JavaBoundary.Export export : world.exports()) {
			holder(export.iface());
		}
		for (JavaBoundary.Resource resource : world.resources()) {
			Objects.requireNonNull(holder(resource.iface())).resource(resource);
		}
		for (JavaBoundary.Export export : world.exports()) {
			(export.iface() == null ? own : Objects.requireNonNull(exported.get(export.iface()))).reserve(export);
		}
		// the imports, with each interface whose resource one takes with use, and each
		// whose resource an export takes, which the component imports for it: named
		// before an export's text names one of their resources
		Map<String, JavaBoundary.WitInterface> described = new LinkedHashMap<>();
		Deque<String> pending = new ArrayDeque<>(world.imports());
		for (JavaBoundary.Export export : world.exports()) {
			for (JavaBoundary.Param param : export.params()) {
				importedHomes(param.type(), pending);
			}
			if (export.result() != null) {
				importedHomes(export.result(), pending);
			}
		}
		Set<String> asked = new HashSet<>();
		while (!pending.isEmpty()) {
			String id = pending.removeFirst();
			if (!asked.add(id)) {
				continue;
			}
			JavaBoundary.WitInterface iface;
			try {
				iface = this.boundary.importInterface(this.wit, this.paths, id);
			}
			catch (LispReadException ex) {
				this.skipped.add("@WitImport " + id + ": " + ex.getMessage());
				continue;
			}
			if (iface == null) {
				this.skipped.add("@WitImport " + id + ": cannot read WIT " + this.wit);
				continue;
			}
			described.put(iface.id(), iface);
			pending.addAll(iface.used().values());
		}
		for (JavaBoundary.WitInterface iface : described.values()) {
			String name = unique(JavaWitImports.upperCamel(JavaWitExports.interfaceName(iface.id())), this.declared);
			this.declared.add(name);
			this.imports.put(iface.id(), new Scope(name, iface.id(), iface.id(), iface, true));
		}
		for (Scope scope : this.imports.values()) {
			scope.reserveImport();
		}
		for (JavaBoundary.Export export : world.exports()) {
			(export.iface() == null ? own : Objects.requireNonNull(exported.get(export.iface()))).export(export);
		}
		for (Scope scope : this.imports.values()) {
			scope.importMembers();
		}
		// the class
		List<String> body = new ArrayList<>();
		body.add("@am.ik.rontolisp.wit.WitExport(value = " + literal(world.name()) + ", wit = " + literal(this.wit)
				+ ")");
		body.add("public class " + this.className + " {");
		own.members(body, 1);
		for (Scope holder : exported.values()) {
			body.add("");
			javadoc(body, 1, holder.docs(""), null);
			body.add(INDENT + "public static class " + holder.name + " {");
			holder.members(body, 2);
			body.add(INDENT + "}");
		}
		for (Scope scope : this.imports.values()) {
			body.add("");
			List<String> about = new ArrayList<>(scope.docs(""));
			if (!about.isEmpty()) {
				about.add("");
			}
			about.add(" The WIT interface " + scope.id + ", which the host provides in a component:");
			about.add(" {@code ServiceLoader.load(" + this.className + "." + scope.name
					+ ".class).findFirst().orElseThrow()}.");
			javadoc(body, 1, about, null);
			body.add(INDENT + "@am.ik.rontolisp.wit.WitImport(value = " + literal(scope.id) + ", wit = "
					+ literal(this.wit) + ")");
			body.add(INDENT + "public interface " + scope.name + " {");
			scope.members(body, 2);
			body.add(INDENT + "}");
		}
		body.add("}");
		return new JavaWitSkeleton(header(world) + simplified(String.join("\n", body) + "\n"),
				List.copyOf(this.skipped));
	}

	/** The member class standing for an exported interface, made when first asked for. */
	private @Nullable Scope holder(@Nullable String iface) {
		if (iface == null) {
			return null;
		}
		Scope known = this.exported.get(iface);
		if (known != null) {
			return known;
		}
		Scope holder = new Scope(JavaWitImports.upperCamel(JavaWitExports.interfaceName(iface)), iface, iface, null,
				false);
		this.declared.add(holder.name);
		this.exported.put(iface, holder);
		return holder;
	}

	/**
	 * Adds the home of each handle a type holds of a resource no exported interface
	 * declares: an interface the component imports.
	 */
	private void importedHomes(JavaBoundary.Type wit, Deque<String> pending) {
		String home = wit.home();
		if (wit.rep() == Rep.HANDLE && home != null && !this.exported.containsKey(home) && !pending.contains(home)) {
			pending.add(home);
		}
		if (wit.element() != null) {
			importedHomes(wit.element(), pending);
		}
		if (wit.error() != null) {
			importedHomes(wit.error(), pending);
		}
		for (JavaBoundary.Part part : wit.parts()) {
			if (part.type() != null) {
				importedHomes(part.type(), pending);
			}
		}
	}

	private String header(JavaBoundary.WitWorld world) {
		List<String> lines = new ArrayList<>();
		lines.add("// The skeleton of a class implementing the WIT world '" + world.name() + "' (" + this.wit + ").");
		lines.add("//");
		lines.add("// The world is the contract: the compiler checks each method below against it, so a");
		lines.add("// renamed export, a changed parameter or a changed type is a compile error rather");
		lines.add("// than a surprise at run time. Fill in the bodies; each throws until you do.");
		if (!this.imports.isEmpty()) {
			lines.add("// The interfaces the world imports are member interfaces a method may call.");
		}
		lines.add("//");
		lines.add("// rontolisp-java-api, where the annotations are, goes on the source path:");
		lines.add("//");
		lines.add("//   rontolisp " + this.className + ".java --java-source-path rontolisp-java-api/src/main/java \\");
		lines.add("//       -o " + world.name() + ".wasm --component");
		if (!this.skipped.isEmpty()) {
			lines.add("//");
			lines.add("// Left out, as the compiler refuses them:");
			lines.add("//");
			for (String refusal : this.skipped) {
				lines.add("//   " + escaped(refusal));
			}
		}
		return String.join("\n", lines) + "\n\n";
	}

	/**
	 * The text with each library class named by its simple name, imported, where no type
	 * the file declares has that name.
	 */
	private String simplified(String text) {
		Set<String> imported = new TreeSet<>();
		Matcher matcher = QUALIFIED.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String simple = matcher.group(2);
			if (this.declared.contains(simple)) {
				matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			if (!matcher.group(1).equals("java.lang")) {
				imported.add(matcher.group());
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(simple));
		}
		matcher.appendTail(out);
		StringBuilder head = new StringBuilder();
		// the JDK's first, then the library's, as the examples write them
		for (boolean jdk : new boolean[] { true, false }) {
			int before = head.length();
			for (String name : imported) {
				if (name.startsWith("java.") == jdk) {
					head.append("import ").append(name).append(";\n");
				}
			}
			if (head.length() > before) {
				head.append('\n');
			}
		}
		return head.toString() + out;
	}

	/**
	 * One WIT scope and the Java type standing for it: the {@code @WitExport} class for
	 * the world's own, its member class for an exported interface, its member interface
	 * for an imported one. Its named types are its member types.
	 */
	private final class Scope implements JavaWitTypes.Spelling {

		/** The simple name of the Java type. */
		final String name;

		/** The scope as a message of the check names it. */
		final String scope;

		/** The scope as the docs are keyed by: an interface's id, empty for the world. */
		final String id;

		/** The imported interface, or {@code null} for an export's scope. */
		final JavaBoundary.@Nullable WitInterface iface;

		/** Whether the type is an interface, whose members are public as they stand. */
		final boolean isInterface;

		/** The simple names of its member types, every one it declares or will. */
		final Set<String> names = new HashSet<>();

		/** The methods, each a list of lines. */
		final List<List<String>> methods = new ArrayList<>();

		/** The member interfaces of the resources, each a list of lines. */
		final List<List<String>> resources = new ArrayList<>();

		/**
		 * The member classes of the resources an exported interface declares, by name:
		 * each member's lines, in WIT order.
		 */
		final Map<String, List<List<String>>> resourceMembers = new LinkedHashMap<>();

		/** The named types reached, by name, declared once each. */
		final Map<String, JavaBoundary.Type> reached = new LinkedHashMap<>();

		/** The records standing for tuples, by name, each with its type. */
		final Map<String, JavaBoundary.Type> tuples = new LinkedHashMap<>();

		/** The record of each alias of a tuple, by the alias's name. */
		final Map<String, String> aliases = new LinkedHashMap<>();

		/** Where a tuple met now stands, which names its record. */
		String place = "";

		/**
		 * Names a named type is written in full by: a variant's cases, in its payloads.
		 */
		Set<String> shadowed = Set.of();

		Scope(String name, String scope, String id, JavaBoundary.@Nullable WitInterface iface, boolean isInterface) {
			this.name = name;
			this.scope = scope;
			this.id = id;
			this.iface = iface;
			this.isInterface = isInterface;
		}

		List<String> docs(String key) {
			return JavaWitScaffold.this.docs.getOrDefault(this.id + "#" + key, List.of());
		}

		// ---- the names, taken before any tuple is named

		/** A resource the interface declares: the member class standing for it. */
		void resource(JavaBoundary.Resource resource) {
			this.names.add(JavaWitImports.upperCamel(resource.name()));
			JavaWitScaffold.this.declared.addAll(this.names);
			this.resourceMembers.put(resource.name(), new ArrayList<>());
		}

		void reserve(JavaBoundary.Export export) {
			for (JavaBoundary.Param param : export.params()) {
				reserve(param.type());
			}
			if (export.result() != null) {
				reserve(export.result());
			}
		}

		void reserveImport() {
			JavaBoundary.WitInterface described = Objects.requireNonNull(this.iface);
			for (JavaBoundary.Function member : described.members()) {
				if (member.kind() == Kind.DROP) {
					this.names.add(JavaWitImports.upperCamel(Objects.requireNonNull(member.resource())));
				}
				for (JavaBoundary.Param param : member.params()) {
					reserve(param.type());
				}
				if (member.result() != null) {
					reserve(member.result());
				}
			}
			JavaWitScaffold.this.declared.addAll(this.names);
		}

		private void reserve(JavaBoundary.Type wit) {
			String definition = wit.definition();
			switch (wit.rep()) {
				case PLIST, TAGGED_LIST, KEYWORD, KEYWORD_LIST -> {
					if (this.names.add(JavaWitImports.upperCamel(Objects.requireNonNull(definition)))) {
						for (JavaBoundary.Part part : wit.parts()) {
							if (part.type() != null) {
								reserve(part.type());
							}
							if (wit.rep() == Rep.TAGGED_LIST) {
								this.names.add(JavaWitImports.upperCamel(part.label()));
							}
						}
					}
				}
				default -> {
					if (wit.element() != null) {
						reserve(wit.element());
					}
					if (wit.error() != null) {
						reserve(wit.error());
					}
					for (JavaBoundary.Part part : wit.parts()) {
						if (part.type() != null) {
							reserve(part.type());
						}
					}
				}
			}
			JavaWitScaffold.this.declared.addAll(this.names);
		}

		// ---- the check

		/**
		 * The refusal of a type the check makes, worded as it words it, or {@code null}:
		 * the type's own ({@link JavaWitTypes#refusal}), then each definition's it holds,
		 * as {@code JavaWitTypes.Table} checks a definition.
		 * @param arm whether it is an error arm a {@code WitException} carries
		 */
		@Nullable String refusal(JavaBoundary.Type wit, boolean arm, String what) {
			String own = JavaWitTypes.refusal(wit, arm);
			if (own != null) {
				return what + " is " + wit.wit() + ", " + own;
			}
			return definitions(wit, what);
		}

		private @Nullable String definitions(JavaBoundary.Type wit, String what) {
			switch (wit.rep()) {
				case LIST, NIL_OR_VALUE, STREAM_HANDLE -> {
					return definitions(Objects.requireNonNull(wit.element()), what);
				}
				case RESULT -> {
					String ok = wit.element() == null ? null : definitions(wit.element(), what);
					return ok != null || wit.error() == null ? ok : definitions(wit.error(), what);
				}
				case FUTURE_HANDLE -> {
					JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
					if (payload.rep() != Rep.RESULT) {
						return definitions(payload, what);
					}
					String ok = payload.element() == null ? null : definitions(payload.element(), what);
					return ok != null || payload.error() == null ? ok
							: refusal(payload.error(), true, what + ": its future's error arm");
				}
				case TUPLE_LIST -> {
					for (JavaBoundary.Part part : wit.parts()) {
						String refused = definitions(Objects.requireNonNull(part.type()), what);
						if (refused != null) {
							return refused;
						}
					}
					return null;
				}
				case PLIST, TAGGED_LIST -> {
					String definition = Objects.requireNonNull(wit.definition());
					String named = this.name + "." + JavaWitImports.upperCamel(definition) + " (" + definition + " of "
							+ this.scope + ")";
					for (JavaBoundary.Part part : wit.parts()) {
						JavaBoundary.Type type = part.type();
						String refused = type == null ? null : refusal(type, false, named + (wit.rep() == Rep.PLIST
								? ": its field " + part.label() : ": the payload of its case " + part.label()));
						if (refused != null) {
							return refused;
						}
					}
					return null;
				}
				default -> {
					return null;
				}
			}
		}

		// ---- the spelling

		@Override
		public String named(JavaBoundary.Type wit, @Nullable JavaType declared, boolean simple) {
			String definition = Objects.requireNonNull(wit.definition());
			String member = JavaWitImports.upperCamel(definition);
			JavaBoundary.WitInterface described = this.iface;
			if (wit.rep() == Rep.HANDLE && described == null) {
				// an export's: a resource of its own interface, of another the world
				// exports, or of one the component imports
				String home = wit.home();
				if (home == null || home.equals(this.id)) {
					return member;
				}
				Scope other = JavaWitScaffold.this.exported.get(home);
				other = other != null ? other : JavaWitScaffold.this.imports.get(home);
				return (other == null ? JavaWitImports.upperCamel(JavaWitExports.interfaceName(home)) : other.name)
						+ "." + member;
			}
			if (wit.rep() == Rep.HANDLE) {
				String home = described == null || this.names.contains(member) ? null
						: described.used().get(definition);
				if (home != null) {
					// a resource of another interface: its member interface there
					Scope other = JavaWitScaffold.this.imports.get(home);
					return (other == null ? JavaWitImports.upperCamel(JavaWitExports.interfaceName(home)) : other.name)
							+ "." + member;
				}
				return member;
			}
			this.reached.putIfAbsent(member, wit);
			return this.shadowed.contains(member) ? qualified() + "." + member : member;
		}

		@Override
		public String tuple(JavaBoundary.Type wit, @Nullable JavaType declared, boolean simple) {
			// an alias's tuple is a record of the alias's name, one for every use
			boolean alias = wit.wit().matches("[a-z][a-z0-9-]*");
			String known = alias ? this.aliases.get(wit.wit()) : null;
			if (known != null) {
				return known;
			}
			Set<String> taken = new HashSet<>(this.names);
			taken.addAll(JavaWitScaffold.this.declared);
			String record = unique(alias ? JavaWitImports.upperCamel(wit.wit()) : this.place, taken);
			this.names.add(record);
			JavaWitScaffold.this.declared.add(record);
			this.tuples.put(record, wit);
			if (alias) {
				this.aliases.put(wit.wit(), record);
			}
			return record;
		}

		/** The type as source names it from anywhere in the file. */
		private String qualified() {
			return this.name.equals(JavaWitScaffold.this.className) ? this.name
					: JavaWitScaffold.this.className + "." + this.name;
		}

		private String text(JavaBoundary.Type wit, boolean boxed, String place) {
			this.place = place;
			return JavaWitTypes.javaText(this, wit, boxed, null, false);
		}

		// ---- the members

		/**
		 * The static method standing for an export, or its refusal; a resource's
		 * constructor, method or static method, in the class standing for the resource.
		 */
		void export(JavaBoundary.Export export) {
			Kind kind = export.kind();
			String resource = export.resource();
			boolean constructor = kind == Kind.CONSTRUCTOR;
			String method = JavaWitImports.lowerCamel(resource == null ? export.name() : export.function());
			String shown = resource == null ? export.name()
					: constructor ? "the constructor of " + resource : resource + "." + export.function();
			String what = (constructor ? JavaWitImports.upperCamel(Objects.requireNonNull(resource)) : method) + " ("
					+ export.name() + " of " + this.scope + ", " + export.at() + ")";
			// a method's handle is its receiver, a constructor's answer the object made
			List<JavaBoundary.Param> params = kind == Kind.METHOD ? export.params().subList(1, export.params().size())
					: export.params();
			JavaBoundary.Type result = constructor ? null : export.result();
			String refused = null;
			for (JavaBoundary.Param param : params) {
				refused = refused != null ? refused
						: refusal(param.type(), false, what + ": its parameter " + param.name());
			}
			if (refused == null && result != null) {
				refused = refusal(result, false, what + ": its result");
			}
			if (refused != null) {
				JavaWitScaffold.this.skipped.add(refused);
				return;
			}
			String placed = resource == null ? method : JavaWitImports.lowerCamel(export.member());
			String place = Character.toUpperCase(placed.charAt(0)) + placed.substring(1);
			List<String> parameters = new ArrayList<>();
			for (JavaBoundary.Param param : params) {
				String parameter = JavaWitImports.lowerCamel(param.name());
				parameters
					.add(text(param.type(), false, place + JavaWitImports.upperCamel(param.name())) + " " + parameter);
			}
			String answer = result == null ? "void" : text(result, false, place + "Result");
			List<String> lines = new ArrayList<>();
			String head = switch (kind) {
				case CONSTRUCTOR -> "public " + JavaWitImports.upperCamel(Objects.requireNonNull(resource));
				case METHOD -> "public " + answer + " " + method;
				default -> "public static " + answer + " " + method;
			};
			javadoc(lines, 0, docs(resource == null ? export.name() : resource + "." + export.function()),
					resource == null ? signature(export.name(), export.params(), result, export.async())
							: signature(export, params));
			lines.add(head + "(" + String.join(", ", parameters) + ") {");
			lines.add(INDENT + "throw new java.lang.UnsupportedOperationException("
					+ literal(shown + " is not implemented yet") + ");");
			lines.add("}");
			if (resource == null) {
				this.methods.add(lines);
			}
			else {
				Objects.requireNonNull(this.resourceMembers.get(resource)).add(lines);
			}
		}

		/** The abstract methods of an imported interface and its resources'. */
		void importMembers() {
			JavaBoundary.WitInterface described = Objects.requireNonNull(this.iface);
			for (JavaBoundary.Function member : described.members()) {
				List<String> lines = switch (member.kind()) {
					case FUNCTION -> member(member, JavaWitImports.lowerCamel(member.function()));
					case CONSTRUCTOR, STATIC -> member(member, JavaWitImports.lowerCamel(member.name()));
					default -> null;
				};
				if (lines != null) {
					this.methods.add(lines);
				}
			}
			for (JavaBoundary.Function drop : described.members()) {
				if (drop.kind() != Kind.DROP) {
					continue;
				}
				String resource = Objects.requireNonNull(drop.resource());
				List<String> lines = new ArrayList<>();
				javadoc(lines, 0, docs(resource), null);
				lines.add("interface " + JavaWitImports.upperCamel(resource) + " extends AutoCloseable {");
				for (JavaBoundary.Function member : described.members()) {
					if (member.kind() == Kind.METHOD && resource.equals(member.resource())) {
						List<String> method = member(member, JavaWitImports.lowerCamel(member.function()));
						if (method != null) {
							lines.add("");
							method.forEach(line -> lines.add(INDENT + line));
						}
					}
				}
				lines.add("");
				lines.add(INDENT + "/**");
				lines.add(INDENT + " * Gives the " + resource + " back to the host.");
				lines.add(INDENT + " */");
				lines.add(INDENT + "@Override");
				lines.add(INDENT + "void close();");
				lines.add("}");
				this.resources.add(lines);
			}
		}

		/** An abstract method standing for a member, or {@code null} for its refusal. */
		private @Nullable List<String> member(JavaBoundary.Function member, String method) {
			JavaBoundary.WitInterface described = Objects.requireNonNull(this.iface);
			String what = method + " (" + member.name() + " of " + described.id() + ", " + member.at() + ")";
			List<JavaBoundary.Param> params = member.kind() == Kind.METHOD
					? member.params().subList(1, member.params().size()) : member.params();
			String refused = null;
			for (JavaBoundary.Param param : params) {
				if (refused == null) {
					refused = handed(param, member.async(), what + ": its parameter " + param.name());
				}
			}
			JavaBoundary.Type result = member.result();
			if (refused == null && result != null) {
				refused = refusal(result, false, what + ": its result");
			}
			if (refused == null) {
				refused = usedHome(params, result, what);
			}
			if (refused != null) {
				JavaWitScaffold.this.skipped.add(refused);
				return null;
			}
			String place = Character.toUpperCase(method.charAt(0)) + method.substring(1);
			List<String> parameters = new ArrayList<>();
			for (JavaBoundary.Param param : params) {
				String parameter = JavaWitImports.lowerCamel(param.name());
				JavaBoundary.Type type = param.type();
				boolean stream = JavaWitImports.asyncIn(type) != null && type.rep() != Rep.FUTURE_HANDLE;
				parameters.add((stream ? JavaWitImports.handedText(type, false)
						: text(type, false, place + JavaWitImports.upperCamel(param.name()))) + " " + parameter);
			}
			String answer = result == null ? "void" : text(result, false, place + "Result");
			List<String> lines = new ArrayList<>();
			javadoc(lines, 0,
					docs(member.resource() == null ? member.function() : member.resource() + "." + member.function()),
					signature(member, params));
			lines.add(answer + " " + method + "(" + String.join(", ", parameters) + ");");
			return lines;
		}

		/**
		 * The refusal of a parameter, as the check makes it: one carrying a stream or a
		 * future the program hands the host by {@link JavaWitImports#handedRefusal}, then
		 * a future's value and error arm; any other as an answer's type.
		 */
		private @Nullable String handed(JavaBoundary.Param param, boolean async, String what) {
			JavaBoundary.Type type = param.type();
			if (JavaWitImports.asyncIn(type) == null) {
				return refusal(type, false, what);
			}
			String refused = JavaWitImports.handedRefusal(type, async);
			if (refused != null) {
				return what + " is " + type.wit() + ", " + refused;
			}
			if (type.rep() != Rep.FUTURE_HANDLE) {
				return null;
			}
			JavaBoundary.Type payload = Objects.requireNonNull(type.element());
			JavaBoundary.Type value = payload.rep() == Rep.RESULT ? payload.element() : payload;
			String valued = value == null ? null : refusal(value, false, what + ": its future's value");
			return valued != null || payload.rep() != Rep.RESULT || payload.error() == null ? valued
					: refusal(payload.error(), true, what + ": its future's error arm");
		}

		/**
		 * The refusal of a member naming a resource another interface defines when no
		 * member interface stands for that one: its import was left out.
		 */
		private @Nullable String usedHome(List<JavaBoundary.Param> params, JavaBoundary.@Nullable Type result,
				String what) {
			List<JavaBoundary.Type> types = new ArrayList<>();
			params.forEach(param -> types.add(param.type()));
			if (result != null) {
				types.add(result);
			}
			Map<String, String> used = Objects.requireNonNull(this.iface).used();
			while (!types.isEmpty()) {
				JavaBoundary.Type type = types.remove(types.size() - 1);
				String definition = type.definition();
				if (type.rep() == Rep.HANDLE && definition != null && used.containsKey(definition)
						&& !JavaWitScaffold.this.imports.containsKey(used.get(definition))) {
					return what + " is " + type.wit() + ", the resource " + definition + " of " + used.get(definition)
							+ ", whose interface is left out";
				}
				if (type.element() != null) {
					types.add(type.element());
				}
				if (type.error() != null) {
					types.add(type.error());
				}
				type.parts().stream().map(JavaBoundary.Part::type).filter(Objects::nonNull).forEach(types::add);
			}
			return null;
		}

		// ---- the text

		/**
		 * Writes the members at a depth: for an export's scope the types, then the
		 * methods; for an import the methods, the resources, then the types.
		 */
		void members(List<String> out, int depth) {
			// the declarations, made as the types they name are reached: the named
			// types, then the tuples' records
			List<List<String>> named = new ArrayList<>();
			List<List<String>> records = new ArrayList<>();
			Set<String> written = new HashSet<>();
			int tuplesWritten = 0;
			boolean more = true;
			while (more) {
				more = false;
				for (Map.Entry<String, JavaBoundary.Type> entry : List.copyOf(this.reached.entrySet())) {
					if (written.add(entry.getKey())) {
						named.add(declaration(entry.getKey(), entry.getValue()));
						more = true;
					}
				}
				List<Map.Entry<String, JavaBoundary.Type>> tuples = List.copyOf(this.tuples.entrySet());
				for (; tuplesWritten < tuples.size(); tuplesWritten++) {
					records.add(tuple(tuples.get(tuplesWritten).getKey(), tuples.get(tuplesWritten).getValue()));
					more = true;
				}
			}
			List<List<String>> types = new ArrayList<>(named);
			types.addAll(records);
			List<List<String>> order = new ArrayList<>();
			if (this.iface == null) {
				order.addAll(types);
				for (Map.Entry<String, List<List<String>>> resource : this.resourceMembers.entrySet()) {
					order.add(resourceClass(resource.getKey(), resource.getValue()));
				}
				order.addAll(this.methods);
			}
			else {
				order.addAll(this.methods);
				order.addAll(this.resources);
				order.addAll(types);
			}
			String indent = INDENT.repeat(depth);
			for (List<String> member : order) {
				out.add("");
				member.forEach(line -> out.add(line.isEmpty() ? "" : indent + line));
			}
		}

		/**
		 * The class standing for a resource the world exports: its constructor, methods
		 * and static methods, then {@code close()}, its destructor.
		 */
		private List<String> resourceClass(String resource, List<List<String>> members) {
			List<String> lines = new ArrayList<>();
			javadoc(lines, 0, docs(resource), null);
			lines.add("public static class " + JavaWitImports.upperCamel(resource)
					+ " implements java.lang.AutoCloseable {");
			for (List<String> member : members) {
				lines.add("");
				member.forEach(line -> lines.add(line.isEmpty() ? "" : INDENT + line));
			}
			lines.add("");
			lines.add(INDENT + "/**");
			lines.add(INDENT + " * The destructor: the component calls it when a holder releases a handle of the "
					+ resource + ".");
			lines.add(INDENT + " */");
			lines.add(INDENT + "@Override");
			lines.add(INDENT + "public void close() {");
			lines.add(INDENT + "}");
			lines.add("}");
			return lines;
		}

		private String modifier() {
			return this.isInterface ? "" : "public ";
		}

		private List<String> declaration(String member, JavaBoundary.Type wit) {
			List<String> lines = new ArrayList<>();
			javadoc(lines, 0, docs(Objects.requireNonNull(wit.definition())), null);
			switch (wit.rep()) {
				case PLIST -> {
					List<String> components = new ArrayList<>();
					for (JavaBoundary.Part part : wit.parts()) {
						components.add(text(Objects.requireNonNull(part.type()), false,
								member + JavaWitImports.upperCamel(part.label())) + " "
								+ JavaWitImports.lowerCamel(part.label()));
					}
					lines.add(modifier() + "record " + member + "(" + String.join(", ", components) + ") {");
					lines.add("}");
				}
				case TAGGED_LIST -> {
					lines.add(modifier() + "sealed interface " + member + " {");
					Set<String> cases = new HashSet<>();
					wit.parts().forEach(part -> cases.add(JavaWitImports.upperCamel(part.label())));
					for (JavaBoundary.Part part : wit.parts()) {
						String record = JavaWitImports.upperCamel(part.label());
						JavaBoundary.Type payload = part.type();
						this.shadowed = cases;
						String component = payload == null ? "" : text(payload, false, member + record) + " value";
						this.shadowed = Set.of();
						lines.add("");
						lines.add(INDENT + "record " + record + "(" + component + ") implements " + member + " {");
						lines.add(INDENT + "}");
					}
					lines.add("}");
				}
				default -> {
					// an enum, or the enum of flags, which a Set of it is
					List<String> constants = new ArrayList<>();
					wit.parts().forEach(part -> constants.add(JavaWitImports.constant(part.label())));
					lines.add(modifier() + "enum " + member + " {");
					lines.add("");
					lines.add(INDENT + String.join(", ", constants));
					lines.add("");
					lines.add("}");
				}
			}
			return lines;
		}

		private List<String> tuple(String record, JavaBoundary.Type wit) {
			List<String> components = new ArrayList<>();
			for (int i = 0; i < wit.parts().size(); i++) {
				components
					.add(text(Objects.requireNonNull(wit.parts().get(i).type()), false, record + "V" + i) + " v" + i);
			}
			List<String> lines = new ArrayList<>();
			lines.add("// " + wit.wit() + ": any record of its components");
			lines.add(modifier() + "record " + record + "(" + String.join(", ", components) + ") {");
			lines.add("}");
			return lines;
		}

	}

	// ---- helpers

	/** A WIT signature, as the WIT writes it. */
	private static String signature(String name, List<JavaBoundary.Param> params, JavaBoundary.@Nullable Type result,
			boolean async) {
		return name + ": " + (async ? "async " : "") + "func(" + parameters(params) + ")"
				+ (result == null ? "" : " -> " + result.wit());
	}

	/** A resource's function as the WIT writes it. */
	private static String signature(JavaBoundary.Export export, List<JavaBoundary.Param> params) {
		JavaBoundary.Type result = export.result();
		return switch (export.kind()) {
			case CONSTRUCTOR -> "constructor(" + parameters(params) + ")";
			case STATIC -> export.function() + ": " + (export.async() ? "async " : "") + "static func("
					+ parameters(params) + ")" + (result == null ? "" : " -> " + result.wit());
			default -> signature(export.function(), params, result, export.async());
		};
	}

	private static String signature(JavaBoundary.Function member, List<JavaBoundary.Param> params) {
		return switch (member.kind()) {
			case CONSTRUCTOR -> "constructor(" + parameters(params) + ")";
			case STATIC -> member.function() + ": " + (member.async() ? "async " : "") + "static func("
					+ parameters(params) + ")" + (member.result() == null ? "" : " -> " + member.result().wit());
			default -> signature(member.function(), params, member.result(), member.async());
		};
	}

	private static String parameters(List<JavaBoundary.Param> params) {
		List<String> written = new ArrayList<>();
		params.forEach(param -> written.add(param.name() + ": " + param.type().wit()));
		return String.join(", ", written);
	}

	/**
	 * A Javadoc comment of the WIT's {@code ///} lines, then the WIT signature, or none
	 * for neither.
	 */
	private static void javadoc(List<String> out, int depth, List<String> docs, @Nullable String wit) {
		if (docs.isEmpty() && wit == null) {
			return;
		}
		String indent = INDENT.repeat(depth);
		out.add(indent + "/**");
		for (String doc : docs) {
			out.add(indent + " *" + escaped(doc).replace("*/", "*&#47;"));
		}
		if (wit != null) {
			if (!docs.isEmpty()) {
				out.add(indent + " *");
			}
			out.add(indent + " * WIT: {@code " + wit + "}");
		}
		out.add(indent + " */");
	}

	/** A text a comment holds, with no Unicode escape for the compiler to read. */
	private static String escaped(String text) {
		return text.replace("\\u", "\\\\u");
	}

	private static String literal(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** A name none of the taken ones is: the name, else it numbered from 2. */
	private static String unique(String name, Set<String> taken) {
		if (!taken.contains(name)) {
			return name;
		}
		for (int i = 2;; i++) {
			if (!taken.contains(name + i)) {
				return name + i;
			}
		}
	}

}
