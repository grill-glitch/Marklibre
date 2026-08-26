# Marklibre 代码考古报告 (CODE ARCHAEOLOGY)

> 基于 `git@341812e` (HEAD, 60 commits) 的完整源码考古。
> 原则:先考古,后判断;先证明,后抽象。所有结论均附源码证据位置。

---

## 1. 项目概况与血缘

- **仓库**: grill-glitch/Marklibre,60 个 commit,单分支 main,标签 v1.0.0~v1.2.2。
- **身份**: Google Markup(截图标注编辑器)的 clean-room 复刻,零运行时依赖(仅 androidx + material)。
- **血缘**: 初始 commit(04e93f6, 2026-07-31)包名为 `com.google.android.markup`,与作者另一个仓库
  `grill-glitch/Markup-libre` 同源(本地 ~/Markup-libre 现为空仓库,无提交);commit 850f65c 更名为
  `org.librelab.marklibre`。**本项目是从 Markup-libre 导入后重命名的延续**,不是全新项目。
- **规模**: 3280 行 Kotlin(10 个文件),无任何测试目录,无 test 依赖。
- **构建**: Kotlin 2.4.0 / AGP 9.3.1 / Gradle 9.6 wrapper / compileSdk 36 / minSdk 35 / versionCode 236。

## 2. 实际架构(观察所得,非预设)

```
AnnotateActivity (834 行)          ← 主编辑器编排者
├── DrawingCanvasView (1129 行)    ← 整个墨水引擎 + 视图 + 手势 + 撤销 + 导出
│     ├── source Bitmap (可空;空 = sticker 模式)
│     ├── elements: ArrayList<InkElement> (Stroke / Text)
│     ├── undoStack / redoStack: ArrayDeque<CanvasOp>
│     ├── inkLayer Bitmap (视图尺寸,缓存已提交墨水)
│     ├── matrix / inverse (fit 变换,视图↔图像坐标)
│     └── gestureMatrix (双指手势临时变换,松手时折叠进 matrix 与元素坐标)
├── ToolbarFragment (648 行)       ← 底部工具条:工具按钮/色板/宽度滑条/内联调色板
├── TextEditorFragment (167 行)    ← 全屏文本输入(字体/颜色)
├── CropOverlayView (197 行)       ← 裁剪矩形 + 8 手柄 + 变暗遮罩(视图坐标)
├── trashDrop / cropActions / progress ... (布局内 View)
└── sticker/StickerActivity (112 行) ← 独立贴纸编辑器(复用 DrawingCanvasView + ColorButton)
```

**关键观察**:
- 没有任何 Repository/ViewModel/DI/状态管理库。状态 = View 字段 + 回调 + SharedPreferences。
- `DrawingCanvasView` 承担了 8 种职责:渲染、笔画、文本元素、选择/拖拽/缩放/旋转手势、
  双指缩放、撤销/重做、裁剪/旋转图像、全分辨率导出、取色。**它从一开始(初始 commit 559 行)
  就是一个大文件,60 个 commit 从未拆过**,是渐进式加功能堆出来的。
- 唯一的数据模型文件 `InkModel.kt`(57 行):`InkTool` / `StrokeStyle` / `InkElement`(sealed:
  Stroke, Text) / `ImageState` / `CanvasOp`(sealed: Add, Remove, EditText, ReplaceImage) /
  `FONT_NAMES` / `fontTypeface()` / `Context.themeColor()` 扩展。
- `StickerActivity` 是**刻意简化的第二编辑器**:同一 DrawingCanvasView,但自带一套
  ImageButton 工具条 + 色板行,不共享 ToolbarFragment(布局完全不同)。

## 3. 关键数据流(用户操作 → 结果)

### 3.1 笔画(pen/highlighter/eraser)
```
ACTION_DOWN → startStroke(activePath 开始)
ACTION_MOVE → continueStroke(quadTo 平滑)
ACTION_UP   → endStroke: 生成 InkElement.Stroke → elements.add
             → undoStack.addLast(CanvasOp.Add) → redoStack.clear
             → renderInk()(重绘 inkLayer 位图)→ notifyUndo()
渲染:onDraw 画 source(matrix)→ 若 activeStyle==ERASER 先画进 inkLayer(CLEAR)→
     blit inkLayer → 非 eraser 的 activePath 直接画主画布(避免高亮 alpha 叠加变实)
```
> 历史原因(commit fa5b2e7, 1ff00d3):eraser 预览必须合成到 inkLayer 上(CLEAR 只清墨水),
> pen/highlighter 预览必须画主画布(否则 inkLayer 每帧重合成把半透明叠成不透明)。
> **这是"看起来相似但语义不同,绝不能合并"的典型**——代码中已有注释说明。

