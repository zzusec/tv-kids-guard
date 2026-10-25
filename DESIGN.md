---
name: tv-kids-guard 家长管理
description: 原生 Android TV 的克制暗色家长控制台，以遥控器焦点和清晰状态为中心。
colors:
  panel-canvas: "#10151E"
  panel-surface: "#1B2432"
  panel-ink: "#E8EDF5"
  panel-muted: "#A5B1C4"
  panel-line: "#354154"
  panel-blue: "#315DCA"
  panel-blue-deep: "#254BA8"
  panel-blue-text: "#9AB9FF"
  panel-blue-soft: "#202F49"
  panel-green: "#90D9B3"
  panel-green-soft: "#17392F"
  panel-amber: "#F3C478"
  panel-amber-soft: "#332A1D"
  panel-red: "#FDA4AF"
  panel-red-soft: "#35222B"
  panel-danger-focus: "#9F2346"
  action-selected-text: "#FFFFFF"
  action-disabled-text: "#68778D"
typography:
  title: {fontFamily: "sans-serif-medium", fontSize: "28sp"}
  value: {fontFamily: "sans-serif-medium", fontSize: "26sp"}
  heading: {fontFamily: "sans-serif-medium", fontSize: "20sp"}
  action: {fontFamily: "sans-serif-medium", fontSize: "18sp"}
  message: {fontFamily: "Android default", fontSize: "18sp"}
  caption: {fontFamily: "sans-serif", fontSize: "14sp"}
  input: {fontFamily: "Android default", fontSize: "36sp", fontWeight: 700}
rounded:
  status: "6dp"
  action: "10dp"
  card: "14dp"
spacing:
  action-gap: "10dp"
  action-inline: "14dp"
  action-block: "12dp"
  card-padding: "18dp"
  overscan: "24dp"
  dialog-padding: "28dp"
components:
  card: {backgroundColor: "{colors.panel-surface}", textColor: "{colors.panel-ink}", rounded: "{rounded.card}", padding: "{spacing.card-padding}"}
  action: {backgroundColor: "{colors.panel-surface}", textColor: "{colors.panel-ink}", typography: "{typography.action}", rounded: "{rounded.action}", padding: "12dp 14dp"}
  action-focused: {backgroundColor: "{colors.panel-blue}", textColor: "{colors.action-selected-text}", rounded: "{rounded.action}"}
  danger: {backgroundColor: "{colors.panel-red-soft}", textColor: "{colors.panel-red}", rounded: "{rounded.action}"}
  danger-focused: {backgroundColor: "{colors.panel-danger-focus}", textColor: "{colors.action-selected-text}", rounded: "{rounded.action}"}
---

# Design System: tv-kids-guard

## Overview

**Creative North Star: "克制的暗色家长控制台"**

参考 Preline 的卡片层次、细边框与操作信息组织，使用 Android 原生 Java/XML 实现，不引入 Tailwind、WebView 或网页组件运行时。用户明确拒绝过亮、刺眼的浅色方案；管理面板采用暗石板底色与有限的蓝色焦点。

**Key Characteristics:** 低亮度大面积底色；清晰的数字与操作说明；遥控器焦点优先。范围为家长管理面板及其输入弹窗；2.5.7（versionCode 38）按数字键能力显示或隐藏原生屏幕数字盘，2.5.13（versionCode 44）起该判定是唯一依据、输入页不再提供切换按钮，2.5.14（versionCode 45）把判定不出来时的整屏数字盘换成一个数字键盘图标，验证门页和锁屏继续使用现有独立主题。

## Colors

Primary：`panel-blue` 标识当前焦点，`panel-blue-deep` 用于按压和焦点描边；`panel-blue-text` 用于快捷编号、加时和更新信息，`panel-blue-soft` 用于输入区。Neutral：`panel-canvas`、`panel-surface`、`panel-line` 分离背景、容器与边界；`panel-ink`、`panel-muted` 分配正文和说明。

状态色成对使用：绿色文字/深绿底表示开启，琥珀色文字/深琥珀底提示注意；红色文字/深红底只用于关闭防护等危险操作。停用状态用中性色；焦点和按压时，按钮标题、编号、说明全部切换为 `action-selected-text`，不能只改标题。

## Typography

