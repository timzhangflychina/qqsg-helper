package com.qqsg.helper;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全窗 OCR 找指定文字（菜单选项 / NPC 名字标签），返回窗口内的点击坐标。
 *
 * <p>背景：抓鬼实车翻车（2026-09-21）—— 许子将菜单用固定行坐标 (520,382) 点
 * 「无条件的评论」，实机上对话框位置和标定截图有偏差，点到了上一行「对话/任务」。
 * 结论：<b>菜单选项一律 OCR 找到文字再点，固定坐标只做兜底</b>。
 *
 * <p>实现要点（都是本项目踩过的坑）：
 * <ul>
 *   <li>OCR 图放独立作业目录（{@link OcrLite#prepareJobDir}），结果按文件名过滤 ——
 *       recognize 是按目录跑的，混进历史截图会认出一堆旧字。</li>
 *   <li>行坐标在 ×2 放大图上，换算 = 行坐标 / 2 / 窗口缩放。</li>
 *   <li>只认调用方给的窗口区域，排除聊天区、任务追踪面板等可能出现同名字样的地方。</li>
 *   <li>多行命中取<b>最短</b>的一行 —— 菜单选项只有 4~10 个字，NPC 正文一大段，
 *       正文里也常出现同样的词（如「北邙山」），取最短才点在选项上。</li>
 *   <li>匹配用「包含 或 相似度 ≥0.72」，容忍 OCR 把「的」认成「酌」之类的错字。</li>
 * </ul>
 */
final class ScreenText {

    /**
     * 默认搜索区 {x0, x1, y0, y1} = 屏幕中央区：排除聊天区（y&gt;600）、
     * 右上角地图条与「任务追踪」面板（x&gt;810，那里可能出现同名字样但点了跑偏）。
     */
    static final int[] ZONE_CENTRAL = {260, 810, 200, 540};

    private ScreenText() {
    }

    /**
     * <b>同一张截图只 OCR 一次</b>（2026-09-29 加速，收益最大的一处）。
     *
     * <p>背景：单次 OCR 要冷启一个 PowerShell 进程，固定开销 ~0.5 秒。而任务里
     * 同一个 {@code img} 常被连续问好几遍 —— 军团任务一轮里 {@code fullHintInFrame}
     * →{@code dailyFullDetected}→{@code ScreenText.find(1级军团任务)}→
     * {@code findTalkOptionInZone}→{@code questPanelTextPresent} 各整窗 OCR 一次，
     * 同一张图重复识别 5~8 次，一个字都没多读，白等 3~4 秒。
     *
     * <p>缓存键用 {@link java.util.IdentityHashMap}（<b>引用相等</b>，不是 equals）：
     * 同一张 {@code BufferedImage} 对象命中缓存；换了新截图（新对象）自然失效，
     * 不需要任何失效逻辑 —— 调用方每次 {@code captureWindow()} 都是新对象。
     *
     * <p>容量上限 {@value #OCR_CACHE_MAX} 张，超出即整体清空（图片占内存，别攒着）。
     */
    private static final int OCR_CACHE_MAX = 8;
    private static final java.util.Map<BufferedImage, List<TextLine>> OCR_CACHE =
            Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static int ocrCacheHits = 0;
    private static int ocrCacheMiss = 0;

    /** 命中/未命中计数（回归脚本与日志用）。 */
    static int ocrCacheHits() {
        return ocrCacheHits;
    }

    static int ocrCacheMiss() {
        return ocrCacheMiss;
    }

    static void resetOcrCacheStats() {
        ocrCacheHits = 0;
        ocrCacheMiss = 0;
    }

    /** 清空缓存（换窗口 / 任务开始时调用，避免跨任务残留）。 */
    static void clearOcrCache() {
        OCR_CACHE.clear();
    }

    /**
     * @param img      当前窗口截图（null 返回 null）
     * @param keywords 依序尝试的关键词（先长后短；长词优先命中精确选项）
     * @param zone     {x0, x1, y0, y1}，只认这个窗口区域里的文字行
     * @param dyClick  命中行中心再往下偏多少像素：NPC 名字标签传 +18（点身体），
     *                 菜单选项传 0（点文字本身）
     * @return 窗口内点击坐标；找不到 / OCR 异常返回 null（不抛错，调用方走兜底）
     */
    static int[] find(BufferedImage img, String[] keywords, int[] zone, int dyClick) {
        try {
            if (img == null) {
                return null;
            }
            double kx = img.getWidth() / (double) GameWindowController.BASE_WIDTH;
            double ky = img.getHeight() / (double) GameWindowController.BASE_HEIGHT;
            // 复用 lines() 的缓存（同一张图不会为了 find 再跑一次 OCR）
            List<TextLine> all = lines(img);
            if (all.isEmpty()) {
                return null;
            }
            for (String kw : keywords) {
                String want = XiaolianBank.normalize(kw);
                if (want.isEmpty()) {
                    continue;
                }
                int bx = -1;
                int by = -1;
                int bestLen = Integer.MAX_VALUE;
                for (TextLine l : all) {
                    String n = l.text;   // lines() 已归一化
                    if (n.isEmpty()) {
                        continue;
                    }
                    if (!n.contains(want) && XiaolianBank.similarity(n, want) < 0.72) {
                        continue;
                    }
                    int cx = l.cx;
                    int cy = l.cy;
                    if (cx < zone[0] || cx > zone[1] || cy < zone[2] || cy > zone[3]) {
                        continue;
                    }
                    if (n.length() < bestLen) {
                        bestLen = n.length();
                        bx = cx;
                        by = cy + dyClick;
                    }
                }
                if (bx >= 0) {
                    return new int[]{bx, by};
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一行 OCR 结果：归一化后的文本 + 窗口内中心坐标。 */
    static final class TextLine {
        final String text;
        final int cx;
        final int cy;

        TextLine(String text, int cx, int cy) {
            this.text = text;
            this.cx = cx;
            this.cy = cy;
        }
    }

    /**
     * 整窗 OCR，返回<b>所有</b>文字行（窗口内坐标，未做 zone 过滤）。
     *
     * <p>{@link #find} 只能问「某个词在哪」，问不了「画面上都有哪些字、分别在哪」——
     * 抢线任务需要一次拿到选线面板上 16 条「N线（状态）」各自的位置，所以补这个口子。
     *
     * <p>处理流程与 {@link #find} 完全一致（独立作业目录、×2 放大、坐标 /2/缩放、
     * 文本归一化），保证两边坐标口径相同。
     *
     * <p><b>带缓存</b>：同一张 {@code img} 对象第二次问直接返回上次结果，
     * 不再启 PowerShell（见 {@link #OCR_CACHE}）。这是军团任务加速的关键。
     *
     * @param img 当前窗口截图
     * @return 文字行列表；截图 null 或 OCR 异常时返回空表（不抛错）
     */
    static List<TextLine> lines(BufferedImage img) {
        if (img == null) {
            return new ArrayList<>();
        }
        List<TextLine> hit = OCR_CACHE.get(img);
        if (hit != null) {
            ocrCacheHits++;
            return hit;
        }
        ocrCacheMiss++;
        List<TextLine> out = linesUncached(img);
        if (OCR_CACHE.size() >= OCR_CACHE_MAX) {
            OCR_CACHE.clear();   // 图片占内存，攒满即清（不做 LRU，够用）
        }
        OCR_CACHE.put(img, out);
        return out;
    }

    /**
     * 真正跑 OCR 的实现（不带缓存）。{@link #lines} 包一层缓存后调用它。
     *
     * <p>注意：空结果<b>也缓存</b>。任务里「这张图没有可交互元素」是要反复确认的
     * 结论，缓存空表能省掉重复的整窗 OCR。
     */
    private static List<TextLine> linesUncached(BufferedImage img) {
        List<TextLine> out = new ArrayList<>();
        try {
            if (img == null) {
                return out;
            }
            double kx = img.getWidth() / (double) GameWindowController.BASE_WIDTH;
            double ky = img.getHeight() / (double) GameWindowController.BASE_HEIGHT;
            File jobDir = OcrLite.prepareJobDir();
            File f = OcrLite.saveForOcr(img, 2, new File(jobDir, "all.png"),
                    OcrLite.MODE_ORIGINAL);
            if (f == null) {
                return out;
            }
            List<OcrLite.Result> res = OcrLite.recognize(Collections.singletonList(f), 30);
            for (OcrLite.Result one : res) {
                if (one.file == null || !one.file.replace('\\', '/').endsWith("all.png")) {
                    continue; // recognize 按目录跑，过滤掉目录里可能的历史图
                }
                for (OcrLite.Line l : one.sorted()) {
                    String n = XiaolianBank.normalize(l.text);
                    if (n.isEmpty()) {
                        continue;
                    }
                    int cx = (int) Math.round(l.cx() / 2.0 / kx);
                    int cy = (int) Math.round(l.cy() / 2.0 / ky);
                    out.add(new TextLine(n, cx, cy));
                }
            }
        } catch (Throwable ignore) {
            // OCR 不可用 / 失败 → 返回已收集的部分（可能为空表）
        }
        return out;
    }
}
