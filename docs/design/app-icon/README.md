# 代肝手账图标

靛蓝手账本图标，替代原「代肝记录」的鼠尾草绿猫耳手账图标。

- `launcher-indigo.svg`：正式图标可编辑矢量源，与 Android 前景层使用同一组路径。
- `launcher-indigo-preview.png`：方形与圆形两种形态的预览。

上一版鼠尾草绿猫耳图标的设计稿（`approved-presentation.png`、
`approved-sage-concept.png`、`launcher-sage-preview.png`、`launcher-sage.svg`）
保留在此仅作沿革参考，应用已不再使用。

对应资源：

- `app/src/main/res/drawable-v24/ic_launcher_foreground.xml`：Android 彩色前景。
- `app/src/main/res/drawable/ic_launcher_background.xml`：浅靛蓝背景。
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`：系统主题图标线稿。
- `app/src/main/res/mipmap-<density>/ic_launcher.png` 与 `ic_launcher_round.png`：旧版密度位图。

## 设计

主体是一本横线手账本：左侧三枚装订环表示可翻页的本子，页面上方两行横线表示账目条目，
右下角一枚金色对勾表示「今日已完成」。整体用靛蓝描边 + 奶油白纸面 + 金色对勾，
与原版的鼠尾草绿 / 猫耳造型明显区分。

配色：背景 `#DDE3F7`，背景圆形 `#C7D0EF`；封面 `#FFF9EC`；轮廓 `#2B3A67`；
横线 `#93A0D0`；对勾 `#E5A73C`。

前景已按 0.82 缩放居中并预留裁切空间，圆形与圆角方形由桌面启动器裁切。
应用 minSdk 为 26，所有支持的 Android 版本使用 adaptive icon。
未实现应用内切换图标功能，该功能留待后续。
