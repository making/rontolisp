package am.ik.rontolisp.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.eval.ClojureDepsRepositories;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import org.jspecify.annotations.Nullable;

/**
 * What a compiled JVM class whose Clojure program lowers at run time carries to lower
 * that program again when it first runs a lowering verb ({@code eval}, a computed
 * {@code resolve} ...): the program text, its entry file, the standards it was read
 * against, every file answer the compile-time lowering got -- the read of a file, whether
 * one exists, a directory's listing -- and where its open passes began, so the run-time
 * lowering takes the same decisions wherever the class runs, without the passes before,
 * and names the same functions and globals the class holds
 * ({@code cli/JvmRunTimeLowering}).
 *
 * <p>
 * The class holds it as one string ({@link #encode()}), opaque to the backend.
 *
 * @param source the program text
 * @param entryFile the path the text was read from, or {@code null}
 * @param clojureConfigDir the user-level {@code deps.edn} directory, or {@code null}
 * @param clojureAliases the selected {@code deps.edn} aliases
 * @param fetches whether the read fetched {@code deps.edn} coordinates (the command
 * line's), so the run-time read fetches through the same caches
 * @param reads the text of every file the lowering read, by path
 * @param exists the answer to every existence question the lowering asked, by path
 * @param listings the listing of every directory the lowering listed, by path (a missing
 * directory absent)
 * @param decisions where the lowering's open passes began
 * ({@code eval/ClojureProgramLowering#decisions}), or {@code null} to lower from nothing
 */
