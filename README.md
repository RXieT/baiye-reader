# 白页

首先说明是vibecoding项目，下述readme由ai填写，因为只是用于满足个人所需所以我不会补充readme，也不会长时间更新。

Android 轻小说阅读器。支持文库自动翻页加载、书架、章节缓存和 EPUB 导入。使用白底界面与 Miuix OS4 玻璃底栏。阅读页提供时间、电量、章节页码，以及目录、排版、背景和阅读控制分级菜单。

需要 Android 13 及以上、较新的 Android System WebView。安装包见 [Actions](https://github.com/RXieT/baiye-reader/actions/workflows/android.yml) 的 `Baiye-Android`，其中 `Baiye.apk` 可直接安装。

## 参考/使用的开源项目

- [Miuix](https://github.com/compose-miuix-ui/miuix)：原生界面，固定 OS4 预览提交 `ea1b935`，Apache-2.0。
- [foliate-js](https://github.com/johnfactotum/foliate-js)：Foliate 与 Readest 使用的阅读内核，固定提交 `78914ae`，MIT。
- [zip.js](https://github.com/gildas-lormeau/zip.js)：EPUB 解压，BSD-3-Clause。
- [OkHttp](https://github.com/square/okhttp)、[Coil](https://github.com/coil-kt/coil)、[AndroidX](https://github.com/androidx/androidx)、[Kotlin](https://github.com/JetBrains/kotlin)：Apache-2.0。
- [Jsoup](https://github.com/jhy/jsoup)：文档解析，MIT。

文库通信行为参考用户提供的轻小说文库 APK 与 [MewX 的原项目](https://github.com/MewX/light-novel-library_Wenku8_Android)，独立重写，未分发原 APK、原应用源码或 native 库。构建配置不含在源码中。许可证在 `licenses`，并随应用打包。

## 构建

JDK 21 与 Android SDK 37：

```sh
bash scripts/build-miuix.sh
./gradlew :app:assembleDebug
```

中继构建配置使用环境变量 `WENKU_RELAY_BUILD_KEY`，由仓库 Actions Secret 提供；空配置改用网页来源，可能受源站验证限制。Release 签名也由 Actions Secret 提供，不在仓库中。

书架和进度仅保存在设备内，卸载会删除。在线章节按需缓存；目录、章节顺序改变时优先按章节编号恢复。内容来自第三方站点，不随源码或安装包分发，也不保证源站持续可用。

验证记录见 [docs/testing.md](docs/testing.md)。
