package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The reachability metadata a program and its libraries declare, in the files GraalVM's
 * native image reads (its published documentation of the format): the classes a program
 * finds by a name it computes, and the members of a library's class it uses by reflection
 * ({@code .kb/java-frontend.md}, "Reflection from reachability metadata"). Read where the
 * program is compiled, from {@code META-INF/native-image/} of the source tree and of each
 * root of the source path: {@code reachability-metadata.json}, whose {@code reflection}
 * entries name a class by {@code type}, and the older {@code reflect-config.json}, an
 * array of entries naming it by {@code name}. Only the reflection entries are read; a
 * program's resources, bundles and services are found while it is compiled whatever the
 * metadata says.
 */
final class JavaReachabilityMetadata {

	private JavaReachabilityMetadata() {
	}

	/** Where a class path entry's metadata is, under a directory of its own. */
	static final String DIRECTORY = "META-INF/native-image/";

	/** The file of the format GraalVM reads today. */
	static final String FILE = "reachability-metadata.json";

	/** The file of the older format, an array of entries. */
	static final String LEGACY_FILE = "reflect-config.json";

	/** Every method the class declares, whatever its access. */
	static final int DECLARED_METHODS = 1;

	/** Every public method the class declares. */
	static final int PUBLIC_METHODS = 2;

	static final int DECLARED_FIELDS = 4;

	static final int PUBLIC_FIELDS = 8;

	static final int DECLARED_CONSTRUCTORS = 16;

	static final int PUBLIC_CONSTRUCTORS = 32;

	/** The flags of an entry by the names the format gives them. */
	private static final Map<String, Integer> FLAGS = Map.of("allDeclaredMethods", DECLARED_METHODS, "allPublicMethods",
			PUBLIC_METHODS, "allDeclaredFields", DECLARED_FIELDS, "allPublicFields", PUBLIC_FIELDS,
			"allDeclaredConstructors", DECLARED_CONSTRUCTORS, "allPublicConstructors", PUBLIC_CONSTRUCTORS);

	/**
	 * An entry of the reflection metadata.
	 *
	 * @param type the class's binary name ({@code a.B$C})
	 * @param condition the binary name of the class that has to be reached for the entry
	 * to count, or {@code null} where it always counts
	 * @param flags the kinds of members it declares whole
	 * @param methods the methods and constructors ({@code <init>}) it declares, each
	 * {@link #member keyed} by its name and parameter types
	 * @param fields the fields it declares, by name
	 */
	record Entry(String type, @Nullable String condition, int flags, List<String> methods, List<String> fields) {
	}

	/**
	 * What the entries of one class that count declare of it, joined: the flags, the
	 * methods by {@link #member key}, the fields by name.
	 */
	static final class Declared {

		int flags;

		final Set<String> methods = new LinkedHashSet<>();

		final Set<String> fields = new LinkedHashSet<>();

		void add(Entry entry) {
			this.flags |= entry.flags();
			this.methods.addAll(entry.methods());
			this.fields.addAll(entry.fields());
		}

		void add(Declared other) {
			this.flags |= other.flags;
			this.methods.addAll(other.methods);
			this.fields.addAll(other.fields);
		}

		/**
		 * Whether a method or a constructor is declared.
		 * @param name its name, {@code <init>} for a constructor
		 * @param parameters the binary names of the types reflection answers it takes
		 * @param isPublic whether it is public
		 */
		boolean declaresMethod(String name, List<String> parameters, boolean isPublic) {
			boolean constructor = name.equals("<init>");
			int all = constructor ? DECLARED_CONSTRUCTORS : DECLARED_METHODS;
			int open = constructor ? PUBLIC_CONSTRUCTORS : PUBLIC_METHODS;
			return (this.flags & all) != 0 || (isPublic && (this.flags & open) != 0)
					|| this.methods.contains(member(name, parameters)) || this.methods.contains(member(name, null));
		}

		boolean declaresField(String name, boolean isPublic) {
			return (this.flags & DECLARED_FIELDS) != 0 || (isPublic && (this.flags & PUBLIC_FIELDS) != 0)
					|| this.fields.contains(name);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Declared declared && declared.flags == this.flags
					&& declared.methods.equals(this.methods) && declared.fields.equals(this.fields);
		}

		@Override
		public int hashCode() {
			return this.flags ^ this.methods.hashCode() ^ this.fields.hashCode();
		}

	}

	/**
	 * A method's key: its name and the binary names of its parameter types, or its name
	 * alone ({@code name(*)}) where an entry of the older format gives no types, which
	 * declares every method of the name.
	 */
	static String member(String name, @Nullable List<String> parameters) {
		return name + "(" + (parameters == null ? "*" : String.join(",", parameters)) + ")";
	}

