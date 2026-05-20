# Debugging

## 常用日志命令

抓取模块相关日志：

```powershell
adb -s 192.168.2.178:41879 logcat -d -v time | Select-String "BiliBlocker|LSPosedFramework"
```

直接读取模块文件日志：

```powershell
adb -s 192.168.2.178:41879 shell su -c "tail -n 300 /storage/emulated/0/Android/data/tv.danmaku.bili/cache/bili_blocker_log.txt"
```

## Frida 探针

仓库内置两个探针脚本：

- `tools/frida/frida-java-ok.js`
  - 最小 Java 可用性探针
- `tools/frida/frida-comment-probe.js`
  - 评论 `ViewHolder` / adapter 数据探针

示例：

```powershell
frida -U -f tv.danmaku.bili -l .\tools\frida\frida-java-ok.js
frida -U -f tv.danmaku.bili -l .\tools\frida\frida-comment-probe.js
```

## 典型排查顺序

1. 先确认模块加载日志是否存在
2. 再看 `CommentListAdapter.onBindViewHolder` 是否命中
3. 再确认 `CommentItem`、`mid`、`scene` 是否提取成功
4. 最后检查按钮宿主、位置和去重逻辑

## 当前基线

- B 站：`tv.danmaku.bili 8.93.0`
- 设备：Android 14 / ColorOS / LSPosed
- 作用域：仅 `tv.danmaku.bili`