使用 Android 自带 `sans-serif`、`sans-serif-medium` 及系统中文回退，不打包网页字体。层级对应 frontmatter 的页面标题、统计值、区块标题、操作、说明；输入弹窗标题沿用统计值字号，数字显示采用 `input`。按钮说明使用 caption，标题与说明额外行距为 4dp；标题不强制全大写。

## Layout

原生 `FrameLayout` + `ScrollView` + `LinearLayout`，无固定画布截图。根容器左右及底部留 24dp，顶部 20dp；滚动内容居中，宽度为 `min(900dp, 屏宽 - 72dp)`。横屏且屏宽至少 600dp 时，概览与操作权重为 0.7:1.3、间距 24dp；其余情况上下堆叠、间距 20dp。

操作始终是两列真实 Button，同行均分宽度，行列间距 10dp；每个按钮至少高 72dp，允许文字换行增高。概览内部留白使用 card-padding。

`PinDialog` 显示数字盘且屏宽至少 600dp 时采用文字左、数字盘右的双栏布局，弹窗宽度为 `min(760dp, 屏宽 - 48dp)`；窄于 600dp 时上下排列，宽度为 `min(560dp, 屏宽 - 48dp)`，左右各留 24dp，内容可滚动。隐藏数字盘时恢复 `min(560dp, 屏宽 - 48dp)` 的单栏，只保留实体输入区，不保留数字盘空位，也不留 2.5.13 删掉的切换按钮位置；数字键判定不出来时，这一栏在输入区下方多出一个居中的数字键盘图标。验证门页（Gate）与锁屏（Lock）显示数字盘时沿用原最大 900dp 布局，隐藏时居中、最大 560dp，图标同样居中挂在文案列内。判定变化后重排输入布局，焦点须留在可见控件上。

新增数字盘只进入验证、锁屏和输入区域，不改变家长面板原有卡片对齐、列宽及间距。数字、删除、确认均使用原生可聚焦按钮；方向键负责在数字盘内移动焦点，OK 激活当前按钮，不依赖触屏或系统输入法。

数字盘为 3 列 4 行，依次为 `1 2 3`、`4 5 6`、`7 8 9`、`删除 0 确认`；按键最小尺寸 48dp，行列间距 8dp。复用主面板的原生暗色按钮 style 与焦点样式，不另起一套视觉语言；数字盘按键尺寸独立于面板操作按钮的最小高度。

数字键盘图标（`keypad_request`，2.5.14 起）是一枚原生 `Button`，最小 64×48dp，沿用数字盘按键的 `parent_button_bg` 与关闭 `StateListAnimator` 的处理，无文字，`contentDescription` 为「屏幕数字键盘」。图形是 26dp 的 3×3 点阵（`RemoteKeypad.KeypadGlyph`，自绘 `Drawable`，点半径为格子的 0.19），静态用 `panel_ink`、焦点与按压时点亮为白色，与盘内按钮的焦点语言一致；它只表达「这里可以展开数字键盘」，不带标题、不带说明文字。图标展开数字盘后自身收起，数字盘退出或换屏时图标回到原位。

## Elevation & Depth

面板卡片和操作按钮不依赖阴影，用底色差与细描边建立层次。按钮关闭系统 `StateListAnimator`，不新增缩放、发光或焦点动效；DPAD 焦点用明确填色表达。

## Shapes

卡片、操作/输入/提示、状态标签分别使用 frontmatter 的 card、action、status 圆角。卡片与普通按钮静态描边 1dp；普通按钮焦点描边 2dp。电视小图为项目内原生 vector 几何图形，不引用第三方商标资产。

## Components

