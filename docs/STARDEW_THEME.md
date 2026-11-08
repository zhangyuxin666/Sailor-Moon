# Stardew Valley theme

The home page uses locally stored official-site artwork: a full-screen sky, mountain layers, farm foreground and title logo. Clouds drift, stars twinkle and birds fly across the scene. Mouse movement adds subtle parallax. Opening videos and the previous four-item menu have been removed.

首页初始没有按钮。点击场景或按 Enter 后进入木牌标题菜单，仅有“登录”和“山谷手册”两个入口。登录入口打开完整的人物档案页面，不使用弹窗；左侧可以预览人物、切换发色与上衣，右侧选择管理者、参与者或游客。

标题菜单现在使用独立地址 `/login?screen=title`，人物页面为 `/login?screen=account`，手册为 `/login?screen=manual`。浏览器返回、前进与刷新会保留当前页面；标题页采用明亮天空和纯山景，不显示首页的农场前景。左上角品牌、右上角动效开关与页脚按参考界面布局，保留中文活动管家标题及两项主入口。

人物编辑使用原生 64×64 像素帧，支持两种体型、12 种发型、4 种服装、四方向与行走预览，以及肤色、发色、瞳色、上衣和裤装的预设/自定义配色。支持随机装扮、恢复默认，自动保存当前浏览器草稿及用户装扮。素材来自 Universal LPC，不是星露谷原版人物文件；逐项作者与许可记录于 `web/public/assets/character/CREDITS.csv` 和 `ATTRIBUTION.md`，源路径记录于 `sources.json`。画面采用运行时分层和保留明暗的调色，绘图代码位于 `ValleyCharacterEditor.tsx`。

管理者和参与者可切换已有账号登录与创建用户。新增 `POST /auth/register`，校验用户名、密码、角色与隐私协议，注册成功后建立会话。管理者仅拥有新建的独立组织；参与者不自动加入班级，原有组织与成员权限保持不变。已有班级账号直接登录即可。游客无需账号，浏览明确标注的静态示例，不访问真实业务数据。人物外观仅保存于当前浏览器，不是服务端账号资料。

首次使用保留 `/auth/bootstrap`。按 Esc 返回标题，按 P 暂停或恢复非表单场景动效；遵循系统减少动态效果设置。

## Files

- `web/src/components/StardewLoginScene.tsx`: splash, title menu, journal page and manual.
- `web/src/pages/Login.tsx`: character editor, login, registration and guest tour.
- `web/src/styles/stardew-home.css`: home scene and responsive motion styles.
- `web/src/styles/stardew.css`: shared theme and login form styles.
- `web/src/components/ValleyIcon.tsx`: custom pixel navigation icons.
- `web/public/assets/stardew/sources.json`: individual artwork and font URLs.

Assets use the existing public `/assets/**` route and load locally.

## 验证

`AuthRegistrationTest` 覆盖注册、角色限制、同名账号保护及协议校验。`AuthRegistrationDatabaseTest` 可通过 `-Dregistration.test.jdbc=jdbc:postgresql://127.0.0.1:55432/activity` 显式启用，使用真实 PostgreSQL 验证创建与登录，全程事务回滚；默认不访问数据库。

## 中文标题

首页标题使用 `web/public/assets/stardew/activity-manager-logo.png`，由内置 image_gen 依据原标题参考图生成，保留透明背景；这不是官方中文字体文件。

生成提示词：将参考图英文替换为准确的“活动管家”，上行“活动”、下行“管家”；沿用金黄色木纹像素字、琥珀色纹理、亮金高光、深棕描边、偏移立体阴影和少量绿色像素叶片。保持居中两行排版、硬边像素及透明背景，不加入木牌、场景或其他文字。

## Credits

Artwork: [Stardew Valley official website](https://www.stardewvalley.net/), owned by ConcernedApe.

Pixel font: [SolidZORO / Zpix v3.3.0](https://github.com/SolidZORO/zpix-pixel-font/releases/tag/v3.3.0). The author permits free personal and educational use.