	/**
	 * A type as an entry names it, made the binary name a class's reflection answers:
	 * {@code java.lang.String[]} for an array, also where it is written as a descriptor
	 * ({@code [Ljava.lang.String;}).
	 */
	static String typeName(String written) {
		String name = written.strip();
		int dimensions = 0;
		while (name.startsWith("[")) {
			dimensions++;
			name = name.substring(1);
		}
		if (dimensions > 0) {
			name = switch (name) {
				case "Z" -> "boolean";
				case "B" -> "byte";
				case "C" -> "char";
				case "S" -> "short";
				case "I" -> "int";
				case "J" -> "long";
				case "F" -> "float";
				case "D" -> "double";
				default -> name.startsWith("L") && name.endsWith(";") ? name.substring(1, name.length() - 1) : name;
			};
		}
		return name + "[]".repeat(dimensions);
	}

	/**
	 * The reflection entries of a file.
	 * @param path where the file is, for a refusal's position
	 * @param text its text
	 * @param legacy whether it is a {@code reflect-config.json}
	 * @return the entries naming a class: of a proxy or a lambda, which the format
	 * describes by an object, none
	 * @throws LispReadException where the file is no JSON or no metadata of its format
	 */
	static List<Entry> read(String path, String text, boolean legacy) {
		Object document = new Json(path, text).document();
		List<Entry> entries = new ArrayList<>();
		Object reflection;
		if (legacy) {
			reflection = document;
		}
		else {
			if (!(document instanceof Map<?, ?> sections)) {
				throw malformed(path, text, "an object of sections");
			}
			reflection = sections.get("reflection");
			if (reflection == null) {
				return entries;
			}
		}
		if (!(reflection instanceof List<?> list)) {
			throw malformed(path, text, legacy ? "an array of entries" : "an array of reflection entries");
		}
		for (Object each : list) {
			if (!(each instanceof Map<?, ?> entry)) {
				throw malformed(path, text, "an object for each entry");
			}
			Object type = entry.get(legacy ? "name" : "type");
			if (!(type instanceof String named)) {
				// a proxy or a lambda, which the subset has neither of
				continue;
			}
			String condition = null;
			if (entry.get("condition") instanceof Map<?, ?> written) {
				Object reached = written.get(legacy ? "typeReachable" : "typeReached");
				if (reached == null) {
					reached = written.get(legacy ? "typeReached" : "typeReachable");
				}
				if (reached instanceof String name) {
					condition = typeName(name);
				}
			}
			int flags = 0;
			for (Map.Entry<String, Integer> flag : FLAGS.entrySet()) {
				if (Boolean.TRUE.equals(entry.get(flag.getKey()))) {
					flags |= flag.getValue();
				}
			}
			List<String> methods = new ArrayList<>();
			if (entry.get("methods") instanceof List<?> written) {
				for (Object method : written) {
					if (method instanceof Map<?, ?> member && member.get("name") instanceof String name) {
						List<String> parameters = null;
						if (member.get("parameterTypes") instanceof List<?> types) {
							parameters = new ArrayList<>();
							for (Object parameter : types) {
								if (parameter instanceof String typed) {
									parameters.add(typeName(typed));
								}
							}
						}
						methods.add(member(name, parameters));
					}
				}
			}
			List<String> fields = new ArrayList<>();
			if (entry.get("fields") instanceof List<?> written) {
				for (Object field : written) {
					if (field instanceof Map<?, ?> member && member.get("name") instanceof String name) {
						fields.add(name);
					}
				}
			}
			entries.add(new Entry(typeName(named), condition, flags, List.copyOf(methods), List.copyOf(fields)));
		}
		return entries;
	}

	/**
	 * Whether a class is of the JDK's packages, which the shared repository's entries
	 * name where they were traced from the JDK's own reflection.
	 */
	static boolean ofTheJdk(String type) {
		for (String jdk : JDK_PACKAGES) {
			if (type.startsWith(jdk)) {
				return true;
			}
		}
		return false;
	}