record RunTimeImage(String source, @Nullable String entryFile, @Nullable String clojureConfigDir,
		List<String> clojureAliases, boolean fetches, Map<String, String> reads, Map<String, Boolean> exists,
		Map<String, List<String>> listings, @Nullable String decisions) {

	private static final String VERSION = "rontolisp-run-time-image-2";

	RunTimeImage {
		clojureAliases = List.copyOf(clojureAliases);
	}

	/** The standards the program is read against again. */
	SourceStandards standards() {
		return SourceStandards.DEFAULT.withClojureConfigDir(this.clojureConfigDir)
			.withClojureAliases(this.clojureAliases)
			.withClojureRepositories(this.fetches ? ClojureDepsRepositories.createDefault() : null);
	}

	/**
	 * The loader the program is read through again: every path the compile-time read
	 * asked about answers as it did then, any other through the file system (what the
	 * program loads as it runs).
	 * @param javaClasses the class loader of the running class
	 * @return the loader
	 */
	SourceLoader loader(ClassLoader javaClasses) {
		SourceLoader files = SourceLoader.fileSystem(javaClasses);
		RunTimeImage image = this;
		return new SourceLoader() {
			@Override
			public String load(String path) throws IOException {
				String text = image.reads.get(path);
				if (text != null) {
					return text;
				}
				if (Boolean.FALSE.equals(image.exists.get(path))) {
					throw new IOException(path + ": no such file");
				}
				return files.load(path);
			}

			@Override
			public byte[] loadBytes(String path) throws IOException {
				String text = image.reads.get(path);
				return text != null ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8) : files.loadBytes(path);
			}

			@Override
			public boolean exists(String path) {
				Boolean answer = image.exists.get(path);
				return answer != null ? answer : image.reads.containsKey(path) || files.exists(path);
			}

			@Override
			public @Nullable List<String> listDirectory(String path) {
				if (image.listings.containsKey(path)) {
					return image.listings.get(path);
				}
				return files.listDirectory(path);
			}

			@Override
			public ClassLoader javaClassLoader() {
				return javaClasses;
			}

			@Override
			public String canonicalPath(String path) {
				return files.canonicalPath(path);
			}

			@Override
			public @Nullable String workingDirectory() {
				return files.workingDirectory();
			}

			@Override
			public @Nullable List<String> listArchive(String path) {
				return files.listArchive(path);
			}

			@Override
			public String loadArchiveEntry(String archive, String entry) throws IOException {
				return files.loadArchiveEntry(archive, entry);
			}

			@Override
			public byte[] loadArchiveEntryBytes(String archive, String entry) throws IOException {
				return files.loadArchiveEntryBytes(archive, entry);
			}
		};
	}

	/**
	 * The image as one string: each field a length-prefixed run.
	 * @return the encoded image
	 */
	String encode() {
		List<String> fields = new ArrayList<>();
		fields.add(VERSION);
		fields.add(this.source);
		fields.add(this.entryFile == null ? "-" : "+" + this.entryFile);
		fields.add(this.clojureConfigDir == null ? "-" : "+" + this.clojureConfigDir);
		fields.add(Integer.toString(this.clojureAliases.size()));
		fields.addAll(this.clojureAliases);
		fields.add(this.fetches ? "+" : "-");
		fields.add(Integer.toString(this.reads.size()));
		this.reads.forEach((path, text) -> {
			fields.add(path);
			fields.add(text);
		});
		fields.add(Integer.toString(this.exists.size()));
		this.exists.forEach((path, answer) -> {
			fields.add(path);
			fields.add(answer ? "+" : "-");
		});
		fields.add(Integer.toString(this.listings.size()));
		this.listings.forEach((path, names) -> {
			fields.add(path);
			fields.add(Integer.toString(names.size()));
			fields.addAll(names);
		});
		fields.add(this.decisions == null ? "-" : "+" + this.decisions);
		StringBuilder out = new StringBuilder();
		for (String field : fields) {
			out.append(field.length()).append(':').append(field);
		}
		return out.toString();
	}

	/**
	 * The image an {@link #encode()} string holds.
	 * @param text the encoded image
	 * @return the image
	 * @throws IllegalArgumentException when the text is no image of this version
	 */
	static RunTimeImage decode(String text) {
		Fields in = new Fields(text);
		if (!VERSION.equals(in.next())) {
			throw new IllegalArgumentException("not a " + VERSION);
		}
		String source = in.next();
		String entryFile = in.optional();
		String configDir = in.optional();
		int aliasCount = Integer.parseInt(in.next());
		List<String> aliases = new ArrayList<>();
		for (int i = 0; i < aliasCount; i++) {
			aliases.add(in.next());
		}
		boolean fetches = in.next().equals("+");
		Map<String, String> reads = new LinkedHashMap<>();
		int readCount = Integer.parseInt(in.next());
		for (int i = 0; i < readCount; i++) {
			reads.put(in.next(), in.next());
		}
		Map<String, Boolean> exists = new LinkedHashMap<>();
		int existsCount = Integer.parseInt(in.next());
		for (int i = 0; i < existsCount; i++) {
			exists.put(in.next(), in.next().equals("+"));
		}
		Map<String, List<String>> listings = new LinkedHashMap<>();
		int listingCount = Integer.parseInt(in.next());
		for (int i = 0; i < listingCount; i++) {
			String path = in.next();
			int count = Integer.parseInt(in.next());
			List<String> names = new ArrayList<>();
			for (int j = 0; j < count; j++) {
				names.add(in.next());
			}
			listings.put(path, List.copyOf(names));
		}
		String decisions = in.optional();
		return new RunTimeImage(source, entryFile, configDir, aliases, fetches, reads, exists, listings, decisions);
	}

	private static final class Fields {

		private final String text;

		private int pos;

		Fields(String text) {
			this.text = text;
		}

		String next() {
			int colon = this.text.indexOf(':', this.pos);
			if (colon < 0) {
				throw new IllegalArgumentException("a truncated run-time image");
			}
			int length = Integer.parseInt(this.text.substring(this.pos, colon));
			this.pos = colon + 1 + length;
			return this.text.substring(colon + 1, this.pos);
		}

		@Nullable String optional() {
			String field = next();
			return field.equals("-") ? null : field.substring(1);
		}

	}

	/**
	 * A loader that answers through another and records every answer the image keeps.
	 */
	static final class Recording implements SourceLoader {

		private final SourceLoader delegate;

		private final Map<String, String> reads = new LinkedHashMap<>();

		private final Map<String, Boolean> exists = new LinkedHashMap<>();

		private final Map<String, List<String>> listings = new LinkedHashMap<>();

		Recording(SourceLoader delegate) {
			this.delegate = delegate;
		}

		/**
		 * The image of a program read through this loader.
		 * @param source the program text
		 * @param entryFile its entry file, or {@code null}
		 * @param standards the standards it was read against
		 * @param decisions where its lowering's open passes began, or {@code null}
		 * @return the image
		 */
		synchronized RunTimeImage image(String source, @Nullable String entryFile, SourceStandards standards,
				@Nullable String decisions) {
			return new RunTimeImage(source, entryFile, standards.clojureConfigDir(), standards.clojureAliases(),
					standards.clojureRepositories() != null, new LinkedHashMap<>(this.reads),
					new LinkedHashMap<>(this.exists), new LinkedHashMap<>(this.listings), decisions);
		}

		@Override
		public String load(String path) throws IOException {
			String text = this.delegate.load(path);
			synchronized (this) {
				this.reads.put(path, text);
			}
			return text;
		}

		@Override
		public boolean exists(String path) {
			boolean answer = this.delegate.exists(path);
			synchronized (this) {
				this.exists.put(path, answer);
			}
			return answer;
		}

		@Override
		public @Nullable List<String> listDirectory(String path) {
			List<String> names = this.delegate.listDirectory(path);
			if (names != null) {
				synchronized (this) {
					this.listings.put(path, List.copyOf(names));
				}
			}
			return names;
		}

		@Override
		public byte[] loadBytes(String path) throws IOException {
			return this.delegate.loadBytes(path);
		}

		@Override
		public ClassLoader javaClassLoader() {
			return this.delegate.javaClassLoader();
		}

		@Override
		public @Nullable JavaClassLookup javaClassLookup() {
			return this.delegate.javaClassLookup();
		}

		@Override
		public void addJavaClassPath(String jar, @Nullable String mavenCoordinate) {
			this.delegate.addJavaClassPath(jar, mavenCoordinate);
		}

		@Override
		public String canonicalPath(String path) {
			return this.delegate.canonicalPath(path);
		}

		@Override
		public @Nullable String workingDirectory() {
			return this.delegate.workingDirectory();
		}

		@Override
		public @Nullable List<String> listArchive(String path) {
			return this.delegate.listArchive(path);
		}

		@Override
		public String loadArchiveEntry(String archive, String entry) throws IOException {
			return this.delegate.loadArchiveEntry(archive, entry);
		}

		@Override
		public byte[] loadArchiveEntryBytes(String archive, String entry) throws IOException {
			return this.delegate.loadArchiveEntryBytes(archive, entry);
		}

		@Override
		public boolean isDirectory(String path) {
			return listDirectory(path) != null;
		}

		@Override
		public @Nullable Long writeDate(String path) {
			return this.delegate.writeDate(path);
		}

		@Override
		public @Nullable String readLink(String path) {
			return this.delegate.readLink(path);
		}

	}

}
