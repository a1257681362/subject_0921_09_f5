# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

## F1：户外广告点位与上刊底账（f1-ledger-1）

围绕"点位档案 → 按周档期 → 上刊单"立底账，本版跑通挂牌、上刊、落架、翻查；结算不在本轮范围。

业务口径：

- **点位档案**：编号、尺寸、朝向、商圈缺一不可；点位编号唯一，重复存不进去。
- **档期按周排**：ISO 周（周一至周日），同一点位同一周只能挂一张画面。
- **上刊单**：画面挂在点位上，状态 `HUNG 挂起 / TAKEN_DOWN 落架 / OFFSET 已对冲`；单号 `PO+序列` 全局唯一。
- **改错不抹记录**：原单置"已对冲"并释放档期，另补一张对冲单挂同一根单（原单、对冲单可按根链翻查）。
- **随单留底**：每步动作写事件留底——经手人、发生日、事件时刻、单据快照；支持挂起中单据的版本选择与回退（回退也留一条事件，关闭单拒绝回退）。
- **关闭即冻结**：落架/对冲后的单据不能静默修改、重复落架或回退。
- **跨月归属**：归属期间 `biz_period` 按发生日落库固化，不随查询日漂移。
- **外部回执**：挂回原单据；回执指向对冲单时自动回溯到根单。
- **月度档期周历**：`/api/ledger/calendar?period=yyyy-MM` 一眼看哪块屏哪周空着。

账号：演示账号 `demo / demo`，骨架账号 `bootstrap / bootstrap`。

## F2：素材流水与多规格转档（f2-asset-transcode-1）

美工传一张原图，横竖两版常规规格自动出，不用再手工导七八个版本。

业务口径：

- **素材档案按文件指纹认人**：原图算 SHA-256 指纹，`fingerprint_sha256` 唯一；同一张图重复上传（哪怕改了文件名）只累加 `upload_count`，**不另开档、不重跑转档**。
- **横竖两版常规规格自动出**：横版 `16:9`、竖版 `9:16`，均居中裁切不变形，**长边压进限定像素**（默认 1280px，出标准件 1280×720 / 720×1280）。
- **体积超标再压一轮质量**：产物先按首轮质量（0.85）出 JPEG，超过体积上限（默认 300KB）就每轮降一档质量（步长 0.15，下限 0.50），取体积最小一轮；压到下限仍超标也交差，任务不算失败。规格/长边/上限/质量档均在 `application.yml` 的 `asset.*` 配置。
- **结果记进素材档案、标清用途**：每个产物一行，`usage` 记规格标识（信息流横版 `feed_landscape` / 信息流竖版 `feed_portrait`），并记尺寸、长边、体积、质量轮次、最终质量。
- **多规格转档版本**：上传首轮记 v1（来源 UPLOAD）；手动再跑不抹历史，另起一版（来源 RETRY），各版本产物独立可翻。
- **多规格转档状态**：任务 `PENDING 待处理 / PROCESSING 转档中 / SUCCESS 成功 / FAILED 失败`，状态同时挂在版本和任务上；**失败原因落库**（如"图片格式不支持或文件已损坏"），前端轮询即可见，美工不干等；失败可一键重新转档。
- 上传接口立即返回任务号，转档在后台线程池异步执行（事务提交后触发，避免后台读不到未提交任务）。
- 原图与产物落盘 `asset.storage-root`（默认 `./data/assets`，原图按指纹存、产物按 素材/版本/规格 存），库里存相对路径。

## F3：点位经纬度建档与半径捞点（f3-site-geo-1）

拓展部圈商圈不再只靠感觉：点位档案补上经纬度，商圈落成圆心+半径的圈界，给个坐标和公里数就能把圈里在营点位全捞出来，跑盘清单对得上。

业务口径：