	private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.",
			"org.xml.", "org.ietf.");

	/**
	 * The directory of the shared repository's metadata for a version of a library, from
	 * the library's {@code index.json}: the entry whose {@code tested-versions} lists the
	 * version, else the one marked {@code latest}.
	 * @param path where the index is, for a refusal
	 * @param text the index
	 * @param version the library's version
	 * @return the entry's {@code metadata-version}, or {@code null} where none fits
	 */
	static @Nullable String repositoryVersion(String path, String text, String version) {
		if (!(new Json(path, text).document() instanceof List<?> entries)) {
			throw malformed(path, text, "an array of the library's versions");
		}
		String latest = null;
		for (Object each : entries) {
			if (!(each instanceof Map<?, ?> entry) || !(entry.get("metadata-version") instanceof String directory)) {
				continue;
			}
			if (entry.get("tested-versions") instanceof List<?> tested && tested.contains(version)) {
				return directory;
			}
			if (Boolean.TRUE.equals(entry.get("latest"))) {
				latest = directory;
			}
		}
		return latest;
	}

	private static LispReadException malformed(String path, String text, String expected) {
		return JavaClasses.error(new JavaClass.Source(path, text, JavaClass.Origin.DEPENDENCY), 0,
				"reachability metadata " + path + " is not " + expected);
	}

	/**
	 * A JSON text (RFC 8259) read into maps, lists, strings, numbers as their text,
	 * {@code Boolean}s and {@code null}s; a text that is not JSON is refused where it
	 * stops being one.
	 */
	private static final class Json {

		private final String path;

		private final String text;

		private int at;

		Json(String path, String text) {
			this.path = path;
			this.text = text;
		}

		@Nullable Object document() {
			// a byte order mark ahead of the text
			if (!this.text.isEmpty() && this.text.charAt(0) == 0xFEFF) {
				this.at = 1;
			}
			Object value = value();
			blank();
			if (this.at < this.text.length()) {
				throw refused("the end of the text");
			}
			return value;
		}

		private @Nullable Object value() {
			blank();
			if (this.at >= this.text.length()) {
				throw refused("a value");
			}
			char c = this.text.charAt(this.at);
			switch (c) {
				case '{':
					return object();
				case '[':
					return array();
				case '"':
					return string();
				case 't':
					return word("true", Boolean.TRUE);
				case 'f':
					return word("false", Boolean.FALSE);
				case 'n':
					return word("null", null);
				default:
					if (c == '-' || (c >= '0' && c <= '9')) {
						return number();
					}
					throw refused("a value");
			}
		}

		private Map<String, @Nullable Object> object() {
			Map<String, @Nullable Object> members = new LinkedHashMap<>();
			this.at++;
			blank();
			if (next('}')) {
				return members;
			}
			do {
				blank();
				if (this.at >= this.text.length() || this.text.charAt(this.at) != '"') {
					throw refused("a member's name");
				}
				String name = string();
				blank();
				if (!next(':')) {
					throw refused("':'");
				}
				members.put(name, value());
				blank();
			}
			while (next(','));
			if (!next('}')) {
				throw refused("',' or '}'");
			}
			return members;
		}

		private List<@Nullable Object> array() {
			List<@Nullable Object> elements = new ArrayList<>();
			this.at++;
			blank();
			if (next(']')) {
				return elements;
			}
			do {
				elements.add(value());
				blank();
			}
			while (next(','));
			if (!next(']')) {
				throw refused("',' or ']'");
			}
			return elements;
		}

		private String string() {
			StringBuilder read = new StringBuilder();
			this.at++;
			while (true) {
				if (this.at >= this.text.length()) {
					throw refused("the end of a string");
				}
				char c = this.text.charAt(this.at++);
				if (c == '"') {
					return read.toString();
				}
				if (c < 0x20) {
					this.at--;
					throw refused("a character that is no control character");
				}
				if (c != '\\') {
					read.append(c);
					continue;
				}
				char escaped = this.at < this.text.length() ? this.text.charAt(this.at++) : 0;
				switch (escaped) {
					case '"', '\\', '/' -> read.append(escaped);
					case 'b' -> read.append('\b');
					case 'f' -> read.append('\f');
					case 'n' -> read.append('\n');
					case 'r' -> read.append('\r');
					case 't' -> read.append('\t');
					case 'u' -> {
						if (this.at + 4 > this.text.length()) {
							throw refused("four hexadecimal digits");
						}
						int unit = 0;
						for (int i = 0; i < 4; i++) {
							int digit = Character.digit(this.text.charAt(this.at++), 16);
							if (digit < 0) {
								this.at--;
								throw refused("a hexadecimal digit");
							}
							unit = unit * 16 + digit;
						}
						read.append((char) unit);
					}
					default -> {
						this.at--;
						throw refused("an escape");
					}
				}
			}
		}

		private String number() {
			int start = this.at;
			next('-');
			if (!next('0')) {
				digits();
			}
			if (next('.')) {
				digits();
			}
			if (this.at < this.text.length()
					&& (this.text.charAt(this.at) == 'e' || this.text.charAt(this.at) == 'E')) {
				this.at++;
				if (!next('+')) {
					next('-');
				}
				digits();
			}
			return this.text.substring(start, this.at);
		}

		private void digits() {
			int start = this.at;
			while (this.at < this.text.length() && this.text.charAt(this.at) >= '0'
					&& this.text.charAt(this.at) <= '9') {
				this.at++;
			}
			if (this.at == start) {
				throw refused("a digit");
			}
		}

		private @Nullable Object word(String word, @Nullable Object value) {
			if (!this.text.startsWith(word, this.at)) {
				throw refused("a value");
			}
			this.at += word.length();
			return value;
		}

		private boolean next(char c) {
			if (this.at < this.text.length() && this.text.charAt(this.at) == c) {
				this.at++;
				return true;
			}
			return false;
		}

		private void blank() {
			while (this.at < this.text.length()) {
				char c = this.text.charAt(this.at);
				if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
					return;
				}
				this.at++;
			}
		}

		private LispReadException refused(String expected) {
			return JavaClasses.error(new JavaClass.Source(this.path, this.text, JavaClass.Origin.DEPENDENCY),
					Math.min(this.at, this.text.length()),
					"reachability metadata " + this.path + " is no JSON: " + expected + " expected");
		}

	}

}
