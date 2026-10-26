package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.*;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.tb.TbMallTask;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Opt-in, serial trace. Never records cookie values and stops at the first login wall. */
@EnabledIfSystemProperty(named="tb.login.trace", matches="true")
class TbLoginTraceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final List<Map<String,Object>> stages = new ArrayList<>();
    Path output;
    TbMallTask task;
    java.lang.reflect.Method login;

    @Test void traceNativeSearch() throws Exception {
        output=Paths.get(System.getProperty("tb.trace.output")); Files.createDirectories(output);
        var config=new AppConfig(); config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getChrome().setCloseBrowserAfterTask(false);
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        var profiles=new BrowserProfileManager(config);
        task=new TbMallTask(config,profiles,new CrawlEntryService(new BrowserPreflightService()),mapper);
        login=TbMallTask.class.getDeclaredMethod("isLoginRequiredPage",Browser.class);login.setAccessible(true);
        var createConfig=TbMallTask.class.getDeclaredMethod("createBrowserConfig",org.rx.crawler.task.tb.TbMallConfig.class);
        createConfig.setAccessible(true);
        var browserConfig=(WebBrowserConfig)createConfig.invoke(task,config.getCustom().getTbMall());
        try(var lease=profiles.acquire("common",browserConfig)) {
            Browser browser=lease.getBrowser();
            if(stage(browser,"01-launched"))return;
            browser.navigateUrl(config.getCustom().getTbMall().getHomeUrl(),Browser.BODY_SELECTOR,60);
            browser.waitForEvents(6000);
            if(stage(browser,"02-home"))return;
            Object popup=browser.executeScript("return Array.from(document.querySelectorAll('#J_TBPC_POP_home [class]')).filter(e=>/close/i.test(e.className)&&e.getBoundingClientRect().width>0).map(e=>({tag:e.tagName,cls:e.className}));");
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("home-popup-controls.json").toFile(),popup);
            if(Boolean.TRUE.equals(browser.executeScript("const e=Array.from(document.querySelectorAll('#J_TBPC_POP_home [class]')).find(e=>/close/i.test(e.className)&&e.getBoundingClientRect().width>0);if(!e)return false;e.setAttribute('data-rx-trace-close','1');return true;"))) {
                browser.elementClick("[data-rx-trace-close='1']",true);browser.waitForEvents(2500);
                if(stage(browser,"02b-close-promotion"))return;
            }
            var input=TbMallTask.class.getDeclaredMethod("nativeSetSearchValue",Browser.class,String.class);input.setAccessible(true);
            if(!Boolean.TRUE.equals(input.invoke(task,browser,"ddr3内存")))throw new IllegalStateException("Native input unavailable");
            browser.waitForEvents(2500);
            if(stage(browser,"03-input"))return;
            browser.pressKey("Enter");
            for(int i=1;i<=6;i++) {
                browser.waitForEvents(2500);
                if(stage(browser,"04-search-"+i))return;
                if(browser.getCurrentUrl().contains("s.taobao.com/search"))break;
                var tabs=WebBrowser.class.getDeclaredField("tabs");tabs.setAccessible(true);
                var open=(Map<String,com.microsoft.playwright.Page>)tabs.get(browser);
                String search=open.entrySet().stream().filter(e->e.getValue().url().contains("s.taobao.com/search"))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if(search!=null) {
                    ((WebBrowser)browser).switchTab(search);
                    if(stage(browser,"04b-adopt-search-tab"))return;
                    break;
                }
            }
            for(int i=1;i<=6;i++) {
                browser.scrollPage(0,600);browser.waitForEvents(2500);
                if(stage(browser,"05-scroll-"+i))return;
            }
        } finally { profiles.closeSession("common"); }
    }

    boolean stage(Browser browser,String name) throws Exception {
        var row=new LinkedHashMap<String,Object>();row.put("stage",name);row.put("time",Instant.now().toString());
        // URL path only: query strings may contain authentication parameters.
        var uri=java.net.URI.create(browser.getCurrentUrl()); row.put("page",uri.getScheme()+"://"+uri.getHost()+uri.getPath());
        row.put("loginRequired",login.invoke(task,browser));
        row.put("visibleCards",browser.executeScript("return Array.from(document.querySelectorAll('a[id^=\"item_id_\"]')).filter(e=>e.getBoundingClientRect().height>0).length;"));
        var field=WebBrowser.class.getDeclaredField("context");field.setAccessible(true);
        var context=(BrowserContext)field.get(browser);
        var pages=new ArrayList<Map<String,Object>>();
        int index=0;
        for(var p:context.pages()) {
            var u=java.net.URI.create(p.url());
            pages.add(Map.of("page",u.getScheme()+"://"+u.getHost()+u.getPath(),"title",p.title()));
            Files.write(output.resolve(name+"-tab-"+(index++)+".png"),p.screenshot());
        }
        row.put("tabs",pages);
        row.put("authCookieMetadata",context.cookies().stream().filter(c->c.domain.endsWith("taobao.com")&&Set.of("unb","cookie2","cookie17","_nk_","lgc","tracknick","sgcookie").contains(c.name))
                .map(c->Map.of("name",c.name,"session",c.expires==null||c.expires<0)).toList());
        stages.add(row);mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("trace.json").toFile(),stages);
        Files.write(output.resolve(name+".png"),browser.captureViewportPng());
        System.out.println("LOGIN_TRACE "+mapper.writeValueAsString(row));
        return Boolean.TRUE.equals(row.get("loginRequired"));
    }
}
