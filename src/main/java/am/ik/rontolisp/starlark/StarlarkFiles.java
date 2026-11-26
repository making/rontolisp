package am.ik.rontolisp.starlark;

import org.jspecify.annotations.Nullable;

/**
 * The files a Starlark program names besides itself: the modules its {@code load}
 * statements read. This package has no filesystem of its own -- the browser playground
 * has none at all -- so the source-language seam hands one in, over the loader the rest
 * of the program reads through ({@code eval/SourceLanguage}).
 */
@FunctionalInterface
public interface StarlarkFiles {

	/** No file is readable: a program that loads one is told so, by name. */
	StarlarkFiles NONE = (from, path) -> null;

	/**
	 * Reads a file named relative to another.
	 * @param from the file whose directory {@code path} is relative to, or {@code null}
	 * for the working directory ({@code -e})
	 * @param path the path as the program spells it
	 * @return the file, or {@code null} when there is none to read
	 */
	@Nullable Source find(@Nullable String from, String path);

	/**
	 * A file that was found.
	 *
	 * @param path the resolved path: what the module is known by, for positions and for
	 * what the file loads in turn
	 * @param text its contents
	 */
	record Source(String path, String text) {
	}

}