### 3.2 文本元素
```
TEXT 工具: ACTION_DOWN → textDown()
  ├─ 已选文本: 旋转旋钮(26dp 容差)→ startRotation
  │           角点(26dp 容差)→ startCornerResize(对角锚定)
  │           box 内 → draggingText
  └─ 未选中: hitTestText(逆序,z 序)→ selectText
TEXT 工具 ACTION_UP 未命中任何文本 → onRequestNewText(x,y) → TextEditorFragment.showForNew
拖拽中双指 → 文本两指缩放(scaleSelectedText)
编辑:TextEditorFragment.commit → canvas.updateText(old, text, color, font)
  → elements[idx] = 新元素 → undoStack.push(EditText(old,new))
拖到垃圾箱 → deleteDraggedText: 先恢复 dragStartState(拖动前位置)再 Remove(index,el)
  → 撤销能回到拖动前位置(commit e1f4250 的历史修复)
```

### 3.3 裁剪 / 旋转(整图破坏性操作)
```
CropOverlayView(视图坐标 rect)→ applyCrop(rect):
  flattenFullRes()(源像素尺寸 = 图像+墨水拍平)→ inverse.mapRect 转图像坐标
  → Bitmap.createBitmap 裁剪 → undoStack.push(ReplaceImage(ImageState(旧), ImageState(新)))
  → source=新, elements.clear() → computeMatrix → renderInk → notifyUndo
rotateImage: 同样的拍平 → Matrix.postRotate(90) → ReplaceImage(同上)
```
> **applyCrop 与 rotateImage 的结构完全一致**(拍平→建新位图→ReplaceImage→清空→重算→重绘→通知),
> 是同一流程的两份实现(证据: DrawingCanvasView.kt L410-466)。
> 注意:裁剪/旋转把矢量墨水**永久栅格化**,撤销靠 ImageState 全量快照恢复——这就是"历史"的一部分,
> 也是 ImageState 存在的唯一原因。

### 3.4 双指缩放/平移(画笔工具)
```
POINTER_DOWN(≥2 指)→ startCanvasGesture: gestureMatrix 归零
MOVE → updateCanvasGesture: span/focus 增量 → postScale(以 focus 为锚) + postTranslate
  缩放 clamp 到 [0.2x, 8x](相对基础 fit 比例)
POINTER_UP → endCanvasGesture:
  matrix.postConcat(gestureMatrix)(注意注释:preConcat 是源空间,错;postConcat 才对,commit 91769b3 修过)
  → 遍历 elements: Stroke.path.transform(gestureMatrix), width *= s;
    Text.x/y/size *= s —— 把手势折叠进元素坐标
```
> 折叠时缩放因子 `s` 用 `hypot(v[MSCALE_X], v[MSKEW_Y])` 提取,该提取逻辑在文件内出现 3 次
> (L525 gestureScale、L710 updateCanvasGesture、L735 endCanvasGesture)。

### 3.5 保存/分享/复制(AnnotateActivity)
```
doSave / doShare / doCopy:
  commitPendingCrop()(HEAD 341812e 新增:裁剪未确认时先提交,保证 WYSIWYG)
  → progress 显示 → 工作线程 flattenFullRes() → 写 MediaStore(doSave/doCopy)
    或 cache 文件 + FileProvider(doShare)→ UI 线程收尾
格式跟随源图(sourceFormat(): PNG/WebP/JPEG);JPEG 默认保留源 EXIF(写 APP1 拼接)
```
> 三个方法共享"进度遮罩 + 工作线程 + flatten + 失败 toast"骨架,但成功分支不同
> (setResult / chooser / clipboard)。`loadImage`、`saveSticker`、metadata strip 按钮也是同骨架。

## 4. 状态与状态源

### 4.1 同一概念多份状态(镜像状态)
| 概念 | 引擎(canvas) | UI(toolbar) | 同步方式 |
|---|---|---|---|
| 当前工具 | `canvas.tool` | `ToolbarFragment.currentTool` | onToolSelected 回调 |
| 当前颜色 | `canvas.color` | `ToolbarFragment.currentInkColor` | onColorSelected 回调 |
| 笔宽 | `canvas.penWidthDp` | `currentPenWidth` + slider.progress | onWidthSelected |
| 高亮宽 | `canvas.highlighterWidthDp` | `currentHighlighterWidth` + slider.progress | onWidthSelected |

