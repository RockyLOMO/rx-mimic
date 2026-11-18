package org.rx.crawler.task.aqc;

import org.rx.core.Strings;

import java.util.List;

/**
 * 爱企查结果完整性校验：核心字段缺失按页面结构变化处理，卡片缺 pid/公司名直接丢弃。
 */
public final class AqcResultChecks {
    private AqcResultChecks() {
    }

    /**
     * 详情核心字段：公司名/统一社会信用代码/法定代表人缺一即视为页面结构未识别。
     */
    public static void detail(AqcCompanyDetailResult result, List<String> errors) {
        if (Strings.isEmpty(result.getCompanyName())) {
            errors.add("companyName");
        }
        if (Strings.isEmpty(result.getCreditCode())) {
            errors.add("creditCode");
        }
        if (Strings.isEmpty(result.getLegalPerson())) {
            errors.add("legalPerson");
        }
    }

    /**
     * 搜索卡片必须有 pid 与公司名，否则丢弃并记入 errors。
     */
    public static List<AqcSearchItem> completeItems(List<AqcSearchItem> items, List<String> errors) {
        List<AqcSearchItem> complete = new java.util.ArrayList<AqcSearchItem>();
        for (AqcSearchItem item : items) {
            if (Strings.isEmpty(item.getPid()) || Strings.isEmpty(item.getCompanyName())) {
                errors.add(String.valueOf(item.getCompanyName()));
                continue;
            }
            complete.add(item);
        }
        return complete;
    }
}
