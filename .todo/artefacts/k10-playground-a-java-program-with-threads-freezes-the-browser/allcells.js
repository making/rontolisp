// Scenario: run every cell of PAGE in order; report each cell's output head.
module.exports = async (page) => {
  await page.goto(process.env.PAGE);
  const n = await page.eval(`document.querySelectorAll(".code-cell").length`);
  const results = [];
  for (let i = 0; i < n; i++) {
    await page.eval(`(() => { const c = document.querySelectorAll(".code-cell")[${i}]; c.querySelector(".run").click(); return 1; })()`);
    const text = await page.waitFor(`(() => { const out = document.querySelectorAll(".code-cell")[${i}].querySelector(".cell-out"); return out.hidden ? null : JSON.stringify({ t: out.textContent.slice(0, 70), err: out.classList.contains("err") }); })()`, 40000);
    results.push(i + " " + text);
  }
  return results;
};