- **坐标十进制度落库，小数位不丢**：纬度 `DECIMAL(10,7)`、经度 `DECIMAL(11,7)`，最多 7 位小数；超界（纬度 ±90、经度 ±180）或超位直接拒收，**不静默截断**。
- **经纬度建档版本**：首次建档案记 v1（来源 INITIAL）；坐标录错了纠偏重录，不抹历史，另起一版（来源 CORRECT），旧版置 SUPERSEDED 可回翻；点位档案上的坐标始终指向当前生效版本。
- **经纬度建档状态**：点位 `UNFILED 未建档 / FILED 已建档`，未建档的点位不进半径检索结果。
- **商圈圈界**：每个商圈一条圆心坐标+半径（公里），同名不能重复圈；圈错了按商圈名**重圈**（当前态覆盖）。点位归属不受影响——**归属始终按录入时定的主商圈走**。
- **半径捞点**：给个坐标、给个公里数（0~100，最多 3 位小数），圈里**在营且已建档**的点位全列出，Haversine 球面距离，**按距离升序**（并列按点位编号稳住）。
- **商圈重叠带标出来**：点位坐标同时落进 ≥2 个商圈圈界的，结果里标「重叠带」并列出实际覆盖的商圈名单；标记只作提示，不改归属。
- **整页能导出**：检索结果整页导出 CSV（带 BOM，Excel 打开不乱码），列含主商圈归属、距离、十进制度坐标、重叠带标记。

## F4：年度刊例与统一计价（f4-rate-card-1）

两家店报价差几十块、客户拿着单子来砍价——刊例收进 evops-base 做统一入口：点位挂年度刊例，按购买档期长度落阶梯折扣；报价只按提交当天的刊例算，历史报价留当时单价快照，调价不许回写旧单。计价逻辑全在 `PricingEngine` 一处，不散在各接口。

业务口径：

- **年度刊例**：一个自然年一份（年份唯一），状态 `DRAFT 编制中 / EFFECTIVE 生效中 / CLOSED 已封存`；封存后不能再起新版、不能提交新报价（历史报价照翻）。
- **年度刊例版本**：建档自动带 v1 草稿；**草稿可反复整表配置，发布后冻结**，档位与点位价一个字不能改。调价只能「另起新版本」（从上一版拷贝档位与点位价做起点）再发布；新版发布时旧生效版自动置 `SUPERSEDED 已被新版取代`，历史永久可翻。版本状态 `DRAFT / EFFECTIVE / SUPERSEDED / REVOKED 已作废`；生效版录错了可作废（留底不删），作废后该年度重新发布版本才能报价。
- **点位挂年度刊例**：版本内每个点位一条挂牌**周单价（未税，折前）**；点位必须已挂牌、不能重复配。没挂进生效刊例的点位不能报价。
- **按购买档期长度落阶梯折扣**：档位按周数配区间 `[minWeeks, maxWeeks]`（闭区间，末档 `maxWeeks` 留空表示「及以上」），档位比例 = 刊例价 × 比例（1 不打折、0.9 九折）。配置即校验：首档从 1 周起、首尾相接**不许断档/重叠**、开口档只能在最后、比例随周数递减（买越长越便宜）。
- **报价按提交当天的刊例算**：按提交日所在自然年取「生效中」刊例的当前版本；提交日留空取今天，**不许按未来日期报价**；刊例没发布/已封存/点位没挂价都直接拒收。
- **历史报价留单价快照，不回写旧单**：提交当时把刊例年份、刊例编号、版本号、税率、点位挂牌价、落档档位、档位比例、折后单价、未税/税额/含税金额，连同点位编号/尺寸/朝向/商圈全部复制进报价单与报价行；之后刊例调价、换版、点位改档都与旧单无关。系统**只提供提交与查询，没有改价接口**，从接口层杜绝回写。报价单号 `QT+序列` 全局唯一。
- **含税未税并列展示，默认含税**：折后未税单价 = 挂牌价 × 档位比例（四舍五入到分）；行金额 = 折后单价 × 周数；税额 = 未税 × 版本税率（默认 6%，可在 `rate-card.default-tax-rate` 配）；含税 = 未税 + 税额。列表、详情、试算都是含税/未税/税额三列并列，含税列加粗为默认口径。
- **计价逻辑收到一处**：所有金额只由 `PricingEngine.price(提交日, 报价行)` 算出，报价提交与报价试算共用，接口层不算一分钱。
- **财务随查随拉的阶梯对照表**：`GET /api/rate-cards/years/{year}` 拉年度当前版本的档位+点位价全量；`.../tiers/export` 导出 CSV（带 BOM，含年份、版本、状态、税率、周数区间、档位比例、中文折扣）。

