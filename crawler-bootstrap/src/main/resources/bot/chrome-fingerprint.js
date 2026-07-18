(function () {
    if (window.__rxChromeFingerprintReady) {
        return;
    }
    Object.defineProperty(window, "__rxChromeFingerprintReady", {
        value: true,
        configurable: false,
        enumerable: false,
        writable: false
    });

    var defineGetter = function (target, name, getter) {
        try {
            Object.defineProperty(target, name, {
                get: getter,
                configurable: true
            });
        } catch (e) {
        }
    };

    // 1) webdriver：真实非自动化 Chrome 为 false/undefined，绝不能暴露 true。
    try {
        delete Navigator.prototype.webdriver;
    } catch (e) {
    }
    defineGetter(Navigator.prototype, "webdriver", function () {
        return undefined;
    });

    // 2) language / languages 由 WebBrowser locale 对齐脚本统一注入（与 Accept-Language 同源），
    // 此处不再覆盖，避免与请求头、--lang 配置冲突。

    defineGetter(Navigator.prototype, "vendor", function () {
        return "Google Inc.";
    });

    // 3) 硬件并发/内存：保留真实值。硬编码 8 会与本机其它信号（GPU/屏幕/时区）冲突，反而更易被判假。
    // 不再覆盖 hardwareConcurrency / deviceMemory。

    // 4) window.chrome：有头真实 Chrome 通常已存在；仅补缺失骨架，避免空对象过简。
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

    // 5) permissions：通知权限与 Notification.permission 对齐（常见自动化探测点）。
    try {
        var rawQuery = navigator.permissions && navigator.permissions.query;
        if (rawQuery && !rawQuery.__rxPatched) {
            var patchedQuery = function (parameters) {
                if (parameters && parameters.name === "notifications") {
                    return Promise.resolve({
                        state: Notification.permission,
                        onchange: null
                    });
                }
                return rawQuery.apply(this, arguments);
            };
            patchedQuery.__rxPatched = true;
            navigator.permissions.query = patchedQuery;
        }
    } catch (e) {
    }

    // 6) 破图 naturalWidth/Height：headless 下 broken image 为 0 的经典特征。
    try {
        var widthDesc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "naturalWidth");
        var heightDesc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "naturalHeight");
        if (widthDesc && widthDesc.get) {
            var widthGetter = widthDesc.get;
            Object.defineProperty(HTMLImageElement.prototype, "naturalWidth", {
                get: function () {
                    var width = widthGetter.call(this);
                    return this.complete && width === 0 ? 16 : width;
                },
                configurable: true
            });
        }
        if (heightDesc && heightDesc.get) {
            var heightGetter = heightDesc.get;
            Object.defineProperty(HTMLImageElement.prototype, "naturalHeight", {
                get: function () {
                    var height = heightGetter.call(this);
                    return this.complete && height === 0 ? 16 : height;
                },
                configurable: true
            });
        }
    } catch (e) {
    }

    // 7) WebGL：仅在软件渲染/无 GPU（SwiftShader/llvmpipe 等）时补丁。
    // 旧逻辑无条件伪造 macOS Intel Iris，在 Windows 真机上会与 platform/UA 严重冲突。
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
        var patchWebGl = function (prototype) {
            var rawGetParameter = prototype && prototype.getParameter;
            if (!rawGetParameter || rawGetParameter.__rxPatched) {
                return;
            }
            var getParameter = function (parameter) {
                // UNMASKED_VENDOR_WEBGL = 37445, UNMASKED_RENDERER_WEBGL = 37446
                if (parameter === 37445 || parameter === 37446) {
                    var original = rawGetParameter.apply(this, arguments);
                    if (!isSoftwareRenderer(original)) {
                        return original;
                    }
                    if (parameter === 37445) {
                        return "Google Inc. (Intel)";
                    }
                    return "ANGLE (Intel, Intel(R) UHD Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)";
                }
                return rawGetParameter.apply(this, arguments);
            };
            getParameter.__rxPatched = true;
            prototype.getParameter = getParameter;
        };
        patchWebGl(WebGLRenderingContext && WebGLRenderingContext.prototype);
        patchWebGl(WebGL2RenderingContext && WebGL2RenderingContext.prototype);
    } catch (e) {
    }

    // 8) plugins / mimeTypes：有头 Chrome 通常已有；为空时补最小集合（避免 plugins.length===0）。
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
            defineGetter(Navigator.prototype, "plugins", function () {
                return new PluginArray();
            });
        }
    } catch (e) {
    }
})();
