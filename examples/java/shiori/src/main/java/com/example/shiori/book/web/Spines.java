// Shiori: how a book is drawn -- its spine on the shelf, and lying in the dashboard's pile.
package com.example.shiori.book.web;

import am.ik.json.JsonArray;
import am.ik.json.JsonNode;
import am.ik.json.JsonObject;
import com.example.shiori.web.Assets;

public final class Spines {

    private Spines() {
    }

    // The bookcloth a spine can be bound in, as hue, saturation and lightness: ai, enji,
    // fukamidori, yamabuki-cha, tetsukon, sabishu, ebicha, uguisu, rikyu-nezumi,
    // edo-murasaki, sumi, seiji-nezumi.
    private static final int[][] CLOTHS = { { 212, 48, 30 }, { 350, 52, 33 }, { 158, 38, 24 }, { 36, 62, 34 },
            { 226, 38, 23 }, { 12, 54, 37 }, { 355, 35, 26 }, { 70, 32, 31 }, { 100, 10, 34 }, { 280, 26, 33 },
            { 30, 6, 17 }, { 172, 24, 34 } };

    // The heights a spine stands at, in pixels: a book's format.
    private static final int[] HEIGHTS = { 188, 204, 224 };

    // Pages a pixel of spine width, the narrowest and widest spine, and the width of a
    // book whose page count is not known.
    private static final int[] WIDTH = { 12, 22, 72, 30 };

    // The same numbers for the book form's live preview (static/app.js), which computes
    // a spine as style does.
    static final String JSON = json();

    private static String json() {
        JsonArray cloths = new JsonArray();
        for (int[] cloth : CLOTHS) {
            cloths.add(numbers(cloth));
        }
        return new JsonObject().put("cloths", cloths)
            .put("heights", numbers(HEIGHTS))
            .put("width", numbers(WIDTH))
            .toString();
    }

    private static JsonNode numbers(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(new JsonNode(value));
        }
        return array.toNode();
    }

    private static int width(Integer pages) {
        return (pages == null) ? WIDTH[3]
                : Math.max(WIDTH[1], Math.min(WIDTH[2], Math.round(pages / (float) WIDTH[0])));
    }

    // The custom properties app.css builds a spine from. The cloth and the height follow
    // from the title alone, so a book keeps its look; the width from the page count.
    static String style(String title, Integer pages) {
        int hash = Assets.textHash(title);
        int[] cloth = CLOTHS[hash % CLOTHS.length];
        return "--h: " + cloth[0] + "; --s: " + cloth[1] + "%; --l: " + cloth[2] + "%; --w: " + width(pages)
                + "px; --sh: " + HEIGHTS[(hash / CLOTHS.length) % HEIGHTS.length] + "px";
    }

    // A finished book lying in the dashboard's monthly pile: its cloth, and a length that
    // grows with its width on the shelf.
    public static String pileStyle(String title, Integer pages) {
        int[] cloth = CLOTHS[Assets.textHash(title) % CLOTHS.length];
        return "--h: " + cloth[0] + "; --s: " + cloth[1] + "%; --l: " + cloth[2] + "%; --t: "
                + Math.max(9, Math.min(18, Math.round(width(pages) / 4f))) + "px";
    }

}