## 接口一览（均在 /api 下，除 health/login 外需登录）

| 动作 | 接口 |
|---|---|
| 登录/登出/当前人 | `POST /api/login`、`POST /api/logout`、`GET /api/me` |
| 点位挂牌/翻查/详情 | `POST /api/sites`、`GET /api/sites`、`GET /api/sites/{id}` |
| 上刊 | `POST /api/orders` |
| 落架 / 对冲 / 留痕更正 / 版本回退 | `POST /api/orders/{orderNo}/take-down`、`/offset`、`/edit`、`/rollback` |
| 单据翻查/详情/事件留底/根单链 | `GET /api/orders`、`/api/orders/{orderNo}`、`/events`、`/chain` |
| 外部回执 | `POST /api/receipts`、`GET /api/receipts/order/{orderNo}` |
| 月度档期周历/点位档期流水/底账版本 | `GET /api/ledger/calendar`、`/api/ledger/sites/{siteNo}/timeline`、`/api/ledger/versions` |
| 原图上传（指纹去重，自动转档） | `POST /api/assets/upload`（multipart：file、operatorName、remark） |
| 素材翻查 / 详情 / 档案全量 | `GET /api/assets?keyword=&status=`、`/api/assets/{id}`、`/api/assets/{id}/detail` |
| 当前转档规格 | `GET /api/assets/specs` |
| 转档状态轮询 / 重新转档 | `GET /api/assets/jobs/{jobId}`、`POST /api/assets/{id}/retry` |
| 产物下载 / 原图下载 | `GET /api/assets/files/{fileId}/download`、`/api/assets/{id}/original` |
| 经纬度建档 / 建档版本翻查 | `POST /api/sites/{siteNo}/geo`、`GET /api/sites/{siteNo}/geo/versions` |
| 半径捞点 / 整页导出 CSV | `GET /api/sites/geo/search?lat=&lng=&radiusKm=`、`GET /api/sites/geo/search/export?...` |
| 商圈圈界：圈定 / 翻查 / 重圈 | `POST /api/districts`、`GET /api/districts`、`PUT /api/districts/{districtName}` |
| 年度刊例：建档 / 翻查 / 版本翻查 / 另起新版 / 封存 | `POST /api/rate-cards`、`GET /api/rate-cards?year=`、`GET /api/rate-cards/{id}/versions`、`POST /api/rate-cards/{id}/versions`、`POST /api/rate-cards/{id}/close` |
| 刊例版本：草稿配置 / 详情 / 发布 / 作废 | `PUT /api/rate-cards/versions/{versionId}`、`GET /api/rate-cards/versions/{versionId}`、`POST .../publish`、`POST .../revoke` |
| 财务阶梯对照表 / 导出 CSV | `GET /api/rate-cards/years/{year}`、`GET /api/rate-cards/years/{year}/tiers/export` |
| 报价：试算 / 提交 / 详情 / 翻查 | `POST /api/quotes/preview`、`POST /api/quotes`、`GET /api/quotes/{quoteNo}`、`GET /api/quotes?customerName=&rateYear=&from=&to=` |

演示界面：启动后浏览器打开根路径 `/`（周例会演示用，九个页签覆盖全流程）。

## 启动

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。
4. 验证：`mvn test`（11 项底账口径测试 + 7 项素材转档测试 + 8 项坐标建档测试 + 13 项年度刊例计价测试，独立内存 H2）。

表结构交付副本：`docs/schema/evops.sql`（容器内根目录 `../../docs/schema` 不可写，交付副本置于工作区内），应与 `src/main/resources/schema.sql` 保持一致。
