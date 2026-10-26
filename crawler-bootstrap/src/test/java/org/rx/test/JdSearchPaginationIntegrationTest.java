package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local responses and native Playwright clicks, with production extraction and pagination. */
@EnabledIfSystemProperty(named="jd.pagination.integration",matches="true")
class JdSearchPaginationIntegrationTest {
    @TempDir Path temp;

    @Test void fillsFortyFromSecondPageAndContinuesAfterOverlappingSecondPage() throws Exception {
        exercise(false,false);exercise(true,false);
    }
    @Test void actualLastPageReturnsFewerWithoutCollectingRecommendations() throws Exception {
        exercise(false,true);
    }
    @Test void failedNextPageIsReportedAsIncomplete() throws Exception {
        exercise(false,false,true);
    }
    void exercise(boolean overlap,boolean last) throws Exception {
        exercise(overlap,last,false);
    }
    void exercise(boolean overlap,boolean last,boolean broken) throws Exception {
        try(var pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var context=pw.chromium().launchPersistentContext(temp.resolve(UUID.randomUUID().toString()),
                    new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(false))) {
            context.route("**/*",r->{
                String url=r.request().url();int p=broken?1:url.contains("page=3")?3:url.contains("page=2")?2:1;
                StringBuilder cards=new StringBuilder();
                int start=last?1:overlap?(p==1?1:p==2?3:63):(p-1)*60+1;
                int count=last?15:60;
                for(int i=start;i<start+count;i++)cards.append(card(i));
                String html="<style>.card{height:26px} ._quick-result_x{position:fixed;top:0;background:white}</style>"
                        +"<div class='_quick-result_x'><span class='_quick-num_x'>prev</span><span>"+p+" / "+(last?1:3)+"</span>"
                        +"<a class='_quick-num_x' href='https://search.jd.com/search?page="+(p+1)+"'>→</a></div>"
                        +"<div id='searchCenter'><div class='_goodsContainer-box_x'>"+cards+"</div>"
                        +"<h3>猜你喜欢</h3>"+card(9999)+"</div>";
                r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
            });
            Page page=context.pages().getFirst();page.navigate("https://search.jd.com/search?page=1");
            Browser browser=mock(Browser.class,call->{
                Object[] args=call.getArguments();
                return switch(call.getMethod().getName()) {
                    case "executeScript" -> page.evaluate("args=>(function(){"+args[0]+"}).apply(null,args)",Arrays.asList(Arrays.copyOfRange(args,1,args.length)));
                    case "getCurrentUrl" -> page.url();
                    case "hasElement" -> page.locator((String)args[0]).count()>0;
                    case "elementClick" -> {page.locator((String)args[0]).click();yield null;}
                    case "scrollPage" -> {page.mouse().wheel(((Number)args[0]).doubleValue(),((Number)args[1]).doubleValue());yield null;}
                    case "waitForEvents" -> {page.waitForTimeout(40);yield null;}
                    case "navigateUrl" -> {page.navigate((String)args[0]);yield null;}
                    default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                };
            });
            var app=new AppConfig();var config=app.getCustom().getJdMall();
            config.setStepDelayMillis(0);config.setStepDelayRandomMillis(0);
            config.setPageTimeoutSeconds(1);
            var task=new JdMallTask(app,null,null,new ObjectMapper());
            var debugClass=Class.forName(JdMallTask.class.getName()+"$DebugRecorder");
            var constructor=debugClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
            var debug=constructor.newInstance(task,"fixture","pagination",false,temp.toString(),config,JdMallTask.TASK_TYPE);
            var collect=JdMallTask.class.getDeclaredMethod("collectSearchPages",Browser.class,BrowserProfileManager.ProfileLease.class,
                    JdSearchRequest.class,JdMallConfig.class,CrawlEntryOptions.class,JdSearchResult.class,debugClass);
            collect.setAccessible(true);
            var request=new JdSearchRequest();request.setKeyword("switch");request.setMaxResults(100);
            var result=new JdSearchResult();
            var items=(List<JdSearchItem>)collect.invoke(task,browser,null,request,config,new CrawlEntryOptions(),result,debug);
            if(broken) {
                assertEquals(60,items.size());assertEquals(false,result.getDiagnostics().get("searchComplete"));
                assertEquals(CustomCrawlStatus.PAGE_CHANGED,result.getStatus());return;
            }
            assertEquals(last?15:100,items.size());
            assertEquals(items.size(),items.stream().map(JdSearchItem::getSkuId).distinct().count());
            assertTrue(items.stream().noneMatch(i->"9999".equals(i.getSkuId())));
            assertEquals(last?1:overlap?3:2,result.getDiagnostics().get("pagesFetched"));
            assertEquals(true,result.getDiagnostics().get("searchComplete"));
            if(!last)assertEquals("100",items.getLast().getSkuId());
        }
    }
    String card(int id) {
        return "<div class='card' data-sku='"+id+"'><a title='Product "+id+"' class='p-name' href='https://item.jd.com/"+id+".html'>Product "+id+"</a>"
                +"<div class='p-price'>¥20</div><img src='https://img.test/"+id+".png'></div>";
    }
}
