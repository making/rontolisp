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
 * A group of per-operator reference pages (functions, macros, or special forms) for one
 * language, loaded from a {@code _catalog.yaml}. It groups the individual pages into
 * categories and defines their order, which drives the previous/next links between the
 * detail pages. The same mechanism powers the built-in functions, macros, and special
 * forms references: each owns a directory of detail pages plus a table page
 * ({@code index_page}) whose names are linked to those detail pages.
 *
 * <p>
 * Expected shape (e.g. {@code reference/functions/_catalog.yaml}):
 *
 * <pre>
 * index_page: reference/functions.md
 * categories:
 *   - title: Arithmetic
 *     index_page: reference/functions/cl.md   # optional; falls back to the file's index_page
 *     functions:
 *       - { slug: plus, name: "+" }
 * </pre>
 *
 * A category's own {@code index_page} lets several table pages share one catalog (and one
 * directory of detail pages): each category routes its entries' "back" link and table
 * auto-linking to its own page instead of the whole file's single default, which is how
 * one {@code reference/functions/_catalog.yaml} backs the per-package function pages
 * ({@code reference/functions/cl.md}, {@code reference/functions/uiop.md}, ...) without
 * moving a single detail page.
 *
 * @param baseDir the directory holding the detail pages and {@code _catalog.yaml},
 * relative to the language directory (e.g. {@code reference/functions})
 * @param indexPage the file's default table page, relative to the language directory
 * (e.g. {@code reference/functions.md}); a category without its own {@code index_page}
 * uses this one
 * @param categories the ordered groups of entries
 */
public record Catalog(String baseDir, String indexPage, List<Category> categories) {

	/**
	 * A titled group of detail pages.
	 *
	 * @param indexPage the table page this category's names link to and its entries'
	 * detail pages link back to
	 */
	public record Category(String title, List<Entry> functions, String indexPage) {
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

	/**
	 * The relative href prefix from {@code indexPage} to this catalog's detail pages
	 * (e.g. {@code functions/} from {@code reference/functions.md} to
	 * {@code reference/functions/plus.md}, or {@code ""} from
	 * {@code reference/functions/cl.md}, which already lives in {@link #baseDir}).
	 */
	public String linkPrefix(String indexPage) {
		int slash = indexPage.lastIndexOf('/');
		String indexDir = slash < 0 ? "" : indexPage.substring(0, slash);
		if (indexDir.equals(this.baseDir)) {
			return "";
		}
		String suffix = indexDir.isEmpty() ? this.baseDir : this.baseDir.substring(indexDir.length() + 1);
		return suffix + "/";
	}

	/** Returns every entry in catalog order (drives previous/next links). */
	public List<Entry> flatEntries() {
		List<Entry> all = new ArrayList<>();
		for (Category category : this.categories) {
			all.addAll(category.functions());
		}
		return all;
	}

	/** One entry paired with the index page its category routes it to. */
	public record EntryRef(Entry entry, String indexPage) {
	}

	/** Returns every entry in catalog order, paired with its category's index page. */
	public List<EntryRef> flatEntryRefs() {
		List<EntryRef> all = new ArrayList<>();
		for (Category category : this.categories) {
			for (Entry entry : category.functions()) {
				all.add(new EntryRef(entry, category.indexPage()));
			}
		}
		return all;
	}

	/**
	 * The distinct index pages used by this catalog's categories, in first-seen order.
	 */
	public List<String> indexPages() {
		List<String> pages = new ArrayList<>();
		for (Category category : this.categories) {
			if (!pages.contains(category.indexPage())) {
				pages.add(category.indexPage());
			}
		}
		return pages;
	}

	/**
	 * Loads every catalog under a language directory, in path order. Both output modes
	 * need the same set: the site links each table page to its detail pages, and the
	 * skill bundle turns the same entries into its operator index.
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
					String categoryIndexPage = categoryMap.containsKey("index_page")
							? String.valueOf(categoryMap.get("index_page")) : indexPage;
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
					categories.add(new Category(title, entries, categoryIndexPage));
				}
			}
			return new Catalog(baseDir, indexPage, categories);
		}
	}

}
