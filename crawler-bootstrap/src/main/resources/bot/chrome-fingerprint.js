(function () {
    // 内部幂等标记保留在闭包内，禁止挂载到 window 暴露注入痕迹（如 window.__rxChromeFingerprintReady）。
    var __rxChromeFingerprintReady = true;
    if (!__rxChromeFingerprintReady) {
        return;
    }

    var wrapGetterWithProxy = function (target, name, applyTrap) {
        try {
            var desc = Object.getOwnPropertyDescriptor(target, name);
            if (desc && typeof desc.get === "function") {
                Object.defineProperty(target, name, {
                    get: new Proxy(desc.get, { apply: applyTrap }),
                    set: desc.set,
                    enumerable: desc.enumerable,
                    configurable: desc.configurable
                });
                return;
            }
            Object.defineProperty(target, name, {
                get: applyTrap,
                enumerable: true,
                configurable: true
            });
        } catch (e) {
        }
    };

    // 0) 清理自动化框架遗留全局变量（Playwright / ChromeDriver cdc_ / Puppeteer 等），不留自定义全局变量。
    try {
        var automationGlobals = [
            "__playwright",
            "__pw_manual",
            "__puppeteer_evaluation_script__",
            "callPhantom",
            "_phantom",
            "__nightmare",
            "domAutomation",
            "domAutomationController"
        ];
        for (var i = 0; i < automationGlobals.length; i++) {
            var prop = automationGlobals[i];
            if (prop in window) {
                try { delete window[prop]; } catch (e) {}
            }
        }
        var cdcPattern = /^cdc_[a-zA-Z0-9]+_(Array|Promise|Symbol|Object|Proxy)$/;
        var cleanCdc = function (root) {
            if (!root) {
                return;
            }
            var keys = Object.getOwnPropertyNames(root);
            for (var k = 0; k < keys.length; k++) {
                if (cdcPattern.test(keys[k])) {
                    try { delete root[keys[k]]; } catch (e) {}
                }
            }
        };
        cleanCdc(window);
        cleanCdc(document);
    } catch (e) {
    }

    // 1) webdriver：真实 Chrome 配合 --disable-blink-features=AutomationControlled 时，
    // Navigator.prototype.webdriver 原生即为 false，且 getter 为 [native code]、enumerable: true。
    // 此时严禁 delete 或 defineProperty 重写，否则会被 fireyejs / baxia 检出原型篡改痕迹。
    try {
        if (navigator.webdriver !== false) {
            wrapGetterWithProxy(Navigator.prototype, "webdriver", function () {
                return false;
            });
        }
    } catch (e) {
    }

    // 2) language / languages 交给本机 Chrome / profile，此处不覆盖，避免与请求头冲突。
    // vendor：真实 Chrome 原生已为 "Google Inc."，仅缺失时兜底。
    try {
        if (!navigator.vendor) {
            wrapGetterWithProxy(Navigator.prototype, "vendor", function () {
                return "Google Inc.";
            });
        }
    } catch (e) {
    }

    // 3) 硬件并发/内存：保留本机真实值，禁止硬编码。

    // 4) window.chrome：有头真实 Chrome 原生已完整具备；仅在缺失时按原生描述符补齐骨架。
    try {
        if (!window.chrome) {
            Object.defineProperty(window, "chrome", {
                value: {},
                writable: true,
                enumerable: true,
                configurable: false
            });
        }
        if (!window.chrome.runtime) {
            window.chrome.runtime = {
                PlatformOs: {
                    MAC: "mac",
                    WIN: "win",
                    ANDROID: "android",
                    CROS: "cros",
                    LINUX: "linux",
                    OPENBSD: "openbsd"
                },
                PlatformArch: {
                    ARM: "arm",
                    ARM64: "arm64",
                    X86_32: "x86-32",
                    X86_64: "x86-64"
                },
                PlatformNaclArch: {
                    ARM: "arm",
                    X86_32: "x86-32",
                    X86_64: "x86-64"
                },
                RequestUpdateCheckStatus: {
                    THROTTLED: "throttled",
                    NO_UPDATE: "no_update",
                    UPDATE_AVAILABLE: "update_available"
                },
                OnInstalledReason: {
                    INSTALL: "install",
                    UPDATE: "update",
                    CHROME_UPDATE: "chrome_update",
                    SHARED_MODULE_UPDATE: "shared_module_update"
                },
                OnRestartRequiredReason: {
                    APP_UPDATE: "app_update",
                    OS_UPDATE: "os_update",
                    PERIODIC: "periodic"
                }
            };
        }
        if (!window.chrome.csi) {
            window.chrome.csi = function () {
                var timing = performance.timing || {};
                return {
                    startE: timing.navigationStart || Date.now(),
                    onloadT: timing.loadEventEnd || 0,
                    pageT: Date.now() - (timing.navigationStart || Date.now()),
                    tran: 15
                };
            };
        }
        if (!window.chrome.loadTimes) {
            window.chrome.loadTimes = function () {
                var timing = performance.timing || {};
                var nav = performance.getEntriesByType && performance.getEntriesByType("navigation")[0];
                return {
                    requestTime: ((timing.navigationStart || Date.now()) / 1000),
                    startLoadTime: ((timing.navigationStart || Date.now()) / 1000),
                    commitLoadTime: ((timing.responseStart || Date.now()) / 1000),
                    finishDocumentLoadTime: ((timing.domContentLoadedEventEnd || Date.now()) / 1000),
                    finishLoadTime: ((timing.loadEventEnd || Date.now()) / 1000),
                    firstPaintTime: nav && nav.responseStart ? nav.responseStart / 1000 : 0,
                    firstPaintAfterLoadTime: 0,
                    navigationType: "Other",
                    wasFetchedViaSpdy: true,
                    wasNpnNegotiated: true,
                    npnNegotiatedProtocol: "h2",
                    wasAlternateProtocolAvailable: false,
                    connectionInfo: "h2"
                };
            };
        }
    } catch (e) {
    }

    // 5) permissions：有头真实 Chrome 下 Notification.permission 为 default，与 permissions.query 原生一致，无需改写。
    // 仅当无头环境出现 Notification.permission === "denied" 时用 Proxy 对齐，且不挂载自定义属性。
    try {
        if (typeof Notification !== "undefined" && Notification.permission === "denied"
                && navigator.permissions && typeof navigator.permissions.query === "function") {
            var rawQuery = navigator.permissions.query;
            navigator.permissions.query = new Proxy(rawQuery, {
                apply: function (target, thisArg, args) {
                    var parameters = args && args[0];
                    if (parameters && parameters.name === "notifications") {
                        return Promise.resolve(Object.setPrototypeOf({
                            state: Notification.permission,
                            onchange: null
                        }, PermissionStatus.prototype));
                    }
                    return Reflect.apply(target, thisArg, args);
                }
            });
        }
    } catch (e) {
    }

    // 6) 破图 naturalWidth/Height：仅在无头（outerWidth === 0）环境下通过原生 Getter Proxy 兜底，
    // 保持 Function.prototype.toString 返回 [native code] 且保留 enumerable 描述符。
    try {
        if (window.outerWidth === 0 || window.outerHeight === 0) {
            wrapGetterWithProxy(HTMLImageElement.prototype, "naturalWidth", function (target, thisArg, args) {
                var width = Reflect.apply(target, thisArg, args);
                return thisArg && thisArg.complete && width === 0 ? 16 : width;
            });
            wrapGetterWithProxy(HTMLImageElement.prototype, "naturalHeight", function (target, thisArg, args) {
                var height = Reflect.apply(target, thisArg, args);
                return thisArg && thisArg.complete && height === 0 ? 16 : height;
            });
        }
    } catch (e) {
    }

    // 7) WebGL：仅当本机实际渲染器为软件渲染（SwiftShader/llvmpipe 等）时才挂 Proxy 补丁。
    // 真机有物理/集成显卡时完全不触碰 WebGLRenderingContext.prototype.getParameter，保持 100% 原生。
    try {
        var isSoftwareRenderer = function (value) {
            if (!value) {
                return true;
            }
            var text = String(value).toLowerCase();
            return text.indexOf("swiftshader") >= 0
                || text.indexOf("llvmpipe") >= 0
                || text.indexOf("softpipe") >= 0
                || text.indexOf("microsoft basic render") >= 0
                || text.indexOf("google swiftshader") >= 0;
        };
        var needsWebGlPatch = (function () {
            try {
                var canvas = document.createElement("canvas");
                var gl = canvas.getContext("webgl") || canvas.getContext("experimental-webgl");
                if (!gl) {
                    return false;
                }
                var ext = gl.getExtension("WEBGL_debug_renderer_info");
                if (!ext) {
                    return false;
                }
                var renderer = gl.getParameter(ext.UNMASKED_RENDERER_WEBGL);
                return isSoftwareRenderer(renderer);
            } catch (e) {
                return false;
            }
        })();
        if (needsWebGlPatch) {
            var patchWebGl = function (prototype) {
                var rawGetParameter = prototype && prototype.getParameter;
                if (typeof rawGetParameter !== "function") {
                    return;
                }
                prototype.getParameter = new Proxy(rawGetParameter, {
                    apply: function (target, thisArg, args) {
                        var parameter = args && args[0];
                        var original = Reflect.apply(target, thisArg, args);
                        if (parameter === 37445 && isSoftwareRenderer(original)) {
                            return "Google Inc. (Intel)";
                        }
                        if (parameter === 37446 && isSoftwareRenderer(original)) {
                            return "ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)";
                        }
                        return original;
                    }
                });
            };
            patchWebGl(typeof WebGLRenderingContext !== "undefined" && WebGLRenderingContext.prototype);
            patchWebGl(typeof WebGL2RenderingContext !== "undefined" && WebGL2RenderingContext.prototype);
        }
    } catch (e) {
    }

    // 8) plugins / mimeTypes：有头真实 Chrome 原生已存在；仅为空时兜底。
    try {
        if (!navigator.plugins || navigator.plugins.length === 0) {
            var fakePluginData = [
                {name: "PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format"},
                {name: "Chrome PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format"},
                {name: "Chromium PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format"}
            ];
            var PluginArray = function () {
                this.length = fakePluginData.length;
                for (var i = 0; i < fakePluginData.length; i++) {
                    this[i] = fakePluginData[i];
                }
            };
            PluginArray.prototype = {
                item: function (i) { return this[i] || null; },
                namedItem: function (name) {
                    for (var i = 0; i < this.length; i++) {
                        if (this[i] && this[i].name === name) {
                            return this[i];
                        }
                    }
                    return null;
                },
                refresh: function () {}
            };
            wrapGetterWithProxy(Navigator.prototype, "plugins", function () {
                return new PluginArray();
            });
        }
    } catch (e) {
    }
})();
