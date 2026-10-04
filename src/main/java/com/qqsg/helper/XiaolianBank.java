package com.qqsg.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「推举孝廉」题库：题目 → 正确答案。
 *
 * <h3>为什么要有本地题库</h3>
 * 孝廉每题限时只有 15 秒、而且越往后越短。把题目丢给大模型再等它返回，网络稍慢就来不及。
 * 本地题库匹配是<b>零等待 + 离线可用</b>的，大模型只在题库里查不到时才兜底。
 *
 * <h3>模糊匹配</h3>
 * OCR 难免识错几个字（游戏里的字有描边、有渐变），所以不能拿字符串直接相等去比。
 * 这里把中文标点、空格、大小写全部归一化，再用<b>字符二元组 Dice 系数</b>衡量相似度：
 * <pre>
 *   sim = 2 × |公共二元组| / (|A 的二元组| + |B 的二元组|)
 * </pre>
 * 一道 20 字的题错 2 个字，Dice 仍在 0.8 以上；而不同题之间通常低于 0.4，区分度足够。
 *
 * <h3>数据来源</h3>
 * {@code /xiaolian/xiaolian_bank.tsv}（2256 条，制表符分隔：题目 \t 答案），
 * 整理自公开题库（GitHub: FizzHawkeye/QQSG）。
 */
public final class XiaolianBank {

    /** 一条题库记录。 */
    public static final class Entry {
        public final String question;
        public final String answer;
        private final String nq;              // 归一化题目
        private final Set<String> bigrams;    // 题目的二元组集合

        Entry(String question, String answer) {
            this.question = question;
            this.answer = answer;
            this.nq = normalize(question);
            this.bigrams = bigrams(this.nq);
        }
    }

    /** 匹配结果。 */
    public static final class Match {
        public final Entry entry;
        /** 题目相似度 0~1。 */
        public final double score;
        /** 参与比对的那段文字（可能是几行拼起来的）。 */
        public final String source;
        /**
         * 题库里**同一道题**的全部记录（含 {@link #entry} 自己）。
         *
         * <p>为什么会有多条：题库是按「公开题库原始材料」整理的，同一道题在不同来源/不同版本里
         * 给出的答案并不总是一样 —— 例如「以下哪个职业是属于QQ三国的?」上游资料一口气给了
         * 豪杰 / 仙术士 / 剑侍 / 阴阳士 四条。这些不是脏数据，而是这道题**可能出现的多个正确选项**
         * （游戏每次抽的选项组合不同），所以题库原样保留，答题时要用 {@link #scoreAnyAnswer}
         * 对选项跟这一整组答案一起打分，只认第一条会漏。
         */
        public final List<Entry> sameQuestion;

        /**
         * <b>另一道题</b>（{@code nq} 与 {@link #entry} 不同）里的最高分。
         *
         * <p>2026-09-29 新增：用于「间隔保护」。题干 OCR 读错字时，最像的那条可能
         * 只是擦边命中（如 0.53），而次像的那条也许是另一道题 —— 若两者靠得太近，
         * 说明这次匹配不可靠（可能张冠李戴到别的题上），宁可转大模型也不要答错。
         */
        public final double runnerUp;
        /** 产生 {@link #runnerUp} 的那道题的原文（日志用）。 */
        public final String runnerUpQuestion;

        Match(Entry entry, double score, String source, List<Entry> sameQuestion) {
            this(entry, score, source, sameQuestion, 0, null);
        }

        Match(Entry entry, double score, String source, List<Entry> sameQuestion,
              double runnerUp, String runnerUpQuestion) {
            this.entry = entry;
            this.score = score;
            this.source = source;
            this.sameQuestion = sameQuestion;
            this.runnerUp = runnerUp;
            this.runnerUpQuestion = runnerUpQuestion;
        }

        /** 首选与次选的分差（另一道题）。越大越可信。 */
        public double gap() {
            return score - runnerUp;
        }
    }