两个来源各自为真,靠 AnnotateActivity 中介回调保持同步。**这是"状态重复"(§4.4)**。
统一成单一来源需要动架构(把 toolbar 状态收进 canvas 或反之),风险高、收益主要是整洁性,
本计划**不做**,仅记录。

### 4.2 无状态丢失隐患的观察
- 撤销栈非空 ⇔ 有未保存编辑(`hasEdits()`),BACK 弹"放弃修改"。undo 到栈空后 hasEdits()=false,
  但 redoStack 可能非空——此时 BACK 不询问直接退出。这是现有行为(保存结果 = 当前画布状态,
  与 redo 栈无关),**保持不动**。

## 5. Undo/Redo 机制(考古结论)

- **单一机制**:所有可撤销操作都进同一对 `ArrayDeque<CanvasOp>`。Stroke/Text 增删、文本移动/
  缩放/旋转、裁剪、整图旋转,全部统一。
- `CanvasOp.Add` / `Remove` / `EditText` / `ReplaceImage` 四种 op 覆盖全部历史。
- 文本拖拽/缩放/旋转手势在 `textDown` 起点 `snapState()` 快照,`commitTextTransform()` 在抬手时
  压入单个 EditText op——**一个手势 = 一个可撤销单元**。
- 裁剪/旋转 = `ReplaceImage(ImageState(旧, 元素拷贝), ImageState(新, 空))`,整图状态快照,
  撤销 = 整图状态替换。这是拍平式破坏操作的自然选择。
- 没有任何独立的第二套历史系统。**Undo/Redo 本身不重复**,无需重构。

## 6. 重复代码清单(核心发现)

### 6.1 文本测量逻辑(字面重复,同一文件内 13+ 处)
模式 `textPaint(color, font, size)` → `measureText` → `descent() - ascent()`:
- 证据: `measureText` 15 处、`descent() - p.ascent()` 13 处,**全部在 DrawingCanvasView.kt**:
  `textBounds`(L220)、`drawElement`(L244)、`drawSelection`(L299)、`addText`(L366)、
  `updateText`(L391)、`flattenFullRes`(L562)、`toLocal`(L917)、`rotationKnob`(L935)、
  `startRotation`(L945)、`rotateSelectedText`(L957)、`cornerAt`(L968)、`startCornerResize`(L986)、
  `resizeSelectedText`(L1022)。
- 输入相同(color/font/size/text)、输出语义相同(w,h)。**同一概念 13 份实现**。

### 6.2 旋转中心计算(逻辑重复,7 处)
`cx = el.x + w/2f, cy = el.y + h/2f`(em-box 中心):drawElement(L249)、drawSelection(L307)、
flattenFullRes(L570)、toLocal(L920)、rotationKnob(L940)、startRotation(L948)、
rotateSelectedText(L960)。其中 4 处还重复 `w`/`h` 的测量(L6.1)。

### 6.3 文本选择框角点(字面重复,3 处)
`pad = 6f*density`,角点 `(x-pad,y-pad)/(x+w+pad,y-pad)/(x-pad,y+h+pad)/(x+w+pad,y+h+pad)`:
`drawSelection` 画手柄(L303-321)、`cornerAt` 命中测试(L971-977)、`startCornerResize` 锚点
(L989-995)。`textBounds` 用 12dp pad(L225)——不同 pad 但同一"文本盒"概念。

### 6.4 矩阵缩放因子提取(字面重复,3 处)
`hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])`:gestureScale(L525)、updateCanvasGesture(L710)、
endCanvasGesture(L735)。(前两处还带 `.coerceAtLeast(1e-4f)`。)

### 6.5 亮度计算(字面重复,跨文件 2 处)
`0.299f*red + 0.587f*green + 0.114f*blue`(判断图标用黑/白):
DrawingCanvasView.drawRotationHandle(L347)、ToolbarFragment.updatePaletteButton(L411)。

### 6.6 压栈样板(结构重复,7 处)
`undoStack.addLast(op); redoStack.clear()`:addText(L379-380)、updateText(L403-404)、
applyCrop(L426-432)、rotateImage(L452-458)、commitTextTransform(L845-846)、
deleteDraggedText(L864-865)、endStroke(L1124-1125)。

### 6.7 裁剪/旋转整图替换流程(结构重复,2 处)
见 §3.3:applyCrop 与 rotateImage 完全相同骨架。

