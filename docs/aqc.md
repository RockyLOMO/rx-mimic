# 爱企查

抓取百度爱企查上的公司主体信息。通用接入见 [文档汇总](README.md)。

公开 action：

| action | 作用 | 主要入参 |
| --- | --- | --- |
| `aqcCompanySearch` | 按名称搜索公司 | `keyword`，`maxResults` 默认 20、上限 50 |
| `aqcCompanyDetail` | 打开公司详情，读取执照信息及股东、人员、变更、风险栏目 | `pid`；也可带 `keyword` |

页面入口是 `https://aiqicha.baidu.com`。详情地址模板为 `https://aiqicha.baidu.com/company_detail_{pid}`。登录墙前缀是 `https://passport.baidu.com`。

本地遗留入口默认关闭：`POST /custom/aqc/search`、`POST /custom/aqc/company/detail`，再用 `GET /custom/aqc/task/{taskId}` 取结果。公网用 `POST /api/v1/tasks`。

配置在 `app.custom.aqc`。百度安全验证没有自动拖动，默认等人工 120 秒。调试文件在 `./data/aqc/debug`。
