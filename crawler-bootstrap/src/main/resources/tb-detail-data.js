function pickRes(ctx) {
  var home = ctx && ctx.loaderData && ctx.loaderData.home;
  return home && home.data && home.data.res;
}
function extractObject(text, start) {
  var depth = 0, inStr = false, esc = false;
  for (var i = start; i < text.length; i++) {
    var ch = text.charAt(i);
    if (inStr) {
      if (esc) { esc = false; } else if (ch === "\\") { esc = true; } else if (ch === "\"") { inStr = false; }
      continue;
    }
    if (ch === "\"") { inStr = true; }
    else if (ch === "{") { depth++; }
    else if (ch === "}") { depth--; if (depth === 0) { return text.substring(start, i + 1); } }
  }
  return null;
}
var res = pickRes(window.__ICE_APP_CONTEXT__);
if (!res) {
  var scripts = document.scripts;
  for (var i = 0; i < scripts.length && !res; i++) {
    var text = scripts[i].text || "";
    var idx = text.indexOf("{\"appData\"");
    if (idx < 0 || text.indexOf("\"loaderData\"") < 0) { continue; }
    var raw = extractObject(text, idx);
    if (!raw) { continue; }
    try { res = pickRes(JSON.parse(raw)); } catch (e) {}
  }
}
if (!res) { return null; }
try { return JSON.stringify(res); } catch (e) { return null; }