    private static volatile List<Entry> entries = null;
    private static volatile String loadError = "";

    private XiaolianBank() {
    }

    /** 题库条数；没加载成功返回 0。 */
    public static int size() {
        List<Entry> e = ensure();
        return e == null ? 0 : e.size();
    }

    /** 加载失败原因。 */
    public static String loadError() {
        ensure();
        return loadError;
    }

    public static boolean loaded() {
        List<Entry> e = ensure();
        return e != null && !e.isEmpty();
    }

    private static synchronized List<Entry> ensure() {
        if (entries != null) {
            return entries;
        }
        List<Entry> list = new ArrayList<>();
        try {
            InputStream in = XiaolianBank.class.getResourceAsStream("/xiaolian/xiaolian_bank.tsv");
            BufferedReader br;
            if (in != null) {
                br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                File dev = new File("src/main/resources/xiaolian/xiaolian_bank.tsv");
                if (!dev.exists()) {
                    loadError = "jar 里缺少 /xiaolian/xiaolian_bank.tsv";
                    entries = list;
                    return entries;
                }
                br = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(dev), StandardCharsets.UTF_8));
            }
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    String q = line.substring(0, tab).trim();
                    String a = line.substring(tab + 1).trim();
                    if (!q.isEmpty() && !a.isEmpty()) {
                        list.add(new Entry(q, a));
                    }
                }
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            loadError = "加载题库失败：" + t;
        }
        entries = list;
        return entries;
    }

    // ==================== 归一化与相似度 ====================

    /**
     * 归一化：只保留汉字、字母、数字；全角转半角、字母转小写。
     * OCR 会在汉字之间插空格，也会把标点识别成别的符号，所以这些一律丢掉。
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {       // 全角 ASCII → 半角
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) {               // 全角空格
                continue;
            }
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else if (isCjk(c)) {
                sb.append(c);
            }
            // 其它（标点、空格、emoji、控制符）全部丢弃
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // 基本区
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s.length() == 1) {
            set.add(s);
        } else {
            for (int i = 0; i + 1 < s.length(); i++) {
                set.add(s.substring(i, i + 2));
            }
        }
        return set;
    }

    // ==================== 数字指纹（2026-10-01 新增） ====================

    /**
     * 抽出字符串里的<b>阿拉伯数字串</b>（按出现顺序），拼成一份「数字指纹」。
     *
     * <p>入参必须已经 {@link #normalize} 过（此时全角数字已折成半角）。
     * 没有数字时返回空串。
     */
    static String digitSignature(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
                in = true;
            } else if (in) {
                sb.append('|');     // 数字段之间加分隔，避免「1与2」被拼成「12」
                in = false;
            }
        }
        if (in) {
            sb.append('|');
            in = false;
        }
        return sb.toString();
    }

    /**
     * 两个<b>都含阿拉伯数字</b>的串，数字指纹是否冲突（数字串不一致）。
     *
     * <p>只要任意一边没有数字，就返回 {@code false}（不适用，交给普通相似度）。
     */
    static boolean digitConflict(String x, String y) {
        String a = digitSignature(x);
        String b = digitSignature(y);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return !a.equals(b);
    }

    /** 数字指纹冲突时，把分数压到的上限 —— 必须低于选项采纳阈值（0.40）。 */
    private static final double DIGIT_CONFLICT_CAP = 0.30;

    /** 两个字符串的二元组 Dice 相似度（0~1）。 */
    public static double similarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        if (x.equals(y)) {
            return 1.0;
        }
        return dice(bigrams(x), bigrams(y));
    }

    /**
     * <b>字符级相似度</b>（基于最长公共子序列，0~1）—— 2026-09-29 新增，专治 OCR 错字。
     *
     * <h3>为什么需要它</h3>
     * 二元组 Dice 对「单个字被认错」极其敏感：一个错字会破坏<b>两个</b>相邻 bigram
     * （如「职业」→「軹业」丢掉「职业」「业…」两个片段）。实测代价：
     * <pre>
     *   题库：残阳炙是哪个职业的技能?          （15 字）
     *   实读：残阳炙是嘟个軹的技能？            （错 2 字）
     *   bigram Dice = 0.526  → 被 0.58 阈值挡掉，题库明明有答案却去问大模型
     * </pre>
     * LCS 只看「有多少个字按顺序对上了」，错 2 个字 ≈ 13/15 = 0.87，短题干不再一错就崩。
     *
     * <h3>实现</h3>
     * 滚动数组 O(n·m) 求 LCS 长度，再除以较长串长度。滚动数组把空间压到 O(min(n,m))，
     * 题库 2254 条 × 每题十几个候选也扛得住。
     */
    public static double lcsRatio(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        if (x.equals(y)) {
            return 1.0;
        }
        int n = x.length(), m = y.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            char ci = x.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                if (ci == y.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                } else {
                    cur[j] = Math.max(prev[j], cur[j - 1]);
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        int lcs = prev[m];
        return (2.0 * lcs) / (n + m);   // 归一化：完全不一致→0，完全一致→1
    }

    /**
     * 题干相似度：<b>bigram Dice 与字符级 LCS 取较大者</b>（2026-09-29 起）。
     *
     * <p>为什么取 max 而不是二选一：
     * <ul>
     *   <li>OCR 干净时两者都高，取谁无所谓；</li>
     *   <li>个别错字时 LCS 明显更高（0.87 vs 0.53），靠它救回擦边题；</li>
     *   <li>字序被打乱/有大量噪声插入时 bigram 更稳（LCS 会被插入的长噪声稀释）。
     *       两者取大 = 两种失效模式互补。</li>
     * </ul>
     */
    public static double questionSimilarity(String a, String b) {
        double d = similarity(a, b);
        double l = lcsRatio(a, b);
        return Math.max(d, l);
    }

    /**
     * 标准答案与某个选项的匹配分。
     *
     * <p>普通情况用二元组 Dice；但题库答案常常很短（例如单字「剑」），而选项带了
     * 「2，」这样的序号前缀，二者 bigram 毫无交集 → Dice 为 0，会漏掉正确答案，
     * 白白走一趟大模型。所以短答案额外用「包含关系 + 长度比」兜底：
     * 越贴合（长度越接近）分越高，从而压过那些只是「碰巧包含」的更长选项。
     *
     * <p>2026-09-29：加入字符级 LCS 通道。选项文字同样会被 OCR 认错
     * （实锤「师缘隐士」→「师缘蠊±」），bigram 只剩 0.413 贴着 0.40 阈值过；
     * 叠 LCS 后能拉开到 0.6+，既更稳也更能压过干扰项。
     */
    /** 否定字（出现即认为该串带「否定语义」）。 */
    private static final String NEG_CHARS = "不没非未无别莫勿否";

    /**
     * 两个字符串是不是「一个带否定、一个不带，但去掉否定字后互相包含/相等」。
     *
     * <p>这种组合语义相反，绝不能让「包含关系」把分抬起来。
     * 实锤（2026-09-30 孝廉第 1 题）：「不是」包含答案「是」，旧逻辑给 0.775，
     * 而真正的选项「是」被 OCR 读成了乱码「00」→ 直接把「不是」当答案点了，第 1 题就答错。
     */
    private static boolean negationMismatch(String x, String y) {
        boolean nx = NEG_CHARS.indexOf(x.charAt(0)) >= 0;
        boolean ny = NEG_CHARS.indexOf(y.charAt(0)) >= 0;
        if (nx == ny) {
            return false;
        }
        String xs = nx ? x.substring(1) : x;
        String ys = ny ? y.substring(1) : y;
        if (xs.isEmpty() || ys.isEmpty()) {
            return true;            // 「是」 vs 「不」这种：一边只剩否定字，必然反向
        }
        return xs.contains(ys) || ys.contains(xs);
    }

    public static double scoreAnswer(String answer, String option) {
        String x = normalize(answer);
        String y = normalize(option);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        double best = scoreNormalized(x, y);
        // 选项行常带序号前缀（「A仓库是什么?」「B是」）——2026-09-30 起再剥一遍首字母比一次取高分。
        // XiaolianTask.stripOptionLetter 会漏掉「字母 + 汉字」这种（汉字在 Character.isLetter 里
        // 算字母，才没被剥），这里补上；对「C不是」这种，剥掉前缀后照样被否定规则拦下。
        String y2 = dropLetterPrefix(y);
        if (!y2.equals(y)) {
            double s2 = scoreNormalized(x, y2);
            if (s2 > best) {
                best = s2;
            }
        }
        return best;
    }

    /** normalize 之后的字符串才是小写，这里只剥「a~d + 还有后文」的序号前缀。 */
    private static String dropLetterPrefix(String y) {
        if (y.length() < 2) {
            return y;
        }
        char c = y.charAt(0);
        return (c >= 'a' && c <= 'd') ? y.substring(1) : y;
    }

    /** {@link #scoreAnswer} 的核心：入参必须已经 normalize 过。 */
    private static double scoreNormalized(String x, String y) {
        if (x.equals(y)) {
            return 1.0;
        }
        // 2026-09-30：反向（否定）匹配一律判死，杜绝「包含答案」的更长选项冒充正确答案。
        if (negationMismatch(x, y)) {
            return 0;
        }
        // 2026-09-30：单字答案只认「以答案开头」。
        // 单字几乎必然被某个更长选项包含（「是」⊂「不是」/「仓库是什么?」），
        // 靠包含关系凑出来的分全是误伤；真选项就是那个单字本身。
        if (x.length() == 1) {
            return y.startsWith(x) ? 0.9 : 0;
        }
        // 2026-10-01：数字指纹冲突 → 直接压死。
        // 实锤（孝廉第 4 题）：「50级及以上」与「60级及以上」只差一个字符，
        // LCS 归一化后 0.833 远超 0.40 阈值 ⇒ 当正确答案被 OCR 读残（50→弱）时，
        // 三个错项（30/60/70级及以上）同分并列，谁都能把答案顶掉 → 点错。
        // 数字型选项（等级/时长/人数/数量）的语义全在数字上，数字不等就是不同答案。
        if (digitConflict(x, y)) {
            return Math.min(DIGIT_CONFLICT_CAP,
                    Math.max(dice(bigrams(x), bigrams(y)), lcsRatio(x, y)));
        }
        double best = Math.max(dice(bigrams(x), bigrams(y)), lcsRatio(x, y));
        if (x.length() <= 2 && y.contains(x)) {
            double ratio = (double) x.length() / y.length();
            double contain = 0.55 + 0.45 * ratio;
            if (contain > best) {
                best = contain;
            }
        }
        return best;
    }

    private static double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        int inter = 0;
        for (String g : small) {
            if (big.contains(g)) {
                inter++;
            }
        }
        return 2.0 * inter / (a.size() + b.size());
    }

    // ==================== 匹配 ====================

    /**
     * 在一堆候选文字里找最像某道题的。
     *
     * @param candidates 屏幕 OCR 出来的若干文字片段（单行、或几行拼起来的）
     */
    public static Match matchQuestion(List<String> candidates) {
        List<Entry> all = ensure();
        if (all == null || all.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Entry bestEntry = null;
        double bestScore = 0;
        String bestSource = null;
        Entry secondEntry = null;      // 另一道题里的最高分（间隔保护用）
        double secondScore = 0;
        for (String raw : candidates) {
            String cand = normalize(raw);
            if (cand.length() < 3) {
                continue;
            }
            for (Entry e : all) {
                // 长度差太多的直接跳过，省时间
                int la = e.nq.length(), lb = cand.length();
                if (Math.abs(la - lb) > Math.max(6, la * 6 / 10)) {
                    continue;
                }
                // 2026-09-29：改用「bigram ∪ 字符级 LCS」——OCR 错一两个字时 bigram 掉得
                // 太狠（0.53），会把题库里明明有的答案判成噪声。
                double sc = questionSimilarity(e.nq, cand);
                if (bestEntry == null || sc > bestScore) {
                    // 旧的冠军降级为次选候选
                    if (bestEntry != null && !bestEntry.nq.equals(e.nq)) {
                        if (sc > secondScore) {
                            secondEntry = bestEntry;
                            secondScore = bestScore;
                        }
                    }
                    bestScore = sc;
                    bestEntry = e;
                    bestSource = raw;
                } else if (!e.nq.equals(bestEntry.nq) && sc > secondScore) {
                    // 只在「另一道题」里记次高分 —— 同一道题的多条记录不算（那本就是同一个答案组）
                    secondEntry = e;
                    secondScore = sc;
                }
            }
        }
        if (bestEntry == null) {
            return null;
        }
        // 把「同一道题」的全部答案一并带上（同题多答案是题库有意保留的，见 Match#sameQuestion）
        List<Entry> same = new ArrayList<>();
        for (Entry e : all) {
            if (e.nq.equals(bestEntry.nq)) {
                same.add(e);
            }
        }
        if (same.isEmpty()) {
            same.add(bestEntry);
        }
        return new Match(bestEntry, bestScore, bestSource, same,
                secondScore, secondEntry == null ? null : secondEntry.question);
    }

    /**
     * 选项与「这道题的全部已知答案」的匹配分：取其中最高的一个。
     *
     * <p>库里同一道题有多条记录时（同题多答案），只跟 {@code entry.answer} 比会漏掉
     * 其它同样正确的选项，从而把一个本来稳拿的题丢给大模型。传 {@code null} 或空表时
     * 退化成 0（调用方自己知道要不要兜底）。
     */
    public static double scoreAnyAnswer(List<Entry> entries, String option) {
        if (entries == null || entries.isEmpty() || option == null) {
            return 0;
        }
        double best = 0;
        for (Entry e : entries) {
            double sc = scoreAnswer(e.answer, option);
            if (sc > best) {
                best = sc;
            }
        }
        return best;
    }

    /** 在选项列表里挑最像标准答案的那个（返回下标，找不到返回 -1）。 */
    public static int matchAnswer(String answer, List<String> options) {
        if (answer == null || options == null || options.isEmpty()) {
            return -1;
        }
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < options.size(); i++) {
            double sc = scoreAnswer(answer, options.get(i));
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return bestScore <= 0 ? -1 : best;
    }

    /** 同上，但把分数也带出来。返回 {下标, 分数×1000}，找不到返回 null。 */
    public static int[] matchAnswerScored(String answer, List<String> options) {
        if (answer == null || options == null || options.isEmpty()) {
            return null;
        }
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < options.size(); i++) {
            double sc = scoreAnswer(answer, options.get(i));
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        return new int[]{best, (int) Math.round(bestScore * 1000)};
    }

    /** 调试用：拿一段文字去题库里查，返回前 N 个候选。 */
    public static List<String> topMatches(String text, int n) {
        List<Entry> all = ensure();
        List<double[]> scores = new ArrayList<>();
        String cand = normalize(text);
        Set<String> cg = bigrams(cand);
        for (int i = 0; i < all.size(); i++) {
            scores.add(new double[]{i, dice(all.get(i).bigrams, cg)});
        }
        Collections.sort(scores, (a, b) -> Double.compare(b[1], a[1]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scores.size()); i++) {
            Entry e = all.get((int) scores.get(i)[0]);
            out.add(String.format("%.3f  %s  ->  %s", scores.get(i)[1], e.question, e.answer));
        }
        return out;
    }
}
