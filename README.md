# 通知图标跟随主题（NotifIconTheme）

LSPosed 模块。把桌面图标包（ADW / Blueprint 那一套 `appfilter.xml` 图标包）里的图标
接到 `com.android.systemui`，让状态栏、通知中心、锁屏、锁屏岛不再各显示各的。

一加 13 / ColorOS 17（Android 17，SDK 37）实测通过。

## 安装

1. 装 APK（从 Actions 产物或 Release 里下）
   ```bash
   adb install -r app-release.apk
   ```
2. LSPosed 里启用，作用域勾 `系统界面 (com.android.systemui)`
3. 打开「通知图标跟随主题」，选图标包 → 保存
4. 想让新配置和新 hook 立刻生效：设置界面里点「重启系统界面」（只重启 SystemUI，不重启手机）

配置项改了以后 5 秒内自动生效，不点重启也能等到下一次图标重绘。

## 设置项

| 项 | 作用 |
| --- | --- |
| 启用模块 | 总开关 |
| 通知中心 | 通知卡片左侧 app 图标跟随主题 |
| 锁屏与锁屏岛 | 锁屏通知图标 + 锁屏胶囊卡片跟随主题 |
| 个人头像角标 | 有头像的通知（微信 / QQ 等）显示头像 + 主题图标角标 |
| 头像白名单 | 只有勾上的应用才做「头像 + 角标」，避免促销大图被当成头像 |
| 图标包 | 自动探测或手动指定 |
| 图标缩放 | 0.60–1.00，默认 0.90（图标包 PNG 自带留白时调这个） |

## Hook 表

| 类 / 方法 | 用途 |
| --- | --- |
| `StatusBarIconView.getIcon(StatusBarIcon)` | 状态栏小图标 |
| `OplusStatusBarIconViewEx.reloadIconFromContext(int, Context, StatusBarIcon)` | ColorOS 通知图标实际加载路径 |
| `StatusBarIconView.setIconColor / setStaticDrawableColor / setTintAlpha` | 拦掉系统染色，保住彩色图标 |
| `StatusBarIconView.onDarkChanged(ArrayList, float, int)` | 亮暗切换后回贴 |
| `NotificationHeaderViewWrapper.onContentUpdated(ExpandableNotificationRow)` | 通知卡片左侧图标入口 |
| `NotificationRowIconView.setImageIcon(Icon)` + `ImageView.setImageDrawable/setImageBitmap` | 异步加载晚到覆盖后回贴 |
| `HeadsUpTempViewCreator$init$1.onInflationFinished(NotificationEntry, NotifViewController)` | 横幅图标 |
| `CapsuleNotificationCardView.getRoundedIcon(NotificationIconData)` | 锁屏岛胶囊图标 |
| `LockScreenNotificationIconData.getIcon()` | 锁屏通知列表图标 |

头像 + 角标优先反射 ColorOS 自带的
`com.oplus.systemui.notification.row.wrapper.LayerDrawableWithAppIconKt.createBackgroundWithAppIcon(...)`，
签名变了就退回自绘（圆形裁头像 + 右下角角标 + 白描边）。

## 边界

- 图标包必须带 `assets/appfilter.xml`，组件名支持 `ComponentInfo{pkg/act}`、`pkg/act`、`pkg` 三种写法
- 图标包里没有的包名 → 保持系统原图标
- 系统自身图标（`com.android.systemui`）不替换
- 只 hook `com.android.systemui`，其他进程不受影响
- 桌面图标包换包后要重新在设置里选一次（自动模式下 5 秒内会重新探测）
- 头像白名单只列本机已安装的应用，未安装的包名留在配置里，装上了自动生效

## 构建

### GitHub Actions（推荐）

推 `main` 或提 PR 自动构建，产物在 Actions 的 Artifacts 里；打 `v*` tag 会自动发 Release 并附 APK。

```
Actions → build → Artifacts → NotifIconTheme-<sha>.zip
```

签名用的是仓库里 `keystore/spud.jks`（密码 `android`，alias `spud`），
所以每次 CI 出的 APK 签名一致，可以直接覆盖安装。
想换成自己的签名：把 keystore base64 后放进 secret `SPUD_KEYSTORE`，
再设 `SPUD_STORE_PASS` / `SPUD_KEY_ALIAS` / `SPUD_KEY_PASS`。

### 本地

```bash
export ANDROID_HOME=/path/to/android-sdk   # 需要 platforms;android-36 + build-tools;36.0.0
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

注意：**工程目录不要放在中文路径下**（aapt2 在中文路径下打不开目录），
虽然 `gradle.properties` 里加了 `android.overridePathCheck=true` 让 Gradle 放行，
aapt2 那一步仍然可能挂。CI 在 Linux 上是 ASCII 路径，没这个问题。

`keystore/spud.jks` 别删——换了签名就得卸载重装，LSPosed 里配好的作用域也会一起丢。

## 版本

| versionName | 内容 |
| --- | --- |
| 2.4 | 首页副标题与白名单页同口径（只数已安装） |
| 2.3 | 白名单只列本机已安装应用 |
| 2.2 | 头像白名单 + LRU 缓存 + MessagingStyle 兜底 |
| 2.1 | 深色主题、自适应图标、miuix 版式 |
| 2.0 | miuix 界面 + 头像角标 + 锁屏岛 |
