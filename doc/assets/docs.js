/*
 * Runnable Lisp cells for the documentation site.
 *
 * Each ".code-cell" is an editable textarea plus a Run button. The rontolisp
 * runtime (the same WebAssembly build that powers the playground,
 * rontoplayground.js) is loaded lazily on the first Run click anywhere on the
 * page and shared by every cell -- nothing heavy loads on page view. One
 * persistent evaluator is shared across the page's cells, so a definition in an
 * earlier cell is visible to later ones (use "Reset runtime" to start over).
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

	function wire() {
		statusEl = document.querySelector(".runtime-status");
		wireToc();

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
