var spec = JSON.parse(arguments[0]);
function token() {
  var m = document.cookie.match(/(?:^|; )_tb_token_=([^;]*)/);
  return m ? m[1] : "";
}
function encode(obj) {
  var out = [];
  for (var k in obj) {
    if (!Object.prototype.hasOwnProperty.call(obj, k)) { continue; }
    var v = obj[k];
    if (v === null || v === undefined) { v = ""; }
    if (typeof v === "object") { v = JSON.stringify(v); }
    out.push(encodeURIComponent(k) + "=" + encodeURIComponent(String(v)));
  }
  return out.join("&");
}
var tk = token();
if (!tk) {
  return JSON.stringify({ok:false, status:0, error:"_tb_token_ missing", body:null});
}
var base = {t: Date.now(), _tb_token_: tk};
var method = (spec.method || "GET").toUpperCase();
var url = spec.path;
var init = {method: method, credentials: "include", headers: {"Accept": "application/json, text/plain, */*"}};
if (method === "POST") {
  url += "?" + encode(base);
  init.headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8";
  init.body = encode(Object.assign({}, base, spec.form || {}));
} else {
  url += "?" + encode(Object.assign({}, base, spec.query || {}));
}
var controller = typeof AbortController !== "undefined" ? new AbortController() : null;
var timer = controller ? setTimeout(function() { controller.abort(); }, 10000) : null;
if (controller) { init.signal = controller.signal; }
return fetch(url, init).then(function(res) {
  if (timer) { clearTimeout(timer); }
  return res.text().then(function(text) {
    var body = null;
    try { body = JSON.parse(text); } catch (e) {}
    if (!body) {
      return JSON.stringify({ok:false, status:res.status, error:"non-json response len=" + text.length, body:null});
    }
    var ok = res.ok && body.success !== false && (body.code == null || body.code === 0 || body.code === "0" || body.code === 200);
    var err = ok ? "" : String(body.message || body.msg || body.resultCode || body.code || ("http " + res.status));
    return JSON.stringify({ok:ok, status:res.status, error:err, body:body});
  });
}).catch(function(e) {
  if (timer) { clearTimeout(timer); }
  return JSON.stringify({ok:false, status:0, error:String(e && e.message || e), body:null});
});

