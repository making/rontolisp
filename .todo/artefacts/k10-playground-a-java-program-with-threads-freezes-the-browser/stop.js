// Scenario: a cell that never ends leaves the page alive; Stop ends it; the next Run works.
module.exports = async (page) => {
  await page.goto(process.env.PAGE);
  const log = [];
  const cellExpr = `document.querySelectorAll(".code-cell")[0]`;
  const original = await page.eval(`${cellExpr}.querySelector(".cell-src").value`);
  await page.eval(`(() => { const c = ${cellExpr}; c.querySelector(".cell-src").value = "void main() { while (true) { } }"; c.querySelector(".run").click(); return 1; })()`);
  await new Promise((r) => setTimeout(r, 5000));
  let t = Date.now();
  log.push("after 5 s: button=" + await page.eval(`${cellExpr}.querySelector(".run").textContent`, 3000) + " status=" + await page.eval(`document.querySelector(".runtime-status").textContent`, 3000) + " (page answered in " + (Date.now() - t) + " ms)");
  await page.eval(`(() => { ${cellExpr}.querySelector(".run").click(); return 1; })()`);
  log.push("after Stop: button=" + await page.eval(`${cellExpr}.querySelector(".run").textContent`) + " cell-status=" + await page.eval(`${cellExpr}.querySelector(".cell-status").textContent`));
  t = Date.now();
  await page.eval(`(() => { const c = ${cellExpr}; c.querySelector(".cell-src").value = ${JSON.stringify(original)}; c.querySelector(".run").click(); return 1; })()`);
  const text = await page.waitFor(`(() => { const out = ${cellExpr}.querySelector(".cell-out"); return out.hidden ? null : out.textContent; })()`, 30000);
  log.push("next Run answered in " + (Date.now() - t) + " ms: " + JSON.stringify(text.slice(0, 80)));
  // a lisp page: definitions are shared by the page's cells, and Reset runtime forgets them
  return log;
};
