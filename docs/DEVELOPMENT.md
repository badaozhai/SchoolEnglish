# 开发与发布

## 项目结构

应用只有一个 Android module：`app/`。

- `MainActivity.kt`：主界面、媒体列表、播放器、字幕状态和悬浮字幕入口。
- `MediaRepository.kt`：内置媒体安装、RAR 解压和媒体扫描。
- `TranscriptDatabase.kt`：PDF 解析、句子拆分和 SQLite 持久化。
- `TranscriptTimeline.kt`：按句子词数估算字幕时间轴。
- `TranscriptViewModel.kt`：异步加载字幕，并丢弃过期查询结果。
- `CaptionOverlayService.kt`：悬浮字幕前台服务和可拖动窗口。
- `app/src/test/`：字幕拆分和时间轴的 JVM 单元测试。

## 本地验证

在项目根目录执行：

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
git diff --check
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 小米真机冒烟测试

先确认设备序列号：

```bash
adb devices -l
```

安装调试包。MIUI 设备使用 `--no-streaming` 更稳定：

```bash
adb -s <device-serial> install -r --no-streaming app/build/outputs/apk/debug/app-debug.apk
```

建议按以下顺序检查：

1. 启动应用，确认内置音频列表可见。
2. 点击一条 MP3，确认播放区出现，默认按钮显示“单曲循环”。
3. 连续点击同一条 MP3，确认只有一个音频继续播放，字幕不消失。
4. 快速切换两到三条 MP3，确认当前标题、音频和字幕属于最后点击的文件。
5. 点击“悬浮字幕”，在系统设置中允许悬浮窗后返回应用。
6. 播放中按 Home，确认桌面上出现半透明悬浮字幕，并且句子会随时间变化。
7. 返回应用，确认悬浮字幕收起且播放区仍可操作。

检查当前活跃音轨数量：

```bash
adb -s <device-serial> shell dumpsys audio \\
  | rg "AudioPlaybackConfiguration.*u/pid:.*state:started"
```

正常情况下，本应用只应有一个 `state:started` 的 `AudioTrack`。

## 悬浮窗权限

应用会通过系统设置页请求 `SYSTEM_ALERT_WINDOW`。测试设备也可以使用 ADB 临时授权：

```bash
adb -s <device-serial> shell appops set com.schoolenglish.listen \\
  android:system_alert_window allow
```

正式使用时仍建议在系统设置中手动确认“显示在其他应用上层”和后台运行策略。

## 提交前检查

- 不要提交 `.gradle/`、`.kotlin/`、`build/`、`app/build/` 或本地 IDE 配置。
- 保留 `app/src/main/assets/` 中的课程音频和录音稿，它们是应用的内置内容。
- 修改录音稿解析规则后，更新或新增 `app/src/test/` 测试。
- 修改播放、字幕或生命周期逻辑后，至少完成一次真机冒烟测试。
- 提交前运行 `git diff --check`，确认没有空白错误。
