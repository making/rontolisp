// GlyphGen.java -- offline glyph generator for the hiragana demo.
//
// Renders the 46 gojuon hiragana from a single real font and downsamples each
// to a GRID x GRID binary bitmap using the SAME crop/center/binarize pipeline
// the browser applies to a drawn stroke (index.html's toBitmap), so a template
// equals what the browser produces for a perfectly drawn glyph.
//
// It writes three artifacts (single source -> no drift):
//   <out>/prototypes.lisp        the trainer's reference glyphs (regenerated)
//   <out>/glyphs.js              GLYPHS/KANA/ORDER for index.html
//   <out>/samples/<romaji>.txt   each template flattened to 576 floats
//
// Run it only when changing the font / resolution / class set:
//   java examples/hiragana/glyphgen/GlyphGen.java examples/hiragana
// (or via examples/hiragana/regen-glyphs.sh). JDK 25 single-file launch; this
// file lives outside the Maven source root, so it is not built or formatted by
// the main project.

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GlyphGen {

	static final int GRID = 24; // output bitmap is GRID x GRID

	// Each kana is rendered from SEVERAL fonts so the network sees stroke-shape
	// variation (hooks/sweeps, brush vs round), not one exemplar -- this is what
	// lets it tolerate real handwriting.  FONTS[0] is the "display" font: the
	// reference shown on the page (glyphs.js) and the parity samples come from it,
	// so "draw to match the reference" still works; the rest only widen training.
	static final String[] FONTS = { "Hiragino Maru Gothic ProN", // round gothic (the displayed reference)
			"Klee", // textbook / pen style -- proper hane (hooks) like handwriting
			"YuGothic", // gothic with a hooked left radical
			"Hiragino Mincho ProN" // brush / serif shapes
	};
	static final int HIRES = 320; // hi-res render canvas (px)
	static final int FONT_PX = 220; // glyph point size on that canvas
	static final double INK_BBOX = 0.3; // ink threshold for the bounding box
	static final double BINARIZE = 0.35; // cell on/off threshold (matches browser)

	// The 46 gojuon, in output-unit order. {kana, romaji}. romaji is the ASCII
	// label (kept multibyte-free across the WASM boundary) and the Lisp var name.
	static final String[][] KANA = { { "あ", "a" }, { "い", "i" }, { "う", "u" }, { "え", "e" }, { "お", "o" },
			{ "か", "ka" }, { "き", "ki" }, { "く", "ku" }, { "け", "ke" }, { "こ", "ko" }, { "さ", "sa" }, { "し", "shi" },
			{ "す", "su" }, { "せ", "se" }, { "そ", "so" }, { "た", "ta" }, { "ち", "chi" }, { "つ", "tsu" }, { "て", "te" },
			{ "と", "to" }, { "な", "na" }, { "に", "ni" }, { "ぬ", "nu" }, { "ね", "ne" }, { "の", "no" }, { "は", "ha" },
			{ "ひ", "hi" }, { "ふ", "fu" }, { "へ", "he" }, { "ほ", "ho" }, { "ま", "ma" }, { "み", "mi" }, { "む", "mu" },
			{ "め", "me" }, { "も", "mo" }, { "や", "ya" }, { "ゆ", "yu" }, { "よ", "yo" }, { "ら", "ra" }, { "り", "ri" },
			{ "る", "ru" }, { "れ", "re" }, { "ろ", "ro" }, { "わ", "wa" }, { "を", "wo" }, { "ん", "n" } };

	// Display-only romaji -> kana entries added to the glyphs.js KANA map but NOT
	// to the trained class set (no GLYPHS thumbnail, no prototype, no sample).
	// They cover the three classes that the real-data Kuzushiji-49 build has
	// beyond the 46 synthetic gojuon -- ゐ (wi), ゑ (we) and the iteration mark ゝ
	// (label "iter") -- so a 49-class infer.wasm (gen.sh --weights-from) can map
	// every predicted romaji back to a kana in the browser. The default 46-class
	// model never emits these labels, so the extra entries are harmless to it.
	static final String[][] KANA_DISPLAY_EXTRA = { { "ゐ", "wi" }, { "ゑ", "we" }, { "ゝ", "iter" } };

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args.length > 0 ? args[0] : ".");
		Path samples = out.resolve("samples");
		Files.createDirectories(samples);

		// bitmaps[class][font] = one GRID x GRID bitmap (GRID rows of GRID chars).
		String[][][] bitmaps = new String[KANA.length][FONTS.length][];
		for (int c = 0; c < KANA.length; c++) {
			for (int f = 0; f < FONTS.length; f++) {
				bitmaps[c][f] = render(KANA[c][0], FONTS[f]);
			}
		}

		writePrototypes(out.resolve("prototypes.lisp"), bitmaps);
		// The page reference and the parity samples use the display font only.
		writeGlyphsJs(out.resolve("glyphs.js"), bitmaps);
		for (int c = 0; c < KANA.length; c++) {
			Files.writeString(samples.resolve(KANA[c][1] + ".txt"), flattenSample(bitmaps[c][0]));
		}
		System.out.println("generated " + KANA.length + " kana x " + FONTS.length + " fonts at " + GRID + "x" + GRID
				+ " into " + out);
	}

	// Render one kana from one font and downsample to a GRID x GRID '#'/'.'
	// bitmap, mirroring index.html's toBitmap: crop the ink bbox, scale it to fit
	// a (GRID-2) box centred with a 1px margin, accumulate ink per cell, binarize.
	static String[] render(String kana, String font) {
		BufferedImage img = new BufferedImage(HIRES, HIRES, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, HIRES, HIRES);
		g.setColor(Color.BLACK);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(font, Font.PLAIN, FONT_PX));
		var fm = g.getFontMetrics();
		int tw = fm.stringWidth(kana);
		int x = (HIRES - tw) / 2;
		int y = (HIRES - fm.getHeight()) / 2 + fm.getAscent();
		g.drawString(kana, x, y);
		g.dispose();

		// ink(px) = 1 - luminance/255 (black stroke -> 1).
		double[] ink = new double[HIRES * HIRES];
		int minx = HIRES, miny = HIRES, maxx = -1, maxy = -1;
		for (int yy = 0; yy < HIRES; yy++) {
			for (int xx = 0; xx < HIRES; xx++) {
				int rgb = img.getRGB(xx, yy);
				int lum = (rgb >> 16) & 0xff; // grayscale -> any channel
				double v = 1.0 - lum / 255.0;
				ink[yy * HIRES + xx] = v;
				if (v > INK_BBOX) {
					if (xx < minx)
						minx = xx;
					if (xx > maxx)
						maxx = xx;
					if (yy < miny)
						miny = yy;
					if (yy > maxy)
						maxy = yy;
				}
			}
		}

		double[] acc = new double[GRID * GRID];
		int[] cnt = new int[GRID * GRID];
		if (maxx >= 0) {
			int bw = maxx - minx + 1, bh = maxy - miny + 1;
			double fit = GRID - 2, scale = fit / Math.max(bw, bh);
			double ox = (GRID - bw * scale) / 2, oy = (GRID - bh * scale) / 2;
			for (int yy = miny; yy <= maxy; yy++) {
				for (int xx = minx; xx <= maxx; xx++) {
					int gx = (int) Math.floor((xx - minx) * scale + ox);
					int gy = (int) Math.floor((yy - miny) * scale + oy);
					if (gx < 0 || gx >= GRID || gy < 0 || gy >= GRID)
						continue;
					acc[gy * GRID + gx] += ink[yy * HIRES + xx];
					cnt[gy * GRID + gx] += 1;
				}
			}
		}

		String[] rows = new String[GRID];
		for (int r = 0; r < GRID; r++) {
			StringBuilder sb = new StringBuilder(GRID);
			for (int cc = 0; cc < GRID; cc++) {
				int i = r * GRID + cc;
				boolean on = cnt[i] > 0 && acc[i] / cnt[i] > BINARIZE;
				sb.append(on ? '#' : '.');
			}
			rows[r] = sb.toString();
		}
		return rows;
	}

	static void writePrototypes(Path file, String[][][] bitmaps) throws IOException {
		StringBuilder b = new StringBuilder();
		b.append(";;;; prototypes.lisp -- GENERATED by glyphgen/GlyphGen.java -- DO NOT EDIT BY HAND.\n");
		b.append(";;;;\n");
		b.append(";;;; The reference glyphs (the \"training alphabet\").  Each class has one glyph\n");
		b.append(";;;; per font (").append(FONTS.length).append(" fonts) so the trainer sees stroke-shape variation.\n");
		b.append(";;;; Each glyph is ").append(GRID).append(" rows of ").append(GRID)
				.append(" characters, '#' = ink, '.' = blank, rendered and\n");
		b.append(";;;; binarized with the same crop/center/binarize the browser applies to a drawn\n");
		b.append(";;;; stroke.  Fonts: ");
		for (int f = 0; f < FONTS.length; f++)
			b.append(f == 0 ? "" : ", ").append(FONTS[f]);
		b.append(".\n");
		b.append(";;;; Used only by the OFFLINE trainer (interpreter/JVM); index.html's glyphs.js\n");
		b.append(";;;; (display font = the first one) is generated from the same run.  Class order\n");
		b.append(";;;; defines the output-unit order and must match *romaji* / *labels*.\n\n");

		b.append("(defparameter *romaji* (list");
		for (String[] k : KANA)
			b.append(" \"").append(k[1]).append("\"");
		b.append("))\n\n");

		// One defun per (class, font) returning its glyph rows.  These are defuns,
		// not defparameters, on purpose: the JVM trainer compiles each defun body
		// into its own method, whereas top-level defparameter literals all land in
		// one `main` method -- 184 glyphs of literals there blow the 64 KB method
		// cap.  (Same reason the weights are chunked into gN functions.)
		for (int c = 0; c < KANA.length; c++) {
			b.append(";; ").append(KANA[c][0]).append("\n");
			for (int f = 0; f < FONTS.length; f++) {
				b.append("(defun glyph-").append(KANA[c][1]).append("-f").append(f).append(" () (list\n");
				for (int r = 0; r < GRID; r++) {
					b.append("  \"").append(bitmaps[c][f][r]).append("\"");
					b.append(r == GRID - 1 ? "))\n" : "\n");
				}
			}
			b.append("\n");
		}

		// *glyphs* groups the font variants per class: a list (one entry per class)
		// of lists (the per-font glyphs).  build-dataset trains on every variant.
		b.append("(defparameter *glyphs* (list\n");
		for (int c = 0; c < KANA.length; c++) {
			b.append("  (list");
			for (int f = 0; f < FONTS.length; f++)
				b.append(" (glyph-").append(KANA[c][1]).append("-f").append(f).append(")");
			b.append(")").append(c == KANA.length - 1 ? "))\n\n" : "\n");
		}

		b.append(";; Convert one glyph (list of equal-length rows) into a flat list of 0.0 / 1.0,\n");
		b.append(";; row-major.  Size-agnostic: it reads the grid width off the row strings.\n");
		b.append("(defun glyph->list (rows)\n");
		b.append("  (let ((acc nil))\n");
		b.append("    (dolist (row rows)\n");
		b.append("      (dotimes (j (length row))\n");
		b.append("        (setq acc (cons (if (char= (char row j) #\\#) 1.0 0.0) acc))))\n");
		b.append("    (reverse acc)))\n");
		Files.writeString(file, b.toString());
	}

	static void writeGlyphsJs(Path file, String[][][] bitmaps) throws IOException {
		StringBuilder b = new StringBuilder();
		b.append("// glyphs.js -- GENERATED by glyphgen/GlyphGen.java -- DO NOT EDIT BY HAND.\n");
		b.append("// The reference glyphs shown on the page (display font = FONTS[0]); the network\n");
		b.append("// is additionally trained on other fonts (prototypes.lisp), same run.\n\n");
		b.append("export const GRID = ").append(GRID).append(";\n\n");

		b.append("export const GLYPHS = {\n");
		for (int c = 0; c < KANA.length; c++) {
			b.append("  ").append(KANA[c][1]).append(": [");
			for (int r = 0; r < GRID; r++) {
				b.append("\"").append(bitmaps[c][0][r]).append("\"");
				b.append(r == GRID - 1 ? "" : ", ");
			}
			b.append("],\n");
		}
		b.append("};\n\n");

		b.append("export const KANA = {");
		for (int c = 0; c < KANA.length; c++) {
			b.append(" ").append(KANA[c][1]).append(": \"").append(KANA[c][0]).append("\",");
		}
		// Display-only extras for the 49-class real-data model (no thumbnail).
		for (String[] e : KANA_DISPLAY_EXTRA) {
			b.append(" ").append(e[1]).append(": \"").append(e[0]).append("\",");
		}
		b.append(" };\n\n");

		b.append("export const ORDER = [");
		for (int c = 0; c < KANA.length; c++) {
			b.append("\"").append(KANA[c][1]).append("\"");
			b.append(c == KANA.length - 1 ? "" : ", ");
		}
		b.append("];\n");
		Files.writeString(file, b.toString());
	}

	// "(0.0 1.0 ...)\n" -- GRID*GRID floats, row-major, matching infer's (read).
	static String flattenSample(String[] rows) {
		StringBuilder b = new StringBuilder("(");
		for (int r = 0; r < GRID; r++) {
			for (int cc = 0; cc < GRID; cc++) {
				if (r != 0 || cc != 0)
					b.append(' ');
				b.append(rows[r].charAt(cc) == '#' ? "1.0" : "0.0");
			}
		}
		b.append(")\n");
		return b.toString();
	}

}
