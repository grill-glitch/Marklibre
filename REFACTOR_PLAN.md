# Marklibre 重构计划 (REFACTOR_PLAN)

> 依据 `CODE_ARCHAEOLOGY.md` 的证据链。原则:一次只解决一个问题、保持行为、
> 优先提取已有正确实现(canonical)、先证明后抽象。按风险从低到高排序。
> 每个项目单独一个 commit,每步以 `./gradlew :app:assembleDebug` 验证。

---

## R1. 删除死代码(零行为影响)

- **问题**: 一批无人引用的代码/资源增加维护负担,误导读者以为有用途。
- **证据**: 考古报告 §8,git grep 全仓零引用:
  `DrawingCanvasView.isStickerMode()`、`AnnotateActivity.writePng()`、
  attrs.xml 的 `tool_name` 属性(含 toolbar.xml 的 `app:tool_name` 使用处)、
  `drawable/ic_close.xml`、dimens `ink_text_padding`/`ink_rect_handle_size`、
  colors `crop_dim`/`crop_handle`、strings 8 条(bold_font~bubbly_font、
  tool_selected、metadata_location_removed)。
- **建议**: 全部删除。
- **风险**: 无(编译期会立即暴露误删)。
- **验证**: assembleDebug 通过 + 无 lint 新增错误。

## R2. DrawingCanvasView 文本测量收敛(最大重复面,零风险)

- **问题**: `textPaint+measureText+descent-ascent` 在单文件内重复 13 处(§6.1);
  旋转中心 `x+w/2, y+h/2` 重复 7 处(§6.2)。
- **证据**: DrawingCanvasView.kt L220/L244/L299/L366/L391/L562/L917/L935/L945/L957/L968/L986/L1022。
- **为什么重复**: 同一"文本 em-box 尺寸"概念被各处就地重算。
- **建议**: 提取私有函数
  `private fun textMetrics(el: InkElement.Text): TextMetrics(w: Float, h: Float)`(或带 Paint),
  保留各调用点的 pad 差异(12dp 命中 vs 6dp 视觉,§7.2 已论证不可合并)。
  旋转中心改为 `metrics.center()` 或 `cx/cy` 字段。
- **预期收益**: 13 处 → 1 处实现;改字号/字距只动一处。
- **风险**: 低(纯等价替换,无行为变化)。
- **验证**: assembleDebug + diff 审阅 + 真机抽查文本工具各手势。

## R3. DrawingCanvasView 矩阵缩放因子提取

- **问题**: `hypot(MSCALE_X, MSKEW_Y)` 提取 3 处(§6.4)。
- **建议**: `private fun Matrix.scale(): Float`,但**保留语义差异**:
  `gestureScale()` 与 `updateCanvasGesture` 用 `coerceAtLeast(1e-4f)`(防除零),
  `endCanvasGesture` 不用(只要实际值)。提取公共"裸 scale",clamp 留在调用点。
- **风险**: 低。
- **验证**: assembleDebug + 双指缩放真机抽查(0.2x~8x)。

## R4. 压栈样板收敛

- **问题**: `undoStack.addLast(op); redoStack.clear()` 重复 7 处(§6.6)。
- **建议**: `private fun pushUndo(op: CanvasOp) { undoStack.addLast(op); redoStack.clear() }`。
  调用点保留各自的 `renderInk()/notifyUndo()` 尾缀。
- **风险**: 低。
- **验证**: assembleDebug + 每类操作(笔画/文本/裁剪/旋转/拖拽/删除)撤销重做抽查。

## R5. applyCrop / rotateImage 共用"拍平替换"流程

- **问题**: 两方法骨架完全相同(§3.3/§6.7):拍平→建新位图→push ReplaceImage→
  清元素→重算矩阵→重绘→通知。
- **建议**: 提取
  `private fun replaceSourceWith(newSource: Bitmap)`(含 ReplaceImage 压栈),
  applyCrop/rotateImage 各自只保留"生成新位图"的部分。
- **风险**: 中低(撤销语义必须逐字保持:ImageState 的旧元素列表是 `ArrayList(elements)` 拷贝)。
- **验证**: assembleDebug + 裁剪/旋转后 undo/redo 结果与之前一致(比对截图像素)。

## R6. 亮度计算收敛(跨文件)

- **问题**: `0.299r+0.587g+0.114b` 两处(§6.5),用于选黑/白图标色。
- **建议**: 在 InkModel.kt 加 `fun Int.luminance(): Float`(纯函数,可单测),
  DrawingCanvasView 与 ToolbarFragment 复用之。
