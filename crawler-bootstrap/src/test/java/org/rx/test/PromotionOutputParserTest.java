package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.crawler.task.common.PromotionOutputParser;
import org.rx.crawler.task.price.PriceCodes;
import static org.junit.jupiter.api.Assertions.*;

class PromotionOutputParserTest {
    @Test void taobaoCopyKeepsOnlyCommandAndNativeShortUrl() {
        String copy = "【券面额】6元 【到手价】72.00元 【下单链接】18 ¥ CZ001 fSUiTDnNz8G ¥ https://m.tb.cn/h.Example 【商品标题】";
        assertEquals("¥CZ001 fSUiTDnNz8G¥", PromotionOutputParser.taobaoToken(copy));
        assertEquals("https://m.tb.cn/h.Example", PromotionOutputParser.firstShortUrl(copy, PriceCodes.TB));
        assertEquals("￥AbCd12345￥", PromotionOutputParser.taobaoToken("￥ AbCd12345 ￥"));
        assertEquals("", PromotionOutputParser.taobaoToken("到手价 ¥72.00 / ￥3.24"));
    }
    @Test void firstNativeLinkWinsAndLongLinksCannotMasqueradeAsShort() {
        String text = "https://item.jd.com/123.html https://u.jd.com/first https://3.cn/second";
        assertEquals("https://u.jd.com/first", PromotionOutputParser.firstShortUrl(text, PriceCodes.JD));
        assertEquals("", PromotionOutputParser.firstShortUrl("https://union-click.jd.com/jdc?e=long", PriceCodes.JD));
        assertEquals("", PromotionOutputParser.firstShortUrl("https://tb.cn.evil.test/a", PriceCodes.TB));
        assertEquals("https://a.3.cn/abc?jkl=@Example@", PromotionOutputParser.firstShortUrl("https://a.3.cn/abc?jkl=@Example@", PriceCodes.JD));
    }
    @Test void analysisShortModeAcceptsOfficialPathShortLinkButNotSignedGatewayOrLookalikeHost() {
        assertEquals("https://s.click.taobao.com/vOGLdKi", PromotionOutputParser.firstShortUrl(
                "推广素材 https://s.click.taobao.com/vOGLdKi", PriceCodes.TB));
        assertEquals("", PromotionOutputParser.firstShortUrl("https://s.click.taobao.com/t?e=signed", PriceCodes.TB));
        assertEquals("", PromotionOutputParser.firstShortUrl("https://s.click.taobao.com.evil.test/vOGLdKi", PriceCodes.TB));
        assertEquals("", PromotionOutputParser.firstShortUrl("https://s.click.taobao.com/vOGLdKi", PriceCodes.JD));
    }
    @Test void jdShortBlockKeepsFullCommandButRejectsLongTitlePrefix() {
        assertEquals("! Z7Example! ZH9112", PromotionOutputParser.jdShortToken("短口令： ! Z7Example! ZH9112"));
        assertEquals("！ Z7Example！ ZH9112", PromotionOutputParser.jdShortToken("！ Z7Example！ ZH9112"));
        assertEquals("", PromotionOutputParser.jdShortToken("11/商品标题 ! Z7Example! ZH9112"));
        assertEquals("", PromotionOutputParser.jdShortToken("复制京口令"));
    }
}
