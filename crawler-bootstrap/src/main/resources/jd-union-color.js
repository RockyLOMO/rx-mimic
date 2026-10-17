var spec = JSON.parse(arguments[0]);
function waitSign() {
  return new Promise(function(resolve) {
    var n = 0;
    (function tick() {
      if (window.ParamsSign) { resolve(true); return; }
      if (++n > 40) { resolve(false); return; }
      setTimeout(tick, 200);
    })();
  });
}
function jsToken() {
  return new Promise(function(resolve) {
    var done = false;
    function finish(v) { if (!done) { done = true; resolve(v || ""); } }
    try {
      if (typeof window.getJsToken === "function") {
        window.getJsToken(function(e) { finish(e && (e.jsToken || e.token || "")); }, 3000);
      }
    } catch (e) {}
    setTimeout(function() { finish(""); }, 3200);
  });
}
function sha256(text) {
  var bytes = new TextEncoder().encode(text);
  return crypto.subtle.digest("SHA-256", bytes).then(function(buf) {
    return Array.prototype.map.call(new Uint8Array(buf), function(b) {
      return ("0" + b.toString(16)).slice(-2);
    }).join("");
  });
}
function callColor() {
  return waitSign().then(function(ready) {
    if (!ready) { return {ok:false, error:"ParamsSign missing", body:null}; }
    return jsToken().then(function(token) {
      var bodyObj = Object.assign({}, spec.params, {clientPageId:"jingfen_pc"});
      var bodyText = JSON.stringify(bodyObj);
      return sha256(bodyText).then(function(hash) {
        var signer = new window.ParamsSign({appId:"586ae"});
        return signer.sign({functionId:spec.functionId, appid:spec.appid, body:hash}).then(function(signed) {
          var h5st = signed && (signed.h5st || (signed.data && signed.data.h5st) || "");
          var jda = (document.cookie.match(/(?:^|; )__jda=([^;]*)/) || [])[1] || "";
          var uuid = "";
          try { uuid = decodeURIComponent(jda).split(".")[1] || ""; } catch (e) {}
          var q = ["functionId=" + encodeURIComponent(spec.functionId), "appid=" + encodeURIComponent(spec.appid),
            "_=" + Date.now(), "loginType=3"];
          if (uuid) { q.push("uuid=" + encodeURIComponent(uuid)); }
          if (token) { q.push("x-api-eid-token=" + encodeURIComponent(token)); }
          if (h5st) { q.push("h5st=" + encodeURIComponent(h5st)); }
          var url = "https://api.m.jd.com/api?" + q.join("&");
          var method = (spec.method || "GET").toUpperCase();
          var init = {method:method, credentials:"include"};
          if (method === "POST") {
            init.headers = {"Content-Type":"application/x-www-form-urlencoded;charset=UTF-8"};
            init.body = "body=" + encodeURIComponent(bodyText);
          } else {
            url += "&body=" + encodeURIComponent(bodyText);
          }
          return fetch(url, init).then(function(res) { return res.json(); }).then(function(body) {
            var code = body && body.code;
            var ok = code == null || code === 200 || code === "200" || code === 0 || code === "0";
            if (code === 402 || code === "402" || code === 413 || code === "413" || code === 420 || code === "420") {
              ok = false;
            }
            return {ok:ok, code:code == null ? "" : String(code), error: ok ? "" : String((body && (body.message || body.msg)) || ""), body:body};
          });
        });
      });
    });
  });
}
return callColor().then(function(out) { return JSON.stringify(out); }).catch(function(e) {
  return JSON.stringify({ok:false, error:String(e && e.message || e), body:null});
});
