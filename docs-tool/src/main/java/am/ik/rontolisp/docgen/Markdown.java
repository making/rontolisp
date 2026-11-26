package am.ik.rontolisp.docgen;

import java.util.List;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * The Markdown dialect of this documentation, shared by every HTML the tool emits -- the
 * site pages ({@link DocGen}) and the skill's install page ({@link SkillGen}) -- so that
 * a page reads the same wherever it is rendered.
 */
final class Markdown {

	private Markdown() {
	}

	static MutableDataSet options() {
		MutableDataSet options = new MutableDataSet();
		options.set(Parser.EXTENSIONS,
				List.of(TablesExtension.create(), AutolinkExtension.create(), StrikethroughExtension.create()));
		// Heading ids (for intra-page #anchor links) without wrapping the heading
		// text in an anchor element.
		options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
		options.set(HtmlRenderer.RENDER_HEADER_ID, true);
		options.set(TablesExtension.COLUMN_SPANS, false);
		options.set(TablesExtension.APPEND_MISSING_COLUMNS, true);
		options.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true);
		return options;
	}

}
