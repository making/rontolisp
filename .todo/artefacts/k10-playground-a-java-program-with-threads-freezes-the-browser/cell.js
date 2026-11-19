// Scenario: on PAGE, click Run on each cell whose source contains one of NEEDLES (";;"-separated); report each output.
module.exports = async (page) => {
  const needles = process.env.NEEDLES.split(";;");
  const limit = Number(process.env.LIMIT || 30000);
  await page.goto(process.env.PAGE);
  const results = [];
  for (const needle of needles) {
    const t = Date.now();
    let found;
    try { found = await page.eval(`(() => { const cells = [...document.querySelectorAll(".code-cell")]; const i = cells.findIndex((c) => c.querySelector(".cell-src") && c.querySelector(".cell-src").value.includes(${JSON.stringify(needle)})); if (i < 0) return -1; const out = cells[i].querySelector(".cell-out"); out.hidden = true; out.textContent = ""; cells[i].querySelector(".run").click(); return i; })()`, Math.min(limit, 15000)); } catch (e) { results.push({ needle, ms: Date.now() - t, result: e.message }); break; }
    if (found < 0) { results.push({ needle, result: "NO SUCH CELL" }); continue; }
    try {
      const text = await page.waitFor(`(() => { const out = document.querySelectorAll(".code-cell")[${found}].querySelector(".cell-out"); return out.hidden ? null : JSON.stringify({ text: out.textContent, err: out.classList.contains("err") }); })()`, limit);
      results.push({ needle, ms: Date.now() - t, ...JSON.parse(text) });
    } catch (e) {
      results.push({ needle, ms: Date.now() - t, result: e.message });
      break;
    }
  }
  if (process.env.CONSOLE) results.push({ console: page.console().slice(-8) });
  return results;
};
