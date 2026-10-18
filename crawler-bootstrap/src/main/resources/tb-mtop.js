var spec = JSON.parse(arguments[0]);
function waitMtop() {
  return new Promise(function(resolve) {
    var n = 0;
    (function tick() {
      if (window.lib && window.lib.mtop && typeof window.lib.mtop.request === "function") { resolve(true); return; }
      if (++n > 40) { resolve(false); return; }
      setTimeout(tick, 200);
    })();
  });
}
function retOk(ret) {
  if (!ret || !ret.length) { return false; }
  return String(ret[0]).indexOf("SUCCESS") === 0;
}
return waitMtop().then(function(ready) {
  if (!ready) { return JSON.stringify({ok:false, error:"lib.mtop missing", body:null}); }
  var timer;
  var timeout = new Promise(function(resolve) {
    timer = setTimeout(function() { resolve({ret:["TIMEOUT::mtop request timeout"]}); }, spec.timeoutMillis || 15000);
  });
  var call = window.lib.mtop.request({
    api: spec.api,
    v: spec.v,
    data: spec.data || {},
    type: spec.type || "GET",
    dataType: spec.dataType || "jsonp",
    timeout: spec.timeoutMillis || 15000,
    ecode: 0,
    valueType: "original"
  }).then(function(res) { return res; }, function(err) { return err || {ret:["FAIL::rejected"]}; });
  return Promise.race([call, timeout]).then(function(res) {
    clearTimeout(timer);
    var body = res && res.data !== undefined ? res : (res && res.retJson) || res;
    var ret = body && body.ret;
    var ok = retOk(ret);
    return JSON.stringify({ok:ok, error: ok ? "" : String(ret && ret[0] || "no ret"), body: body});
  });
}).catch(function(e) {
  return JSON.stringify({ok:false, error:String(e && e.message || e), body:null});
});
