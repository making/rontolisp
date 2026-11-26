package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.wit.WitDocument;
import am.ik.wit.WitItem;
import am.ik.wit.WitLocations;
import am.ik.wit.WitPackageAssembler;
import am.ik.wit.WitParseException;
import am.ik.wit.WitParseResult;
import am.ik.wit.WitParser;
import am.ik.wit.WitPrinter;
import am.ik.wit.WitSourceFile;

import org.jspecify.annotations.Nullable;

/**
 * The WIT a {@code rontolisp:wit-import} or {@code rontolisp:wit-export} reads: one
 * {@code .wit} file, or a package published as a directory -- its {@code *.wit} files and
 * the packages its {@code deps/} holds ({@link WitPackageAssembler}). The reading is the
 * front end's ({@code eval/WitFiles}); this is what the directives parse and name in
 * their messages.
 */
public final class WitSource {

	private final String path;

	private final @Nullable String text;

	private final List<WitSourceFile> files;

	private final List<List<WitSourceFile>> deps;

	private WitSource(String path, @Nullable String text, List<WitSourceFile> files, List<List<WitSourceFile>> deps) {
		this.path = path;
		this.text = text;
		this.files = files;
		this.deps = deps;
	}

	/**
	 * One {@code .wit} file.
	 * @param path the file's path, for messages
	 * @param text its WIT text
	 * @return the source
	 */
	public static WitSource file(String path, String text) {
		return new WitSource(path, text, List.of(), List.of());
	}

	/**
	 * A package read from its directory.
	 * @param path the directory's path, for messages
	 * @param files the package's {@code .wit} files, sorted by name
	 * @param deps each {@code deps/} entry's files, the entries sorted by name
	 * @return the source
	 */
	public static WitSource directory(String path, List<WitSourceFile> files, List<List<WitSourceFile>> deps) {
		return new WitSource(path, null, List.copyOf(files), List.copyOf(deps));
	}

	/**
	 * Returns the path the source was read from.
	 * @return the file's or the directory's path
	 */
	public String path() {
		return this.path;
	}

	/**
	 * Returns this source under another path, for a front end whose messages name the
	 * path as the program wrote it rather than as it was resolved. A directory's files
	 * keep their own paths.
	 * @param shown the path a message names
	 * @return the source
	 */
	public WitSource shownAs(String shown) {
		return new WitSource(shown, this.text, this.files, this.deps);
	}

	/**
	 * Returns whether the source is a package directory.
	 * @return {@code true} for a directory, {@code false} for one file
	 */
	public boolean isDirectory() {
		return this.text == null;
	}

	/**
	 * Parses the source: one file as it is, a directory's files each on its own and
	 * assembled into one document whose positions stay per file.
	 * @return the document and its positions
	 * @throws UnsupportedOperationException naming the file (and line) that does not
	 * parse
	 */
	WitParseResult parse() {
		try {
			return this.text != null ? WitParser.parseLocated(this.text)
					: WitPackageAssembler.assemble(this.files, this.deps);
		}
		catch (WitParseException ex) {
			// a directory's message names its file already
			throw new UnsupportedOperationException(
					this.text != null ? this.path + ": " + ex.getMessage() : ex.getMessage(), ex);
		}
	}

	/**
	 * The WIT text a lowering carries for the backend to parse again: a file's own text,
	 * or the parsed directory printed as the one document it assembled to.
	 * @param document what {@link #parse} answered
	 * @return the text
	 */
	String text(WitDocument document) {
		return this.text != null ? this.text : WitPrinter.print(document);
	}

	/**
	 * Where an item was written, for a message: {@code path:line}, the path of the file
	 * the item is in -- a directory's own file, not the directory.
	 * @param path the source's path
	 * @param locations the positions {@link #parse} answered
	 * @param item the item
	 * @return the file and line
	 */
	static String at(String path, WitLocations locations, WitItem item) {
		String file = locations.fileOf(item);
		return (file == null ? path : file) + ":" + locations.lineOf(item);
	}

}