### 6.8 dp↔滑条进度映射(转换重复,ToolbarFragment 内 5+ 处)
笔 2..16dp(progress 0..14)、高亮 8..32dp(progress 0..24):
- onViewCreated 初始化(L189-191)
- slider 监听器(L196-207)
- setActiveTool 两个分支(L257-265)
- setSelectedPenWidth(L423)、setSelectedHighlighterWidth(L432)
- updateWidthPreview 反向映射(L441-445)
同一"宽度↔进度"转换被实现 5+ 次。

### 6.9 JPEG 段遍历(逻辑重复,跨 commit 独立出现 2 处)
- `readJpegApp1`(AnnotateActivity L608,commit c8c30f6 引入):遍历 marker 找 APP1-Exif。
- `stripJpegExif`(L798,commit ef11754 引入):遍历 marker 丢弃 Exif 段。
两者各自实现同一套 JPEG marker 遍历语义(SOI 跳过、长度前缀段、SOS/EOI 停止),
**由两个不同 commit 独立写出**,互不知晓对方存在。

### 6.10 色板按钮收集与互斥选中(逻辑重复,3 个文件)
- 收集 ColorButton 后代:ToolbarFragment.colorPanelChildren 递归(L225-240)、
  TextEditorFragment L78-80、StickerActivity L68-72(后两者只收直接子节点)。
- 互斥 checked:`for (other in colorButtons) other.checked = other === cb`
  StickerActivity L75-77、TextEditorFragment L84-85;ToolbarFragment.setSelectedColor
  的等价循环 L376-378。**同一"单选色块"概念三份实现**。

### 6.11 工具高亮逻辑(逻辑重复,2 处)
"激活工具:画笔→墨水色,非画笔→onPrimary,未激活→中性色"三路判定:
ToolbarFragment.setActiveTool/animateIconTint/applyButtonState(L242-354)与
StickerActivity.highlight()(L47-62)。UI 机制不同(滑动高亮 View + PenButton vs 直接换
background),但**着色决策公式相同**。

### 6.12 后台任务骨架(结构重复,5+ 处)
"进度遮罩 → Thread{...} → runOnUiThread 收尾/失败 toast":
AnnotateActivity.loadImage/doSave/doShare/doCopy/fire_department 按钮、
StickerActivity.saveSticker。成功分支各不相同,失败分支几乎全是 `R.string.image_save_failed`。

### 6.13 文件写出(字面重复,小)
- AnnotateActivity.writeImage 与 writeJpegWithExif(L563-581):目录创建 + `markup_<ts>.<ext>`
  命名 + FileOutputStream,仅压缩方式不同。
- saveToMediaStore 与 StickerActivity.saveSticker:位图写出骨架。
- `writePng`(L560)是 writeImage 的薄包装,**无人调用**。

## 7. 应该复用但没有复用的(以及不应合并的)

### 7.1 应复用
- §6.1~6.9 全部属于"同一个概念多份实现",应各自收敛到单一实现。
- `DrawingCanvasView` 的 `pointerSpan/pointerFocus` 已共享(好),文本两指缩放与画布手势共用。

### 7.2 看起来相似但语义不同、**不应**合并(防御性清单)
| 位置 | 为什么不合并 |
|---|---|
| eraser 预览(合成进 inkLayer)vs pen/highlighter 预览(直接画主画布) | CLEAR 必须作用在墨水位图;alpha 叠加会变实(§3.1 注释,历史 bug fa5b2e7/1ff00d3) |
| CropOverlayView 8 手柄 vs 文本 4 角点缩放 | 前者任意矩形边/角可拖、有 MOVE 模式、视图坐标;后者对角锚定、局部(旋转)坐标,数学不同 |
| `textBounds`(12dp pad,命中测试)vs `drawSelection` box(6dp pad,视觉) | pad 语义不同:命中容差 vs 视觉边距;`textBounds` 还被 hitTestText 用 |
| StickerActivity 工具条 vs ToolbarFragment | 布局/交互机制完全不同(纯 ImageButton + ring vs 滑动高亮 + PenButton + 色板面板),合并要改 UI,违反"不破坏行为" |
| gestureScale() 的 coerceAtLeast vs endCanvasGesture 不 clamp | 前者防除零(缩比),后者只要实际缩放值——虽然公式同源,但调用语义不同,提取时须保留各自行为 |

## 8. 死代码(无人引用,git grep 证实)

