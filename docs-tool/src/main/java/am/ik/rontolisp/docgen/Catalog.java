package am.ik.rontolisp.docgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * A group of per-operator reference pages (functions, macros, or special forms)
 * for one language, loaded from a {@code _catalog.yaml}. It groups the
 * individual pages into categories and defines their order, which drives the
 * previous/next links between the detail pages. The same mechanism powers the
 * built-in functions, macros, and special forms references: each owns a
 * directory of detail pages plus a table page ({@code index_page}) whose names
 * are linked to those detail pages.
 *
 * <p>
 * Expected shape (e.g. {@code reference/functions/_catalog.yaml}):
 *
 * <pre>
 * index_page: reference/functions.md
 * categories:
 *   - title: Arithmetic
 *     functions:
 *       - { slug: plus, name: "+" }
 * </pre>
 *
 * @param baseDir the directory holding the detail pages and {@code _catalog.yaml},
 * relative to the language directory (e.g. {@code reference/functions})
 * @param indexPage the table page whose names link to the detail pages, relative
 * to the language directory (e.g. {@code reference/functions.md})
 * @param categories the ordered groups of entries
 */
public record Catalog(String baseDir, String indexPage, List<Category> categories) {

	/** A titled group of detail pages. */
	public record Category(String title, List<Entry> functions) {
	}

	/**
	 * One detail page.
	 *
	 * @param slug the file stem under {@link #baseDir}
	 * @param name the displayed operator name (e.g. {@code +}, {@code do*})
	 */
	public record Entry(String slug, String name) {
	}

	/** The Markdown source path of an entry, relative to the language directory. */
	public String mdFile(Entry entry) {
		return this.baseDir + "/" + entry.slug() + ".md";
	}

	/** The relative href prefix from the index page to the detail pages (e.g. {@code functions/}). */
	public String linkPrefix() {
		int slash = this.baseDir.lastIndexOf('/');
		return (slash < 0 ? this.baseDir : this.baseDir.substring(slash + 1)) + "/";
	}

	/** Returns every entry in catalog order (drives previous/next links). */
	public List<Entry> flatEntries() {
		List<Entry> all = new ArrayList<>();
		for (Category category : this.categories) {
			all.addAll(category.functions());
		}
		return all;
	}

	/**
	 * Loads every catalog under a language directory, in path order. Both output
	 * modes need the same set: the site links each table page to its detail pages,
	 * and the skill bundle turns the same entries into its operator index.
	 */
	public static List<Catalog> discover(Path langDir) throws IOException {
		try (java.util.stream.Stream<Path> paths = Files.walk(langDir)) {
			List<Path> catalogFiles = paths.filter(p -> p.getFileName().toString().equals("_catalog.yaml"))
				.sorted()
				.toList();
			List<Catalog> catalogs = new ArrayList<>();
			for (Path file : catalogFiles) {
				catalogs.add(load(langDir, file));
			}
			return catalogs;
		}
	}

	/** Loads a catalog from its {@code _catalog.yaml}. */
	@SuppressWarnings("unchecked")
	public static Catalog load(Path langDir, Path catalogYaml) throws IOException {
		String baseDir = langDir.relativize(catalogYaml.getParent()).toString().replace('\\', '/');
		try (InputStream in = Files.newInputStream(catalogYaml)) {
			Map<String, Object> root = new Yaml().load(in);
			if (root == null) {
				return new Catalog(baseDir, "", List.of());
			}
			String indexPage = String.valueOf(root.getOrDefault("index_page", baseDir + ".md"));
			List<Category> categories = new ArrayList<>();
			Object rawCategories = root.get("categories");
			if (rawCategories instanceof List<?> categoryList) {
				for (Object rawCategory : categoryList) {
					Map<String, Object> categoryMap = (Map<String, Object>) rawCategory;
					String title = String.valueOf(categoryMap.getOrDefault("title", ""));
					List<Entry> entries = new ArrayList<>();
					Object rawFunctions = categoryMap.get("functions");
					if (rawFunctions instanceof List<?> functionList) {
						for (Object rawEntry : functionList) {
							Map<String, Object> entryMap = (Map<String, Object>) rawEntry;
							String slug = String.valueOf(entryMap.get("slug"));
							String name = String.valueOf(entryMap.getOrDefault("name", slug));
							entries.add(new Entry(slug, name));
						}
					}
					categories.add(new Category(title, entries));
				}
			}
			return new Catalog(baseDir, indexPage, categories);
		}
	}

}
