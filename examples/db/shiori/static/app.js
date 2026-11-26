// The page's own script, served from /static/app.js so the Content-Security-
// Policy can forbid inline script. For the same reason nothing here, nor any
// template, uses hx-on or another htmx attribute that htmx evaluates as code.

// A slip of paper for every HX-Trigger {"toast": "..."}, and for the flash
// message a full page carries. Messages arrive URL-encoded: an HTTP
// header is Latin-1.
function shioriToast(message) {
  const box = document.getElementById("toasts");
  if (!box) return;
  const slip = document.createElement("p");
  slip.className = "toast";
  slip.textContent = message;
  box.appendChild(slip);
  setTimeout(() => slip.classList.add("leaving"), 3000);
  setTimeout(() => slip.remove(), 3600);
}
document.addEventListener("toast", (e) => shioriToast(decodeURIComponent(e.detail.value)));

// A request that got no usable answer -- the network gone, the server down,
// no reply within htmx's timeout, or an error that is not one of this
// site's pages (a proxy's 502) -- changes nothing on the page. The dialog
// says so and offers to send the request again.
let shioriFailed = null;
function shioriRequestFailed(ctx, message, retry) {
  const dialog = document.getElementById("request-error");
  if (!dialog) return;
  shioriFailed = retry;
  dialog.querySelector(".alert-message").textContent = message;
  // A failed post leaves its form as it was: say so, so nobody retypes it.
  const form = ctx.sourceElement?.matches?.("form") ? ctx.sourceElement : null;
  dialog.querySelector(".alert-kept").hidden = !(form && ctx.request.method !== "GET" &&
    form.querySelector("textarea, input:not([type=hidden], [type=radio], [type=checkbox])"));
  if (!dialog.open) dialog.showModal();
}
function shioriResend(ctx) {
  return () => {
    const source = ctx.sourceElement;
    const submitter = ctx.sourceEvent?.submitter;
    if (source?.isConnected && source.matches("form")) {
      source.requestSubmit(submitter?.form === source ? submitter : null);
    } else if (source?.isConnected && source.matches("a[href]")) {
      source.click();
    } else if (ctx.request.method === "GET") {
      location.assign(ctx.request.action);   // HX-Location, history
    } else {
      location.reload();
    }
  };
}
document.addEventListener("htmx:error", (e) => {
  const ctx = e.detail.ctx;
  if (!ctx) return;   // not a request: a script error, logged by htmx
  if (ctx.response) {
    // The answer arrived but could not be shown.
    shioriRequestFailed(ctx, "応答を表示できませんでした。ページを読み込み直してください。",
                        () => location.reload());
  } else if (!navigator.onLine) {
    shioriRequestFailed(ctx, "ネットワークに接続されていません。接続を確かめてから、もう一度試してください。",
                        shioriResend(ctx));
  } else if (ctx.request.signal?.reason === "timeout") {
    shioriRequestFailed(ctx, "サーバーから応答がありませんでした。しばらく待ってから、もう一度試してください。",
                        shioriResend(ctx));
  } else {
    shioriRequestFailed(ctx, "サーバーに接続できませんでした。しばらく待ってから、もう一度試してください。",
                        shioriResend(ctx));
  }
});
document.addEventListener("htmx:before:swap", (e) => {
  const response = e.detail.ctx.response;
  if (response && response.status >= 400 &&
      !(response.headers.get("content-type") || "").startsWith("text/html")) {
    e.preventDefault();
    shioriRequestFailed(e.detail.ctx,
                        `サーバーがエラーを返しました（${response.status}）。しばらく待ってから、もう一度試してください。`,
                        shioriResend(e.detail.ctx));
  }
});
// close does not bubble: listen in the capture phase.
document.addEventListener("close", (e) => {
  if (e.target.id !== "request-error") return;
  const retry = shioriFailed;
  shioriFailed = null;
  if (e.target.returnValue === "retry" && retry) retry();
}, true);

// The book form's spine preview: the cloth, height and width the server
// would give the book (spine-style in view.lisp), out of the numbers the
// form carries in data-spine.
function shioriSpine(title, pages, spec) {
  let hash = 0;
  for (const ch of title) hash = (hash * 31 + ch.codePointAt(0)) % 1000003;
  const [h, s, l] = spec.cloths[hash % spec.cloths.length];
  const height = spec.heights[Math.floor(hash / spec.cloths.length) % spec.heights.length];
  const [per, narrowest, widest, unknown] = spec.width;
  const width = pages > 0 ? Math.max(narrowest, Math.min(widest, Math.round(pages / per))) : unknown;
  return `--h: ${h}; --s: ${s}%; --l: ${l}%; --w: ${width}px; --sh: ${height}px`;
}
function shioriInit(root) {
  root.querySelectorAll("[data-flash]").forEach((el) => {
    shioriToast(el.dataset.flash);
    el.remove();
  });
  const form = root.querySelector("[data-spine]");
  if (!form) return;
  const spec = JSON.parse(form.dataset.spine);
  const spine = form.querySelector(".spine");
  const update = () => {
    const f = form.elements;
    spine.style.cssText = shioriSpine(f.title.value, parseInt(f.pages.value, 10), spec);
    spine.querySelector(".spine-title").textContent = f.title.value || "書名";
    spine.querySelector(".spine-author").textContent = f.author.value || "著者";
  };
  form.addEventListener("input", update);
  update();
}
// htmx calls this for the page it starts on and for everything it swaps
// in afterwards. Without htmx (the CDN unreachable) the page still shows
// its flash message.
document.addEventListener("DOMContentLoaded", () => {
  if (window.htmx) htmx.onLoad(shioriInit);
  else shioriInit(document);
});

// A form marked data-reset-on-success empties itself once its post is taken.
document.addEventListener("htmx:after:request", (e) => {
  const form = e.target;
  if (form.matches?.("form[data-reset-on-success]") && e.detail.ctx?.response?.status < 400) {
    form.reset();
  }
});