| 位置 | 证据 |
|---|---|
| `DrawingCanvasView.isStickerMode()` L157 | 全仓无调用 |
| `AnnotateActivity.writePng()` L560 | 全仓无调用 |
| `attrs.xml` 的 `PenButton_tool_name` | toolbar.xml 设置了 `app:tool_name`,但 PenButton.kt 从不读取 |
| `drawable/ic_close.xml` | 无任何布局/代码引用 |
| `dimens.xml` 的 `ink_text_padding` / `ink_rect_handle_size` | 无引用 |
| `colors.xml` 的 `crop_dim` / `crop_handle` | 无引用(CropOverlayView 硬编码 0x99000000 / WHITE) |
| strings.xml 8 条:metadata_location_removed / bold_font / classic_font / modern_font / script_font / soft_font / bubbly_font / tool_selected | 无引用(字体按钮文案用 Kotlin FONT_NAMES,不用字符串) |

> 注:`MarkupEditText`(10 行空壳)不算死代码——它是 text_editor.xml 的标签类,保留。

## 9. 高耦合点与隐式依赖

- **AnnotateActivity 是唯一中介**:canvas ↔ toolbar ↔ text fragment ↔ crop ↔ trash ↔ 保存全走它,
  回调接口 2 个(DrawingCanvasView.Listener / ToolbarFragment.Callbacks)+ 2 个 lambda
  (textFragment.onCommit/onDismissed)。改动任一组件都要过这里。
- **DrawingCanvasView 内部隐式状态机**:`draggingText/scalingText/resizingCorner/rotatingText`
  互斥关系靠 ACTION_* 事件顺序隐式维护,ACTION_CANCEL 需要同时清 6 个字段(L673-682)。
- **sticker 模式 = source==null 的隐式分支**:flattenFullRes、computeMatrix、onDraw 都按
  source 可空分支,没有显式的模式标志。
- **palettePanel 懒解析**(ToolbarFragment L54-78):布局声明顺序导致 fragment 视图在
  activity 布局展开**期间**创建,面板控件必须 `by lazy` 延后 find。这是布局结构造成的历史包袱,
  改动布局顺序需极其小心。

## 10. 历史原因(为什么代码长这样)

- `com.google.android.markup` 初始包名 → 850f65c 改名(与 Markup-libre 同源)。
- `inkLayer` 双缓冲 + 预览分流 = 两个 alpha 相关 bug(fa5b2e7, 1ff00d3)的直接产物。
- `matrix.postConcat(gestureMatrix)` 的注释是 91769b3 修"松手回弹"后的教训记录。
- `readJpegApp1`/`stripJpegExif` 各自独立出生(c8c30f6 / ef11754),互不感知。
- 色板面板 `by lazy` + `paletteInited` 标志 = c6a6494/06da4a1 反复迭代的痕迹。
- HEAD(341812e)commitPendingCrop:裁剪中保存的 WYSIWYG 修复,最新功能。
- `CanvasOp.Remove` 的 index+element 双保险(L484-493)= 删除后索引漂移的历史防御。

## 11. UNKNOWN(不猜)

- **UNKNOWN**: `MarkupEditText` 为何需要自定义子类(无任何覆写)——可能是从上游 Markup-libre
  继承的痕迹,无历史 commit 说明。
- **UNKNOWN**: `isStickerMode()`/`writePng` 当初的用途(从未有调用者)。
- **UNKNOWN**: StickerActivity 的 `SAVE_STICKER` signature 权限与 `com.google.android.apps.pixel.
  creativeassistant` queries 是否仍被实际系统流程使用(manifest 保留,无法在本机验证)。
- **UNKNOWN**: 各 UI 交互(动画时长 120ms、容差 26/28dp、clamp 0.2x-8x、eraser 30dp 等)
  具体数值是否必须与 Google Markup 逐像素一致——clean-room 复刻,无原版对照可测。

## 12. 结论

代码整体**结构清晰、历史可追溯**,没有需要推翻重来的乱象。真正的乱点集中在:
1. **DrawingCanvasView 内部**:文本测量/旋转中心/角点/矩阵缩放等几何原语 13+ 份重复
   (单文件内复制粘贴式增长,60 个 commit 从未收敛)。
2. **JPEG 段遍历两份独立实现**。
3. **工具条/色板/宽度滑条的镜像状态与映射逻辑**。
4. **一批死代码**(无风险清理)。
5. 若干跨文件小重复(亮度、色块互斥、后台任务骨架)。

Undo/Redo、画布渲染管线、预览分流机制**本身不重复**,不动。
