# Architecture

## 模块入口

`MainHook` 是 LSPosed 入口。模块注入 `tv.danmaku.bili` 后：

1. 初始化统一日志 `ModuleLog`
2. 确认当前进程是否需要安装评论 Hook
3. 调用 `CommentHook.init(...)`

## 评论按钮链路

`CommentHook` 负责在 `CommentListAdapter.onBindViewHolder(...)` 后读取当前 `ViewHolder`：

1. 识别评论相关 holder
2. 从 `ViewHolder`、adapter 数据项或嵌套对象图里提取 `CommentItem`
3. 提取 `mid / itemId / rootId / parentId`
4. 判断当前场景是主评论列表、评论详情还是弹窗
5. 把归一化后的 `Binding` 交给 `CommentBlockFeature`

`CommentBlockFeature` 负责纯 UI 层逻辑：

1. 判断当前项是否应该显示按钮
2. 清理 RecyclerView 复用残留
3. 选择主评论头部宿主并定位按钮
4. 做同评论项去重，避免预览灰框和主评论重复出现按钮
5. 绑定点击事件并切换已拉黑状态

## 拉黑执行链路

拉黑执行完全复用 B 站内部菜单回调：

1. `CommentHook` 在评论 bind 时提取并绑定当前评论 `mid`
2. 同时 hook `CommentMoreMenuItemHolder`，只缓存真正的“拉黑/黑名单/block”菜单项回调
3. 按钮点击时由 `CommentBlockFeature` 按 `mid` 懒查缓存，并调用对应的原生菜单回调
4. 由 B 站内部确认弹窗和后续关系处理链路完成真正的拉黑

## 日志

`ModuleLog` 将日志同时输出到：

- Android Logcat
- `XposedBridge.log`
- 模块文件日志
- 可选 Toast

这保证了 LSPosed 场景下即使 logcat 噪声很大，也能保留稳定证据。
