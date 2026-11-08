// Inline enhancements for the jolt-metrics HTML report. Every table is already
// complete in the markup; this only sorts, filters, collapses and downloads.
(function () {
  "use strict";

  function cellKey(row, index) {
    var cell = row.cells[index];
    if (!cell) return "";
    var v = cell.getAttribute("data-value");
    return v === null ? cell.textContent.trim().toLowerCase() : parseFloat(v);
  }

  function compare(a, b) {
    if (typeof a === "number" && typeof b === "number") return a - b;
    return String(a).localeCompare(String(b));
  }

  function sortBy(table, th, index) {
    var body = table.tBodies[0];
    if (!body) return;
    var dir = th.getAttribute("data-dir") === "asc" ? "desc" : "asc";
    var sign = dir === "asc" ? 1 : -1;
    Array.prototype.forEach.call(table.tHead.rows[0].cells, function (h) {
      h.removeAttribute("data-dir");
    });
    th.setAttribute("data-dir", dir);
    var rows = Array.prototype.slice.call(body.rows);
    rows.sort(function (r1, r2) { return sign * compare(cellKey(r1, index), cellKey(r2, index)); });
    rows.forEach(function (r) { body.appendChild(r); });
  }

  function makeSortable(table) {
    if (!table.tHead) return;
    Array.prototype.forEach.call(table.tHead.rows[0].cells, function (th, index) {
      th.title = "Sort";
      th.addEventListener("click", function () { sortBy(table, th, index); });
    });
  }

  function filterFiles(input) {
    var table = document.getElementById("files-table");
    if (!table || !table.tBodies[0]) return;
    var needle = input.value.trim().toLowerCase();
    Array.prototype.forEach.call(table.tBodies[0].rows, function (row) {
      var path = row.cells[0] ? row.cells[0].textContent.toLowerCase() : "";
      row.style.display = path.indexOf(needle) === -1 ? "none" : "";
    });
  }

  function filterSeverity() {
    var shown = {};
    document.querySelectorAll("input.severity-filter").forEach(function (box) {
      shown[box.value] = box.checked;
    });
    document.querySelectorAll("li[data-severity]").forEach(function (li) {
      li.style.display = shown[li.getAttribute("data-severity")] === false ? "none" : "";
    });
  }

  function downloadJson() {
    var data = document.getElementById("metrics-data");
    if (!data) return;
    var blob = new Blob([data.textContent], { type: "application/json" });
    var link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "jolt-metrics.json";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(function () { URL.revokeObjectURL(link.href); }, 1000);
  }

  // Theme: auto (follow the system), light or dark.  The choice is a per-viewer
  // convenience, so storage failing (private windows, file:// rules) is fine.
  var THEMES = ["auto", "light", "dark"];
  var THEME_KEY = "jolt-metrics-theme";

  function readTheme() {
    try { return localStorage.getItem(THEME_KEY) || "auto"; } catch (e) { return "auto"; }
  }

  function applyTheme(theme, button) {
    if (theme === "auto") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", theme);
    if (button) button.textContent = "Theme: " + theme;
  }

  // The page's own copy; storage is only written through, so the button still
  // cycles when storage is blocked.
  var currentTheme = readTheme();

  function nextTheme(button) {
    currentTheme = THEMES[(THEMES.indexOf(currentTheme) + 1) % THEMES.length];
    try { localStorage.setItem(THEME_KEY, currentTheme); } catch (e) { /* not remembered */ }
    applyTheme(currentTheme, button);
  }

  applyTheme(currentTheme, null);

  function init() {
    var themeButton = document.getElementById("theme-toggle");
    if (themeButton) {
      applyTheme(currentTheme, themeButton);
      themeButton.addEventListener("click", function () { nextTheme(themeButton); });
    }
    document.querySelectorAll("table.sortable").forEach(makeSortable);
    document.querySelectorAll("section > h2").forEach(function (h2) {
      h2.addEventListener("click", function () { h2.parentNode.classList.toggle("collapsed"); });
    });
    var filter = document.getElementById("files-filter");
    if (filter) filter.addEventListener("input", function () { filterFiles(filter); });
    document.querySelectorAll("input.severity-filter").forEach(function (box) {
      box.addEventListener("change", filterSeverity);
    });
    var download = document.getElementById("download-json");
    if (download) download.addEventListener("click", downloadJson);
    document.documentElement.classList.add("js");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