- 概览只常驻「今天已看」「每天可看」和防护状态；临时加时只在真实生效时显示，2.5.12 起「今天不限时」这一行连同状态一起删除。真实权限缺失、服务错误与更新失败仍须可见，不为了简洁隐藏问题。设置保护警告须区分无障碍权限未开启、服务未连接和查询失败，连接恢复后移除相应警告，不把权限已开启当作服务运行。
- 操作保留两位快捷码与 DPAD/OK 共用执行路径；按原生 `Button` 处理焦点、按压和可用性，不改成只能点击的卡片。隐藏操作不参与焦点导航，防护切换后焦点不能丢失。
- 普通按钮按压使用深蓝，焦点使用蓝色；危险按钮焦点/按压均使用深红。禁用状态使用 canvas 底色与 disabled-text，不可误呈现为已选中。
- 输入使用 `TextView` 展示数字，两种输入方式均不弹 IME。非虚拟 `InputDevice` 的普通数字键与小键盘数字键并集为 0–9 每个数字都提供支持，或本机已实测到过真实数字键，就只显示实体输入区，数字盘和图标都不出现；并集缺一字或检测结果未知时不按品牌硬编码，也不自动摊开数字盘，而是给一枚数字键盘图标，方向键选中、OK 才展开盘。展开后方向键选数字、OK 输入，「删除」「确认」均有屏幕按钮。
- 2.5.13 起输入页不再有任何模式切换按钮：早期版本的「使用屏幕数字盘」「使用遥控器数字键」原生按钮、其焦点位与 `show_keypad`/`pad_pinned` 存储全部删除，页面只剩硬件判定出来的那一种输入方式。2.5.14 起唯一新增的控件是那枚图标本身，它不是模式开关：不改变判定，只在判定不出来时提供进入数字盘的一步。
- Gate 与 `PinDialog` 默认聚焦输入区：数字键已确认时直接按遥控器数字键输入；未确认时从输入区按方向下或右选中数字键盘图标，OK 展开数字盘并聚焦第一个数字，从盘顶部按方向上回到输入区（图标已收起）；锁屏把上方的焦点目标设成「家长管理」按钮。
- 实体数字键直接输入的路径保留，输入后左键删除、OK 直接确认；数字盘显示时左键是焦点导航，不与删除混用。返回取消当前输入。6 位家长 PIN 始终遮罩，失败冷却和授权检查不变；时长显示数字，不把时长也遮罩。
- Lock 默认聚焦「家长管理」按钮，OK 进入家长验证；从该按钮出发，数字键未确认时方向下或右选中数字键盘图标、OK 展开数字盘，已确认时屏幕上没有数字盘也没有图标，直接按遥控器数字键即可。输入家长密码并确认只打开家长管理面板，锁屏仍在面板之下，离开面板即恢复；选中图标、展开数字盘或返回取消输入均不解除锁定，进入面板也不送出一天的时长。提示文字相应说明「输入 6 位家长密码进入家长面板」，不写「解除限时」。
- 数字盘被禁用时（失败冷却、服务正在恢复）图标一起隐藏，焦点路径回到禁用前的入口，不出现「能选中但不能用」的控件；恢复可用后图标随数字盘一起回来。
- 语义必须区分：`00 关闭面板` 不停防护；`22 临时加时长` 只加今天；`44 关闭防护` 保留设置且确认后执行；`55 开启防卸载` 打开系统确认页启用设备管理员；`99 启用防护` 恢复管控。`55` 与 `99` 互斥，共用最后一格，面板保持四行以适配 1080p 电视。
- 2.5.15 起面板的两条退路（返回键与 `00 关闭面板`）把遥控器交给电视机自己的桌面：桌面这一屏不弹锁屏、不盖黑屏，`33 立即锁定` 与验证页返回仍回到锁屏。这份豁免由无障碍设置拦截观察前台窗口，桌面无豁免时照常锁定；离开桌面、待机或拦截服务断开即刻失效，锁屏与黑屏一起恢复。

## Do's and Don'ts

- **Do** 以 [colors.xml](app/src/main/res/values/colors.xml)、[styles.xml](app/src/main/res/values/styles.xml)、[activity_main.xml](app/src/main/res/layout/activity_main.xml)、[原生 drawables](app/src/main/res/drawable/) 与 `MainActivity` / `RemoteKeypad` / `PinDialog` 的实际实现为事实源。
- **Do** 保留远距离可读性、电视安全边距、显著焦点与错误可见性；更改后验证原生 DPAD 和不同尺寸布局。
- **Don't** 把深色面板推广覆盖验证门页或锁屏，不用浅色整屏底、装饰图表、无数据的进度图或营销插图替代真实状态。
- **Don't** 把 Preline 参考理解为移植网页运行时；官方参考仅为 [Card](https://preline.co/docs/components/card.html) 与 [Buttons](https://preline.co/docs/components/buttons.html) 的视觉组织方式。
