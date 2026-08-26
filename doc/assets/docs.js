/*
 * Runnable Lisp cells and the search dialog for the documentation site.
 *
 * Each ".code-cell" is an editable textarea plus a Run button. The rontolisp
 * runtime (the same WebAssembly build that powers the playground,
 * rontoplayground.js) is loaded lazily on the first Run click anywhere on the
 * page and shared by every cell -- nothing heavy loads on page view. One
 * persistent evaluator is shared across the page's cells, so a definition in an
 * earlier cell is visible to later ones (use "Reset runtime" to start over).
 *
 * The search (Ctrl+K or /) reads the two index files docgen writes beside the
 * pages of this language tree. Tier 1 (search-index.json -- titles, operator
 * signatures, headings) is prefetched when the browser goes idle, so the
 * dominant "I know the name, take me there" query answers with no round trip;
 * tier 2 (search-body.json -- the section bodies) is fetched on the first
 * keystroke. Matching is plain substring on both, which needs no tokenizer and
 * therefore works the same in the Japanese tree as in the English one.
 */
(function () {
	"use strict";

	var runtimePromise = null;
	var statusEl = null;

	function setStatus(state, text) {
		if (!statusEl) return;
		statusEl.setAttribute("data-state", state);
		statusEl.textContent = text || "";
	}

	// Lazily inject rontoplayground.js and initialize the GraalVM runtime.
	// Concurrent first clicks all await the same promise.
	function ensureRuntime() {
		if (runtimePromise) return runtimePromise;
		var src = document.body.getAttribute("data-runtime-src");
		setStatus("loading", "loading runtime…");
		runtimePromise = new Promise(function (resolve, reject) {
			var script = document.createElement("script");
			script.src = src;
			script.onload = function () {
				if (typeof GraalVM === "undefined" || !GraalVM.run) {
					reject(new Error("runtime script loaded but GraalVM is unavailable"));
					return;
				}
				GraalVM.run([]).then(resolve, reject);
			};
			script.onerror = function () {
				reject(new Error("failed to load " + src));
			};
			document.head.appendChild(script);
		});
		runtimePromise.then(
			function () { setStatus("ready", "runtime ready"); },
			function (e) {
				setStatus("error", "runtime failed");
				console.error(e);
			}
		);
		return runtimePromise;
	}

	function showOutput(cell, text, isErr) {
		var out = cell.querySelector(".cell-out");
		out.hidden = false;
		out.textContent = text;
		out.classList.toggle("err", !!isErr);
	}

	function runCell(cell) {
		var button = cell.querySelector(".run");
		var cellStatus = cell.querySelector(".cell-status");
		var src = cell.querySelector(".cell-src").value;
		button.disabled = true;
		cellStatus.textContent = "";
		cellStatus.classList.remove("err");
		ensureRuntime().then(
			function () {
				var res;
				try {
					res = globalThis.rontoEval(src);
				} catch (e) {
					showOutput(cell, String(e), true);
					button.disabled = false;
					return;
				}
				var isErr = typeof res === "string" && res.indexOf("ERROR:") === 0;
				showOutput(cell, isErr ? res.slice(6).trim() : res, isErr);
				button.disabled = false;
			},
			function () {
				cellStatus.textContent = "runtime unavailable";
				cellStatus.classList.add("err");
				button.disabled = false;
			}
		);
	}

	// Copy the given text to the clipboard, with a graceful fallback for
	// browsers/contexts where the async Clipboard API is unavailable (e.g. a
	// page served over plain http or an older browser).
	function copyText(text, onDone) {
		if (navigator.clipboard && navigator.clipboard.writeText) {
			navigator.clipboard.writeText(text).then(onDone, function () {
				fallbackCopy(text, onDone);
			});
		} else {
			fallbackCopy(text, onDone);
		}
	}

	function fallbackCopy(text, onDone) {
		var ta = document.createElement("textarea");
		ta.value = text;
		ta.setAttribute("readonly", "");
		ta.style.position = "fixed";
		ta.style.left = "-9999px";
		document.body.appendChild(ta);
		ta.select();
		try {
			document.execCommand("copy");
			onDone();
		} catch (e) {
			/* clipboard unavailable; leave the button label unchanged */
		}
		document.body.removeChild(ta);
	}

	function makeCopyButton() {
		var b = document.createElement("button");
		b.type = "button";
		b.className = "copy";
		b.textContent = "Copy";
		b.setAttribute("aria-label", "Copy code to clipboard");
		return b;
	}

	// Flash a "Copied" confirmation on the button, then restore its label.
	function flashCopied(button) {
		button.textContent = "Copied";
		button.classList.add("copied");
		window.setTimeout(function () {
			button.textContent = "Copy";
			button.classList.remove("copied");
		}, 1200);
	}

	// Add a copy button to every code block: runnable cells copy the editable
	// source; static blocks (bash, console, transcripts) copy their text.
	function wireCopy() {
		Array.prototype.forEach.call(document.querySelectorAll(".code-cell"), function (cell) {
			var toolbar = cell.querySelector(".cell-toolbar");
			var src = cell.querySelector(".cell-src");
			if (!toolbar || !src) return;
			var button = makeCopyButton();
			button.addEventListener("click", function () {
				copyText(src.value, function () { flashCopied(button); });
			});
			toolbar.appendChild(button);
		});

		Array.prototype.forEach.call(document.querySelectorAll(".markdown pre"), function (pre) {
			// The runnable cells' output area is also a <pre>; skip it.
			if (pre.classList.contains("cell-out")) return;
			var wrap = document.createElement("div");
			wrap.className = "code-block";
			pre.parentNode.insertBefore(wrap, pre);
			wrap.appendChild(pre);
			var button = makeCopyButton();
			button.addEventListener("click", function () {
				var code = pre.querySelector("code");
				copyText(code ? code.textContent : pre.textContent, function () {
					flashCopied(button);
				});
			});
			wrap.appendChild(button);
		});
	}

	// Highlight the TOC entry of the section currently scrolled into view.
	function wireToc() {
		var toc = document.querySelector(".toc");
		if (!toc) return;
		var entries = [];
		Array.prototype.forEach.call(toc.querySelectorAll("a"), function (a) {
			var id = decodeURIComponent((a.getAttribute("href") || "").replace(/^#/, ""));
			var heading = id && document.getElementById(id);
			if (heading) entries.push({ link: a, heading: heading });
		});
		if (!entries.length) return;

		var ticking = false;
		function update() {
			ticking = false;
			var offset = 80; // a little below the sticky top bar
			var activeIdx = 0;
			for (var i = 0; i < entries.length; i++) {
				if (entries[i].heading.getBoundingClientRect().top <= offset) {
					activeIdx = i;
				} else {
					break;
				}
			}
			// Near the very bottom, prefer the last entry (it may never reach the top).
			if (window.innerHeight + window.scrollY >= document.body.scrollHeight - 2) {
				activeIdx = entries.length - 1;
			}
			entries.forEach(function (e, i) {
				e.link.classList.toggle("active", i === activeIdx);
			});
		}
		function onScroll() {
			if (!ticking) {
				ticking = true;
				window.requestAnimationFrame(update);
			}
		}
		window.addEventListener("scroll", onScroll, { passive: true });
		window.addEventListener("resize", onScroll, { passive: true });
		update();
	}

	// Hamburger toggle for the sidebar on narrow screens. The sidebar is
	// hidden by default via CSS transform; toggling body.nav-open slides it in.
	function wireNavToggle() {
		var toggle = document.querySelector(".nav-toggle");
		var backdrop = document.querySelector(".sidebar-backdrop");
		var sidebar = document.querySelector(".sidebar");
		if (!toggle || !sidebar) return;

		function setOpen(open) {
			document.body.classList.toggle("nav-open", open);
			toggle.setAttribute("aria-expanded", open ? "true" : "false");
			if (backdrop) backdrop.hidden = !open;
		}
		toggle.addEventListener("click", function () {
			setOpen(!document.body.classList.contains("nav-open"));
		});
		if (backdrop) {
			backdrop.addEventListener("click", function () { setOpen(false); });
		}
		// Close after navigating within the sidebar (single-page-feel on mobile).
		sidebar.addEventListener("click", function (ev) {
			var a = ev.target.closest && ev.target.closest("a");
			if (a) setOpen(false);
		});
		// If the viewport grows past the mobile breakpoint, drop the open state
		// so the sidebar returns to its normal docked layout cleanly.
		window.addEventListener("resize", function () {
			if (window.innerWidth > 800) setOpen(false);
		});
	}


	/* ------------------------------------------------------------------ *
	 * Search
	 * ------------------------------------------------------------------ */

	var MAX_HITS = 40;

	var search = {
		base: ".",
		tier1: null,
		tier1Promise: null,
		tier2: null,
		tier2Promise: null,
		overlay: null,
		input: null,
		list: null,
		foot: null,
		hits: [],
		active: 0,
		lastFocus: null
	};

	function fetchJson(url) {
		return fetch(url).then(function (res) {
			if (!res.ok) throw new Error(url + ": " + res.status);
			return res.json();
		});
	}

	// Tier 1 is small and is wanted before the first keystroke, so it is
	// prefetched on idle; tier 2 only when someone actually types.
	function loadTier1() {
		if (!search.tier1Promise) {
			search.tier1Promise = fetchJson(search.base + "/search-index.json").then(function (data) {
				search.tier1 = data;
				return data;
			});
			search.tier1Promise.catch(function (e) { console.error(e); });
		}
		return search.tier1Promise;
	}

	function loadTier2() {
		if (!search.tier2Promise) {
			search.tier2Promise = fetchJson(search.base + "/search-body.json").then(function (data) {
				search.tier2 = data;
				return data;
			});
			search.tier2Promise.catch(function (e) { console.error(e); });
		}
		return search.tier2Promise;
	}

	function escHtml(s) {
		return s.replace(/[&<>"]/g, function (c) {
			return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
		});
	}

	// Escape `text`, wrapping every occurrence of a query term in <mark>.
	function highlight(text, terms) {
		var lower = text.toLowerCase();
		var spans = [];
		terms.forEach(function (term) {
			if (!term) return;
			var at = lower.indexOf(term);
			while (at >= 0) {
				spans.push([at, at + term.length]);
				at = lower.indexOf(term, at + term.length);
			}
		});
		spans.sort(function (a, b) { return a[0] - b[0]; });
		var out = "";
		var pos = 0;
		spans.forEach(function (span) {
			if (span[0] < pos) return; // overlapping terms: keep the first
			out += escHtml(text.slice(pos, span[0])) + "<mark>" + escHtml(text.slice(span[0], span[1])) + "</mark>";
			pos = span[1];
		});
		return out + escHtml(text.slice(pos));
	}

	// A window of `text` around its first matching term.
	function snippet(text, terms) {
		var lower = text.toLowerCase();
		var at = -1;
		terms.forEach(function (term) {
			var i = term ? lower.indexOf(term) : -1;
			if (i >= 0 && (at < 0 || i < at)) at = i;
		});
		if (at < 0) at = 0;
		var start = Math.max(0, at - 60);
		var end = Math.min(text.length, at + 160);
		return (start > 0 ? "…" : "") + text.slice(start, end) + (end < text.length ? "…" : "");
	}

	function matchesAll(hay, terms) {
		for (var i = 0; i < terms.length; i++) {
			if (hay.indexOf(terms[i]) < 0) return false;
		}
		return true;
	}

	/*
	 * Rank, cheapest signal first: an operator's own name beats a page title,
	 * which beats a heading, which beats a body section. Within a rank the
	 * earlier offset wins, and a page whose name STARTS with the query beats one
	 * that merely contains it -- typing "car" should land on `car`, not on
	 * `char-code`.
	 */
	function rankTitle(title, term, operator) {
		var base = operator ? 0 : 3;
		if (title === term) return base;
		if (title.indexOf(term) === 0) return base + 1;
		if (title.indexOf(term) >= 0) return base + 2;
		return -1;
	}

	// Tier 1: page titles, operator signatures and headings.
	function searchTier1(terms, into) {
		if (!search.tier1) return;
		var term = terms[0];
		search.tier1.pages.forEach(function (page, index) {
			var title = page.t.toLowerCase();
			var signature = (page.s || "").toLowerCase();
			var headings = page.h || [];
			var hay = title + " " + signature + " " + page.p.toLowerCase();
			headings.forEach(function (h) { hay += " " + h[1].toLowerCase(); });
			if (!matchesAll(hay, terms)) return;

			var titleRank = rankTitle(title, term, !!page.o);
			if (titleRank >= 0) {
				add(into, {
					rank: titleRank,
					offset: title.indexOf(term),
					page: index,
					anchor: "",
					heading: "",
					text: page.s || ""
				});
			}
			else if (signature.indexOf(term) >= 0) {
				add(into, {
					rank: 6,
					offset: signature.indexOf(term),
					page: index,
					anchor: "",
					heading: "",
					text: page.s
				});
			}
			headings.forEach(function (h) {
				var anchor = anchorOf(page, h);
				if (!anchor) return; // the page's own title, already ranked above
				var label = h[1].toLowerCase();
				var at = label.indexOf(term);
				if (at < 0) return;
				add(into, {
					rank: at === 0 ? 7 : 8,
					offset: at,
					page: index,
					anchor: anchor,
					heading: h[1],
					text: ""
				});
			});
		});
	}

	// Tier 2: the section bodies, keyed back to tier 1's pages and headings.
	function searchTier2(terms, into) {
		if (!search.tier2) return;
		var term = terms[0];
		var sections = search.tier2.s;
		for (var i = 0; i < sections.length; i++) {
			var pageIndex = sections[i][0];
			var headingIndex = sections[i][1];
			var text = sections[i][2];
			var lower = text.toLowerCase();
			if (!matchesAll(lower, terms)) continue;
			var page = search.tier1.pages[pageIndex];
			if (!page) continue;
			var heading = headingIndex >= 0 && page.h ? page.h[headingIndex] : null;
			var anchor = heading ? anchorOf(page, heading) : "";
			add(into, {
				rank: 9,
				offset: lower.indexOf(term),
				page: pageIndex,
				anchor: anchor,
				heading: anchor ? heading[1] : "",
				text: text
			});
		}
	}

	// One hit per target: a heading found in both tiers is the same destination,
	// so keep the better rank but let the body tier contribute the snippet.
	function add(into, hit) {
		var key = hit.page + "#" + hit.anchor;
		var existing = into[key];
		if (!existing) {
			into[key] = hit;
			return;
		}
		if (!existing.text && hit.text) existing.text = hit.text;
		if (hit.rank < existing.rank) {
			hit.text = hit.text || existing.text;
			into[key] = hit;
		}
	}

	function runSearch(query) {
		var terms = query.toLowerCase().split(/\s+/).filter(function (t) { return t.length > 0; });
		if (!terms.length) return [];
		var into = {};
		searchTier1(terms, into);
		// A one-character query is too broad in English but meaningful in
		// Japanese, where one character is a word.
		if (query.length >= 2 || /[^\x00-\x7F]/.test(query)) searchTier2(terms, into);
		var hits = Object.keys(into).map(function (k) { return into[k]; });
		hits.sort(function (a, b) {
			return a.rank - b.rank || a.offset - b.offset || (a.page - b.page) || (a.anchor < b.anchor ? -1 : 1);
		});
		return hits.slice(0, MAX_HITS);
	}

	// The <h1> of a page repeats its title, so a hit on it IS a hit on the page:
	// give it the page's own anchor so the two fold into one result.
	function anchorOf(page, heading) {
		return heading[1] === page.t ? "" : heading[0];
	}

	function hitHref(hit) {
		var page = search.tier1.pages[hit.page];
		return search.base + "/" + page.p + (hit.anchor ? "#" + hit.anchor : "");
	}

	function renderHits(terms) {
		search.list.innerHTML = "";
		if (!search.hits.length) {
			var empty = document.createElement("div");
			empty.className = "search-empty";
			empty.textContent = search.input.value ? "No matches" : "Type to search this language's pages";
			search.list.appendChild(empty);
			return;
		}
		search.hits.forEach(function (hit, i) {
			var page = search.tier1.pages[hit.page];
			var a = document.createElement("a");
			a.className = "search-hit" + (i === search.active ? " active" : "");
			a.href = hitHref(hit);
			a.setAttribute("role", "option");
			a.setAttribute("aria-selected", i === search.active ? "true" : "false");
			var html = '<span class="hit-title">' + highlight(page.t, terms) + "</span>";
			if (hit.heading) {
				html += '<span class="hit-crumb">' + highlight(hit.heading, terms) + "</span>";
			}
			if (hit.text) {
				html += '<span class="hit-snippet">' + highlight(snippet(hit.text, terms), terms) + "</span>";
			}
			a.innerHTML = html;
			a.addEventListener("mousemove", function () { setActive(i, false); });
			search.list.appendChild(a);
		});
	}

	function setActive(i, scroll) {
		var items = search.list.querySelectorAll(".search-hit");
		if (!items.length) return;
		search.active = Math.max(0, Math.min(i, items.length - 1));
		Array.prototype.forEach.call(items, function (el, n) {
			el.classList.toggle("active", n === search.active);
			el.setAttribute("aria-selected", n === search.active ? "true" : "false");
		});
		if (scroll && items[search.active].scrollIntoView) {
			items[search.active].scrollIntoView({ block: "nearest" });
		}
	}

	function update() {
		var query = search.input.value.trim();
		var terms = query.toLowerCase().split(/\s+/).filter(function (t) { return t.length > 0; });
		if (!search.tier1) {
			search.list.innerHTML = '<div class="search-empty">Loading the index…</div>';
			loadTier1().then(update, function () {
				search.list.innerHTML = '<div class="search-empty">The search index could not be loaded.</div>';
			});
			return;
		}
		if (query && !search.tier2) {
			// Re-render once when the body tier lands, not once per keystroke
			// typed while it was in flight.
			var first = !search.tier2Promise;
			loadTier2();
			if (first) {
				search.tier2Promise.then(update, function () { setFoot("full-text index unavailable"); });
			}
			setFoot("loading full text…");
		}
		else {
			setFoot("");
		}
		search.active = 0;
		search.hits = query ? runSearch(query) : [];
		renderHits(terms);
	}

	function setFoot(text) {
		if (search.foot) search.foot.textContent = text;
	}

	function buildOverlay() {
		var overlay = document.createElement("div");
		overlay.className = "search-overlay";
		overlay.hidden = true;
		overlay.innerHTML =
			'<div class="search-panel" role="dialog" aria-modal="true" aria-label="Search the documentation">' +
			'<input class="search-input" type="text" autocomplete="off" autocorrect="off" spellcheck="false"' +
			' placeholder="Search this documentation">' +
			'<div class="search-results" role="listbox"></div>' +
			'<div class="search-legend"><span><kbd>↑</kbd><kbd>↓</kbd> move</span>' +
			'<span><kbd>Enter</kbd> open</span><span><kbd>Esc</kbd> close</span>' +
			'<span class="search-note"></span></div>' +
			"</div>";
		document.body.appendChild(overlay);
		search.overlay = overlay;
		search.input = overlay.querySelector(".search-input");
		search.list = overlay.querySelector(".search-results");
		search.foot = overlay.querySelector(".search-note");

		overlay.addEventListener("mousedown", function (ev) {
			if (ev.target === overlay) closeSearch();
		});
		search.input.addEventListener("input", update);
		search.input.addEventListener("keydown", function (ev) {
			if (ev.key === "ArrowDown") {
				ev.preventDefault();
				setActive(search.active + 1, true);
			} else if (ev.key === "ArrowUp") {
				ev.preventDefault();
				setActive(search.active - 1, true);
			} else if (ev.key === "Enter") {
				var items = search.list.querySelectorAll(".search-hit");
				if (items[search.active]) {
					ev.preventDefault();
					location.href = items[search.active].getAttribute("href");
					closeSearch();
				}
			} else if (ev.key === "Escape") {
				ev.preventDefault();
				closeSearch();
			}
		});
		return overlay;
	}

	function openSearch(seed) {
		if (!search.overlay) buildOverlay();
		if (!search.overlay.hidden) return;
		search.lastFocus = document.activeElement;
		search.overlay.hidden = false;
		document.body.classList.add("search-open-body");
		search.input.value = seed || "";
		search.input.focus();
		search.input.select();
		update();
	}

	function closeSearch() {
		if (!search.overlay || search.overlay.hidden) return;
		search.overlay.hidden = true;
		document.body.classList.remove("search-open-body");
		if (search.lastFocus && search.lastFocus.focus) search.lastFocus.focus();
	}

	// `/` opens the dialog only when nothing else is taking the keystroke -- a
	// runnable cell's textarea is an editor, and "/" is a Lisp symbol there.
	function isTyping(target) {
		if (!target) return false;
		var tag = target.tagName;
		return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
	}

	function wireSearch() {
		var base = document.body.getAttribute("data-search-base");
		if (base) search.base = base;
		var button = document.querySelector(".search-open");
		if (button) {
			button.addEventListener("click", function () { openSearch(""); });
			// A Mac keyboard has no Ctrl+K habit; show the modifier it does have.
			if (/Mac|iPhone|iPad/.test(navigator.platform || "")) {
				var key = button.querySelector(".search-open-key");
				if (key) key.textContent = "⌘K";
			}
		}
		document.addEventListener("keydown", function (ev) {
			if (!ev.key) return;
			if ((ev.ctrlKey || ev.metaKey) && !ev.altKey && ev.key.toLowerCase() === "k") {
				ev.preventDefault();
				openSearch("");
			} else if (ev.key === "/" && !ev.ctrlKey && !ev.metaKey && !ev.altKey && !isTyping(ev.target)) {
				ev.preventDefault();
				openSearch("");
			} else if (ev.key === "Escape") {
				closeSearch();
			}
		});
		// Warm tier 1 once the page is otherwise done, so the first keystroke
		// searches immediately.
		if (window.requestIdleCallback) {
			window.requestIdleCallback(function () { loadTier1(); });
		} else {
			window.setTimeout(loadTier1, 1500);
		}
	}

	function wire() {
		statusEl = document.querySelector(".runtime-status");
		wireSearch();
		wireNavToggle();
		wireToc();
		wireCopy();

		var cells = document.querySelectorAll(".code-cell");
		Array.prototype.forEach.call(cells, function (cell) {
			var button = cell.querySelector(".run");
			if (button) {
				button.addEventListener("click", function () { runCell(cell); });
			}
			// Ctrl/Cmd+Enter runs the focused cell.
			var src = cell.querySelector(".cell-src");
			if (src) {
				src.addEventListener("keydown", function (ev) {
					if ((ev.ctrlKey || ev.metaKey) && ev.key === "Enter") {
						ev.preventDefault();
						runCell(cell);
					}
				});
			}
		});

		var reset = document.querySelector(".reset-runtime");
		if (reset) {
			reset.addEventListener("click", function () {
				// The persistent evaluator lives inside the loaded runtime; the
				// simplest correct reset is a full reload.
				if (runtimePromise) {
					location.reload();
				}
			});
		}
	}

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", wire);
	} else {
		wire();
	}
})();