- **风险**: 低。
- **验证**: assembleDebug + 单测(可加)。

## R7. ToolbarFragment 宽度↔滑条映射收敛

- **问题**: dp↔progress 映射实现 5+ 处(§6.8),且 setActiveTool 分支里的
  coerce 写法与 setter 不一致(一处 coerceIn(0,14) 硬编码,一处算 max)。
- **建议**: 提取
  `private fun currentWidth(): Float`(由 currentTool + slider.progress 算)与
  `private fun setSliderFor(tool: InkTool)`(设置 max/progress)。
  onViewCreated 初始化、slider 监听器、setActiveTool、两个 setter、updateWidthPreview 全部改用它。
- **风险**: 中(UI 行为:滑条范围/初值必须逐值一致——用 setter 前后对比值验证)。
- **验证**: assembleDebug + 真机:切笔/高亮时滑条范围(0..14 / 0..24)与初值(8/24)不变,
  拖动后数值与旧实现一致。

## R8. JPEG 段遍历统一(独立双实现收敛)

- **问题**: readJpegApp1 与 stripJpegExif 各自实现 JPEG marker 遍历(§6.9),
  由两个 commit 独立写出;现在同一文件、语义重叠。
- **建议**: 提取纯函数 `Jpeg.walkSegments(data, onSegment)` 或在 JpegCodec object 中提供
  `readApp1Exif(data)` 与 `stripExif(data)`,内部共享一个 marker 遍历器。
  为锁定行为,新增最小 JVM 单测(junit,test-only 依赖)覆盖:含 EXIF 的 JPEG、
  无 EXIF、SOS 后数据保留、非 JPEG 输入。
- **风险**: 中(字节级解析)。**先写测试锁定旧行为,再重构**。
- **验证**: `./gradlew test` + assembleDebug。

## R9. 色板按钮收集与互斥选中收敛(3 文件)

- **问题**: ColorButton 收集 + 互斥 checked 三份实现(§6.10)。
- **建议**: 加扩展 `ViewGroup.colorButtons(): List<ColorButton>`(递归,替代
  ToolbarFragment 手写 walk)与 `List<ColorButton>.checkOnly(color: Int)`。
  ToolbarFragment/TextEditorFragment/StickerActivity 复用。
- **风险**: 低中(递归收集与现行为等价:三个布局里 ColorButton 都不嵌套于其他 ViewGroup
  之外;Sticker/TextEditor 目前只收直接子节点,递归结果相同,因它们无嵌套)。
- **验证**: assembleDebug + 三处色板点选行为抽查。

## R10. 工具高亮着色决策收敛(可选,低优先)

- **问题**: "画笔→墨水色/激活→onPrimary/未激活→中性"三路判定两份(§6.11)。
- **建议**: 提取 `fun toolIconTint(active: Boolean, isInkTool: Boolean, inkColor: Int,
  activeTint: Int, neutral: Int): Int` 放 InkModel.kt;ToolbarFragment.animateIconTint/
  applyButtonState 与 StickerActivity.highlight 复用之。UI 机制(滑动高亮/ring)不合并。
- **风险**: 低(纯函数替换)。
- **验证**: assembleDebug + 两处工具切换图标色抽查。

---

## 明确不做的(避免过度重构)

| 项 | 原因 |
|---|---|
| 统一 canvas.tool/color/width 与 toolbar 镜像状态 | 架构级改动,两源同步是当前设计;收益 < 风险 |
| 合并 StickerActivity 与主编辑器的工具条 UI | 布局/交互机制根本不同,合并=改 UI,违反保持行为 |
| 提取"后台任务+进度"通用骨架 | 6 处成功分支各不相同,抽象后只剩失败 toast 相同,得不偿失 |
| 拆分 DrawingCanvasView(如拆出 GestureController/UndoManager) | 拆分本身不是目标;本次先收敛重复,拆分留待后续评估 |
| 引入 ViewModel/DI/Flow/Compose 等 | 考古无实际问题支撑,明确禁止(任务 §18) |
| 统一 eraser/pen 预览路径 | 语义不同(§7.2),合并会复现历史 alpha bug |

## 验证总纲

1. 每步:`./gradlew :app:assembleDebug`。
2. R8 加 `./gradlew test`(新增最小 JVM 单测)。
3. 全部完成后:完整 `./gradlew build` + `git log` 逐个 commit 审阅。
4. 真机(用户提供):文本工具全套手势、裁剪/旋转 undo、双指缩放、色板交互。
