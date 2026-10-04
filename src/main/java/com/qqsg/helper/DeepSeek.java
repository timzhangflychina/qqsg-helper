package com.qqsg.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * DeepSeek 大模型问答客户端（只在<b>本地题库查不到</b>时兜底用）。
 *
 * <h3>为什么能不用第三方库</h3>
 * 只发一个 HTTPS POST、取回 JSON 里的一个字段，JDK 自带的 {@link HttpURLConnection}
 * 足够；JSON 也只做了一个「按需求值」的极简解析（见 {@link #extractContent}），
 * 不引入 Gson/Jackson，jar 体积不变。
 *
 * <h3>延迟控制</h3>
 * 题库每题限时 15 秒，所以这里默认连接 4 秒、读 6 秒。超时直接放弃并走人工兜底，
 * 不会把答题窗口拖没。
 */
public final class DeepSeek {

    /** 默认模型（便宜、够快；答题这种短问答不需要 reasoner）。 */
    public static final String MODEL = "deepseek-chat";
    /** 接口地址。 */
    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";

    private DeepSeek() {
    }

    /** 问答题的系统提示：只要序号，别的什么都别输出。 */
    private static final String SYSTEM_PROMPT =
            "你是《QQ三国》游戏「推举孝廉」答题助手。"
                    + "用户会给你一道题和若干选项，你要选出唯一正确的那个选项。"
                    + "只输出这个选项的序号（阿拉伯数字），不要输出任何其它文字、标点或解释。"
                    + "题目文字来自游戏截图 OCR，可能有错别字或缺字，请结合题意推断，不要因此拒答。"
                    + "必须给出一个答案，不允许回答 0 或「无法判断」。";

    /** 复述模式的系统提示：把正确选项那行文字原样照抄回来（用于按相似度反查屏幕行）。 */
    private static final String SYSTEM_PROMPT_ECHO =
            "你是《QQ三国》游戏「推举孝廉」答题助手。"
                    + "用户会给你一段来自游戏答题界面的 OCR 文字（含题目和若干选项）。"
                    + "请判断哪一项是正确答案，然后把那一项的完整文字原样照抄输出，"
                    + "不要输出序号、不要输出解释、不要输出题目本身，只输出正确选项那一行的文字。"
                    + "文字里可能有错别字或缺字，请结合题意推断。必须给出一个答案。";

    /**
     * 字母模式的系统提示：只回 A/B/C/D。
     *
     * <p>为什么优先用字母：选项常常只有 2~3 个字（如「太丑 / 太笨」），OCR 很容易认错其中
     * 一个字（实测「太丑」被认成「太君」），这时按文字相似度反查屏幕行会完全对不上。
     * 而选项字母的位置是<b>固定</b>的（第一项必是 A），只要求模型回一个字母，就绕开了这个坑。
     */
    private static final String SYSTEM_PROMPT_LETTER =
            "你是《QQ三国》游戏「推举孝廉」答题助手。"
                    + "用户会给你一道题和四个选项（标了字母 A/B/C/D），其中选项文字来自游戏截图 OCR，"
                    + "可能有错别字或缺字。请判断哪个选项正确，然后<b>只输出这个选项的字母</b>"
                    + "（单个大写字母，A、B、C 或 D），不要输出文字、序号或解释。"
                    + "必须给出一个答案，不允许说无法判断。";

    /**
     * 「字母模式」：给出题干 + 按固定四行归位后的选项，让模型只回一个字母。
     *
     * @return 模型原文（调用方用 {@link #firstLetterIndex} 解析）
     */
    public static String pickByLetter(String apiKey, String question, String[] rows,
                                      int connectTimeoutMs, int readTimeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("题目（OCR，可能有错字）：\n").append(question == null ? "" : question.trim())
                .append("\n\n四个选项（按固定位置从上到下）：\n");
        for (int i = 0; i < rows.length; i++) {
            sb.append((char) ('A' + i)).append(". ")
                    .append(rows[i] == null || rows[i].trim().isEmpty() ? "（未识别）" : rows[i].trim())
                    .append('\n');
        }
        sb.append("\n请只输出正确选项的字母。");
        return chat(apiKey, SYSTEM_PROMPT_LETTER, sb.toString(), connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 「复述模式」：把整屏 OCR 文字交给模型，让它把正确选项那一行原样抄回来。
     *
     * <p>为什么不直接问序号：游戏界面里「倒计时」标签夹在题干中间，会把题干切成碎片，
     * 选项行也常被漏检，序号极易错位。让模型复述文字、再按相似度反查回屏幕行，
     * 就没有「序号对不上」的风险。
     *
     * @return {模型原文, 无}（下标交给调用方去做相似度匹配）
     */
    public static String pickByScreenText(String apiKey, String screenText,
                                          int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String user = "游戏答题界面文字如下（OCR 结果，可能有错字）：\n\n"
                + (screenText == null ? "" : screenText.trim())
                + "\n\n请输出正确选项那一行的完整文字。";
        return chat(apiKey, SYSTEM_PROMPT_ECHO, user, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 调一次 chat/completions，返回模型输出的正文。
     *
     * @throws IOException 网络/协议错误
     */
    public static String chat(String apiKey, String system, String user,
                              int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String body = "{"
                + "\"model\":\"" + escape(MODEL) + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escape(system) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escape(user) + "\"}"
                + "],"
                + "\"temperature\":0,"
                + "\"max_tokens\":64,"
                + "\"stream\":false"
                + "}";

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new IOException("HTTP " + code + "：" + shorten(text));
            }
            String content = extractContent(text);
            if (content == null) {
                throw new IOException("返回里没有 content 字段：" + shorten(text));
            }
            return content.trim();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 让模型在选项里选一个。
     *
     * @return 选项下标（0 基）；模型说不知道或解析不出来返回 -1
     */
    public static int pickOption(String apiKey, String question, List<String> options,
                                 int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Object[] r = pickOptionVerboseRaw(apiKey, question, options, connectTimeoutMs, readTimeoutMs);
        return (Integer) r[1];
    }

    /**
     * 同 {@link #pickOption}，但把「模型原文」也带出来，方便排查为什么没选出来。
     *
     * @return {模型原文(String), 下标(Integer)}；下标解析失败时为 -1
     */
    public static Object[] pickOptionVerboseRaw(String apiKey, String question, List<String> options,
                                                int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (options == null || options.isEmpty()) {
            return new Object[]{"", -1};
        }
        StringBuilder sb = new StringBuilder();
        sb.append("题目：").append(question == null ? "" : question.trim()).append('\n');
        sb.append("选项：\n");
        for (int i = 0; i < options.size(); i++) {
            sb.append(i + 1).append(". ").append(options.get(i).trim()).append('\n');
        }
        sb.append("\n请从上面 ").append(options.size())
                .append(" 个选项里选出正确的那一个，只回复选项序号（一个阿拉伯数字，例如 2），"
                        + "不要写任何解释。");

        String out = chat(apiKey, SYSTEM_PROMPT, sb.toString(), connectTimeoutMs, readTimeoutMs);
        return new Object[]{out, parseIndex(out, options)};
    }

    /** 便捷版：只返回下标。 */
    public static int pickOptionVerbose(String apiKey, String question, List<String> options,
                                        int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Object[] r = pickOptionVerboseRaw(apiKey, question, options, connectTimeoutMs, readTimeoutMs);
        return (Integer) r[1];
    }

    /**
     * 从模型回复里解析出选项下标（0 基），失败返回 -1。
     *
     * <p>分三层兜底，因为实测模型并不总是乖乖只回一个数字：
     * <ol>
     *   <li>回复里第一个数字（最常见的正常情况）；</li>
     *   <li>回复里的 A/B/C/D 字母；</li>
     *   <li>回复直接复述了选项文字 → 用相似度匹配（"购买守护神石进行绑定" 这种）。</li>
     * </ol>
     */
    static int parseIndex(String out, List<String> options) {
        if (out == null || out.trim().isEmpty()) {
            return -1;
        }
        Integer n = firstNumber(out);
        if (n != null && n >= 1 && n <= options.size()) {
            return n - 1;
        }
        int letter = firstLetterIndex(out);
        if (letter >= 0 && letter < options.size()) {
            return letter;
        }
        // 复述选项文字：挑最像的那个
        int best = -1;
        double bs = 0;
        for (int i = 0; i < options.size(); i++) {
            double sc = XiaolianBank.scoreAnswer(options.get(i), out);
            if (sc > bs) {
                bs = sc;
                best = i;
            }
        }
        if (bs >= 0.5) {
            return best;
        }
        return -1;
    }

    /** 取回复里第一个 A~D（大小写均可）对应的下标；没有返回 -1。 */
    static int firstLetterIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c >= 'A' && c <= 'D') {
                // 避免把英文单词里的字母误当成选项（要求前后不是英文字母）
                boolean prevLetter = i > 0 && Character.isLetter(s.charAt(i - 1));
                boolean nextLetter = i + 1 < s.length() && Character.isLetter(s.charAt(i + 1));
                if (!prevLetter && !nextLetter) {
                    return c - 'A';
                }
            }
        }
        return -1;
    }

    /** 取字符串里第一个数字（模型偶尔会多写几个字，这里宽容一点）。 */
    static Integer firstNumber(String s) {
        if (s == null) {
            return null;
        }
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                int j = i;
                while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                    j++;
                }
                try {
                    return Integer.parseInt(s.substring(i, j));
                } catch (Throwable t) {
                    return null;
                }
            }
            i++;
        }
        return null;
    }

    // ==================== 极简 JSON 工具 ====================

    /** 转义成 JSON 字符串字面量内容（不含两侧引号）。 */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 从返回 JSON 里取 {@code choices[0].message.content}。
     * 只做「找到 "content" 键 → 读出它后面那个字符串」，够用且不依赖任何 JSON 库。
     */
    static String extractContent(String json) {
        if (json == null) {
            return null;
        }
        int key = json.indexOf("\"content\"");
        if (key < 0) {
            return null;
        }
        int i = key + "\"content\"".length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != ':') {
            return null;
        }
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        if (json.startsWith("null", i)) {
            return null;
        }
        if (json.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= json.length()) {
                    break;
                }
                char e = json.charAt(i);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            } catch (Throwable ignore) {
                                // 转义序列坏了就原样保留
                            }
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(e);
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 取返回 JSON 里的 error.message，用于报错时给人看。 */
    static String extractError(String json) {
        if (json == null) {
            return null;
        }
        int i = json.indexOf("\"message\"");
        if (i < 0) {
            return null;
        }
        int j = json.indexOf('"', i + 9);
        if (j < 0) {
            return null;
        }
        int k = j + 1;
        StringBuilder sb = new StringBuilder();
        while (k < json.length() && json.charAt(k) != '"') {
            if (json.charAt(k) == '\\' && k + 1 < json.length()) {
                k++;
            }
            sb.append(json.charAt(k));
            k++;
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** 连通性自检：返回 null 表示正常，否则是错误说明。 */
    public static String selfTest(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "没有填 API Key";
        }
        try {
            String out = chat(apiKey.trim(), "你是一个测试用的助手。", "只回复两个字：正常",
                    5000, 8000);
            return out.isEmpty() ? "返回内容为空" : null;
        } catch (Throwable t) {
            return t.getMessage();
        }
    }
}
