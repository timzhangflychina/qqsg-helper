package com.qqsg.helper.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Enumeration;

/**
 * 界面美化工具箱：统一配色 / 字体 / 圆角卡片 / 扁平按钮 / 矢量图标 / 自动换行布局。
 *
 * <p>设计目标：
 * <ul>
 *   <li>按钮尺寸统一、间距均匀，看着整齐；</li>
 *   <li>任务按钮自动换行 —— 以后再加四五个按钮，也只会多占一行，不会出现横向滚动条；</li>
 *   <li>所有图标都用 Java2D 现画（{@link Glyph}），不依赖任何图片资源与系统 emoji 字体。</li>
 * </ul>
 */
public final class UiKit {

    private UiKit() {
    }

    /* ------------------------------------------------------------------ 配色 */

    /** 窗口底色。 */
    public static final Color APP_BG = new Color(0xEDF0F5);
    /** 卡片底色。 */
    public static final Color CARD_BG = new Color(0xFFFFFF);
    /** 卡片描边。 */
    public static final Color CARD_BORDER = new Color(0xDCE2EA);
    /** 分割线。 */
    public static final Color LINE = new Color(0xE5E9EF);
    /** 主文字。 */
    public static final Color TEXT = new Color(0x1F2A37);
    /** 次要文字。 */
    public static final Color TEXT_SUB = new Color(0x7B8899);

    public static final Color GREEN = new Color(0x16A34A);
    public static final Color RED = new Color(0xE0484C);
    public static final Color BLUE = new Color(0x2F6FED);
    public static final Color PURPLE = new Color(0x7C5CE0);
    public static final Color AMBER = new Color(0xC07A0A);
    public static final Color TEAL = new Color(0x0E9C9C);
    public static final Color GRAY = new Color(0x5B6675);

    /* ------------------------------------------------------------------ 字体 */

    private static String family = "Dialog";

    public static Font font(int size) {
        return new Font(family, Font.PLAIN, size);
    }

    public static Font bold(int size) {
        return new Font(family, Font.BOLD, size);
    }

    /** 挑一个系统里存在的中文字体，保证中文不变方块。 */
    private static String pickFamily() {
        String[] prefer = {"Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
                "PingFang SC", "Noto Sans CJK SC", "Dialog"};
        java.util.Set<String> avail = new java.util.HashSet<>(java.util.Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String p : prefer) {
            if (avail.contains(p)) {
                return p;
            }
        }
        return "Dialog";
    }

    /**
     * 安装外观（Look and Feel）与全局字体。<b>必须在创建任何 Swing 组件之前调用。</b>
     */
    public static void install() {
        family = pickFamily();

        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 装不上就用默认外观，界面依旧可用
        }

        FontUIResource f = new FontUIResource(family, Font.PLAIN, 12);
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object k = keys.nextElement();
            if (UIManager.get(k) instanceof FontUIResource) {
                UIManager.put(k, f);
            }
        }
        UIManager.put("Panel.background", APP_BG);
        UIManager.put("OptionPane.messageFont", new FontUIResource(family, Font.PLAIN, 13));
        UIManager.put("OptionPane.buttonFont", new FontUIResource(family, Font.PLAIN, 12));
        UIManager.put("ToolTip.font", new FontUIResource(family, Font.PLAIN, 12));
    }

    /* -------------------------------------------------------------- 颜色工具 */

    public static Color alpha(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                (int) Math.round(Math.max(0, Math.min(1, a)) * 255));
    }

    public static Color mix(Color a, Color b, double ratio) {
        double r = Math.max(0, Math.min(1, ratio));
        return new Color(
                (int) Math.round(a.getRed() * (1 - r) + b.getRed() * r),
                (int) Math.round(a.getGreen() * (1 - r) + b.getGreen() * r),
                (int) Math.round(a.getBlue() * (1 - r) + b.getBlue() * r));
    }

    /* -------------------------------------------------------------- 矢量图标 */

    /**
     * 图标形状。全部在 0~1 的单位方格里描述，绘制时自动缩放到目标尺寸，
     * 因此同一个图标可以任意放大缩小而不会糊。
     */
    public enum Glyph {
        PLAY, STOP, MONITOR, CLOSE, PLUS, REFRESH, FLAG, CASTLE, COIN, PEOPLE, GEAR, STAR, BOOK, BRAIN;

        /** 在 {@code (x, y)} 处画一个边长 {@code size} 的图标。 */
        public void paint(Graphics2D g2, double x, double y, double size, Color color) {
            Graphics2D g = (Graphics2D) g2.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.scale(size, size);
            g.setColor(color);
            BasicStroke thin = new BasicStroke(0.10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            BasicStroke mid = new BasicStroke(0.13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            g.setStroke(thin);

            switch (this) {
                case PLAY: {
                    Path2D p = new Path2D.Double();
                    p.moveTo(0.30, 0.18);
                    p.lineTo(0.82, 0.50);
                    p.lineTo(0.30, 0.82);
                    p.closePath();
                    g.fill(p);
                    break;
                }
                case STOP: {
                    g.fill(new RoundRectangle2D.Double(0.25, 0.25, 0.50, 0.50, 0.14, 0.14));
                    break;
                }
                case MONITOR: {
                    g.setStroke(thin);
                    g.draw(new RoundRectangle2D.Double(0.10, 0.18, 0.80, 0.52, 0.12, 0.12));
                    g.draw(new Line2D.Double(0.50, 0.70, 0.50, 0.84));
                    g.draw(new Line2D.Double(0.30, 0.86, 0.70, 0.86));
                    break;
                }
                case CLOSE: {
                    g.setStroke(mid);
                    g.draw(new Line2D.Double(0.26, 0.26, 0.74, 0.74));
                    g.draw(new Line2D.Double(0.74, 0.26, 0.26, 0.74));
                    break;
                }
                case PLUS: {
                    g.fill(new RoundRectangle2D.Double(0.425, 0.16, 0.15, 0.68, 0.07, 0.07));
                    g.fill(new RoundRectangle2D.Double(0.16, 0.425, 0.68, 0.15, 0.07, 0.07));
                    break;
                }
                case REFRESH: {
                    g.setStroke(thin);
                    // 圆环缺口开在右上方
                    g.draw(new Arc2D.Double(0.18, 0.18, 0.64, 0.64, 55, 245, Arc2D.OPEN));
                    // 缺口处的箭头，指向顺时针方向
                    Path2D head = new Path2D.Double();
                    head.moveTo(0.50, 0.02);
                    head.lineTo(0.88, 0.21);
                    head.lineTo(0.52, 0.38);
                    head.closePath();
                    g.fill(head);
                    break;
                }
                case FLAG: {
                    g.setStroke(thin);
                    g.draw(new Line2D.Double(0.26, 0.12, 0.26, 0.88));
                    Path2D p = new Path2D.Double();
                    p.moveTo(0.28, 0.14);
                    p.lineTo(0.84, 0.26);
                    p.lineTo(0.28, 0.48);
                    p.closePath();
                    g.fill(p);
                    break;
                }
                case CASTLE: {
                    Path2D p = new Path2D.Double();
                    p.moveTo(0.10, 0.86);
                    p.lineTo(0.10, 0.34);
                    p.lineTo(0.26, 0.34);
                    p.lineTo(0.26, 0.20);
                    p.lineTo(0.42, 0.20);
                    p.lineTo(0.42, 0.34);
                    p.lineTo(0.58, 0.34);
                    p.lineTo(0.58, 0.20);
                    p.lineTo(0.74, 0.20);
                    p.lineTo(0.74, 0.34);
                    p.lineTo(0.90, 0.34);
                    p.lineTo(0.90, 0.86);
                    p.closePath();
                    g.fill(p);
                    break;
                }
                case COIN: {
                    g.setStroke(thin);
                    g.draw(new Ellipse2D.Double(0.10, 0.10, 0.80, 0.80));
                    g.draw(new Line2D.Double(0.34, 0.28, 0.50, 0.46));
                    g.draw(new Line2D.Double(0.66, 0.28, 0.50, 0.46));
                    g.draw(new Line2D.Double(0.50, 0.46, 0.50, 0.74));
                    g.draw(new Line2D.Double(0.34, 0.52, 0.66, 0.52));
                    g.draw(new Line2D.Double(0.34, 0.63, 0.66, 0.63));
                    break;
                }
                case PEOPLE: {
                    g.setStroke(thin);
                    g.draw(new Ellipse2D.Double(0.06, 0.16, 0.26, 0.26));
                    g.draw(new Ellipse2D.Double(0.54, 0.16, 0.26, 0.26));
                    g.draw(new Arc2D.Double(0.00, 0.52, 0.38, 0.34, 0, 180, Arc2D.OPEN));
                    g.draw(new Arc2D.Double(0.48, 0.52, 0.38, 0.34, 0, 180, Arc2D.OPEN));
                    break;
                }
                case GEAR: {
                    g.setStroke(thin);
                    g.draw(new Ellipse2D.Double(0.30, 0.30, 0.40, 0.40));
                    g.draw(new Ellipse2D.Double(0.12, 0.12, 0.76, 0.76));
                    g.draw(new Line2D.Double(0.50, 0.04, 0.50, 0.24));
                    g.draw(new Line2D.Double(0.50, 0.76, 0.50, 0.96));
                    g.draw(new Line2D.Double(0.04, 0.50, 0.24, 0.50));
                    g.draw(new Line2D.Double(0.76, 0.50, 0.96, 0.50));
                    break;
                }
                case STAR: {
                    g.setStroke(thin);
                    Path2D p = new Path2D.Double();
                    for (int i = 0; i < 10; i++) {
                        double ang = -Math.PI / 2 + i * Math.PI / 5;
                        double rr = (i % 2 == 0) ? 0.44 : 0.19;
                        double px = 0.5 + rr * Math.cos(ang);
                        double py = 0.5 + rr * Math.sin(ang);
                        if (i == 0) {
                            p.moveTo(px, py);
                        } else {
                            p.lineTo(px, py);
                        }
                    }
                    p.closePath();
                    g.fill(p);
                    break;
                }
                case BOOK: {
                    g.setStroke(thin);
                    // 一本摊开的书：中间书脊 + 左右两页
                    Path2D left = new Path2D.Double();
                    left.moveTo(0.50, 0.24);
                    left.lineTo(0.10, 0.16);
                    left.lineTo(0.10, 0.78);
                    left.lineTo(0.50, 0.86);
                    left.closePath();
                    g.draw(left);
                    Path2D right = new Path2D.Double();
                    right.moveTo(0.50, 0.24);
                    right.lineTo(0.90, 0.16);
                    right.lineTo(0.90, 0.78);
                    right.lineTo(0.50, 0.86);
                    right.closePath();
                    g.draw(right);
                    g.draw(new Line2D.Double(0.50, 0.24, 0.50, 0.86));
                    break;
                }
                case BRAIN: {
                    g.setStroke(thin);
                    // 灯泡：孝廉答题的主视觉
                    g.draw(new Ellipse2D.Double(0.26, 0.08, 0.48, 0.48));
                    g.draw(new Line2D.Double(0.36, 0.58, 0.36, 0.72));
                    g.draw(new Line2D.Double(0.64, 0.58, 0.64, 0.72));
                    g.draw(new RoundRectangle2D.Double(0.32, 0.74, 0.36, 0.14, 0.06, 0.06));
                    g.draw(new Line2D.Double(0.50, 0.00, 0.50, 0.06));
                    break;
                }
                default:
                    break;
            }
            g.dispose();
        }
    }

    /* ------------------------------------------------------------ 扁平按钮 */

    /** 按钮风格。 */
    public enum Mode {
        /** 实心：用于「启动」等主操作。 */
        FILLED,
        /** 描边：用于任务按钮，每个任务一种主题色。 */
        OUTLINE,
        /** 幽灵：用于次要操作，无边框。 */
        GHOST
    }

    /**
     * 自绘的圆角扁平按钮。
     *
     * <p>相比 Swing 默认按钮：尺寸完全可控（{@link #setFixedSize}），
     * 圆角、悬停/按下反馈、左侧矢量小图标、运行中状态切换都由这里统一处理，
     * 所以整排按钮看上去是同一个模子刻出来的。
     */
    public static class FlatButton extends JButton {

        private final Glyph glyph;
        private final Color accent;
        private final Mode mode;
        /**
         * 圆角半径。跟着按钮高度缩 —— 26 高的小按钮再用 8 的圆角会圆得像药丸，
         * 显得比实际还大；26 高用 6、34 高仍用 8。
         */
        private final int arc;

        private String baseText;
        private boolean hover;
        private boolean pressed;
        private boolean active;   // 开关类按钮的“已开启”状态
        private boolean running;  // 任务运行中

        public FlatButton(String text, Glyph glyph, Mode mode, Color accent, int w, int h) {
            super();
            getModel().setEnabled(true);
            this.baseText = text == null ? "" : text;
            this.glyph = glyph;
            this.mode = mode;
            this.accent = accent;
            this.arc = Math.max(5, Math.min(8, h / 4));

            setFixedSize(w, h);
            setFont(UiKit.font(fontSizeFor(h)));
            setFocusable(false);
            setBorder(null);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    pressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (isEnabled()) {
                        pressed = true;
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    pressed = false;
                    repaint();
                }
            });
        }

        /**
         * 按按钮高度挑字号 —— 矮按钮用更小的字，否则字会顶满整条按钮、显得臃肿。
         *
         * <p>2026-09-23 应用户要求把任务按钮整体缩到约原来一半（112×34 → 66×26），
         * 字号随之降到 10 号；控制栏那些 30 高的按钮（启动/停止/后台/移除）仍是 12 号。
         */
        private static int fontSizeFor(int h) {
            if (h >= 29) {
                return 12;
            }
            return h >= 20 ? 10 : 9;
        }

        /**
         * 按按钮高度挑图标边长 —— 图标跟字号一起缩，避免「大图标配小字」。
         *
         * <p>34 高的旧按钮图标 18→16；26 高的新任务按钮图标只有 10，视觉上大约减半。
         */
        private int iconSizeFor(int h) {
            return Math.max(8, Math.min(16, h - 16));
        }

        /** 固定按钮尺寸（同时约束最小/最大，避免被布局拉变形）。 */
        public final void setFixedSize(int w, int h) {
            Dimension d = new Dimension(w, h);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        public void setActive(boolean active) {
            this.active = active;
            repaint();
        }

        public boolean isActive() {
            return active;
        }

        public void setRunning(boolean running) {
            this.running = running;
            super.setText(running ? "中止任务" : baseText);
            repaint();
        }

        public boolean isRunning() {
            return running;
        }

        @Override
        public void setText(String t) {
            baseText = t == null ? "" : t;
            super.setText(running ? "中止任务" : baseText);
            repaint();
        }

        @Override
        public String getText() {
            return running ? "中止任务" : baseText;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            Color eff = running ? RED : accent;

            Color fill;
            Color border;
            Color fg;
            if (!isEnabled()) {
                fill = new Color(0xF1F3F6);
                border = new Color(0xE2E6EC);
                fg = new Color(0xAEB6C0);
            } else {
                switch (mode) {
                    case FILLED:
                        fill = eff;
                        border = eff;
                        fg = Color.WHITE;
                        if (hover) {
                            fill = mix(fill, Color.WHITE, 0.12);
                            border = fill;
                        }
                        break;
                    case OUTLINE:
                        fill = active ? alpha(eff, 0.13) : (hover ? alpha(eff, 0.09) : CARD_BG);
                        border = alpha(eff, hover || active ? 0.80 : 0.45);
                        fg = eff;
                        break;
                    default: // GHOST
                        fill = active ? alpha(eff, 0.16) : (hover ? new Color(0xE6EAF1) : new Color(0xF1F3F7));
                        border = null;
                        fg = active ? eff : (hover ? TEXT : GRAY);
                        break;
                }
                if (pressed) {
                    fill = mix(fill, new Color(0x000000), 0.10);
                }
            }

            if (fill != null) {
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            if (border != null) {
                g2.setColor(border);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }

            String text = getText();
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int iconSize = iconSizeFor(h);
            int gap = (glyph != null && !text.isEmpty()) ? Math.max(3, iconSize / 3) : 0;
            int textW = text.isEmpty() ? 0 : fm.stringWidth(text);
            int contentW = (glyph != null ? iconSize : 0) + gap + textW;

            // 预留边距也跟着缩小（旧值 8 是为 112 宽的大按钮留的，窄按钮再留 8 就挤了）
            int x = Math.max(5, (w - contentW) / 2);
            if (glyph != null) {
                glyph.paint(g2, x, (h - iconSize) / 2.0, iconSize, fg);
                x += iconSize + gap;
            }
            if (textW > 0) {
                int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(fg);
                g2.drawString(text, x, baseline);
            }
            g2.dispose();
        }
    }

    /* ------------------------------------------------------------ 卡片 / 布局 */

    /** 圆角白卡片，可当容器用；高度只按内容算，不会被纵向 BoxLayout 拉长。 */
    public static class CardPanel extends JPanel {
        private final int radius;

        public CardPanel(LayoutManager lm, int padTop, int padLeft, int padBottom, int padRight) {
            this(lm, padTop, padLeft, padBottom, padRight, 12);
        }

        public CardPanel(LayoutManager lm, int padTop, int padLeft, int padBottom, int padRight, int radius) {
            super(lm);
            this.radius = radius;
            setOpaque(false);
            setBorder(new EmptyBorder(padTop, padLeft, padBottom, padRight));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * 放在 {@link JScrollPane} 里、宽度永远跟随视口的面板。
     *
     * <p>这是「自动换行 + 没有横向滚动条」的关键：视口有多宽，它就多宽，
     * 里面的按钮只管换行，绝不会把内容撑到视口外面去。
     */
    public static class ScrollablePanel extends JPanel implements Scrollable {
        private final boolean trackWidth;

        public ScrollablePanel(LayoutManager lm, boolean trackWidth) {
            super(lm);
            this.trackWidth = trackWidth;
            setOpaque(true);
            setBackground(APP_BG);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - 40, 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return trackWidth;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /* -------------------------------------------------------------- 小挂件 */

    /** 彩色小圆点（在线状态灯）。 */
    public static JComponent dot(final int size, final Color color) {
        JComponent c = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int y = (getHeight() - size) / 2;
                g2.setColor(alpha(color, 0.20));
                g2.fillOval(0, y, size, size);
                g2.setColor(color);
                g2.fillOval(2, y + 2, size - 4, size - 4);
                g2.dispose();
            }
        };
        Dimension d = new Dimension(size, size);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
        c.setOpaque(false);
        return c;
    }

    /** 圆角小标签（如「1号」）。 */
    public static class Chip extends JComponent {
        private final String text;
        private final Color fg;
        private final Color bg;

        public Chip(String text, Color fg, Color bg) {
            this.text = text == null ? "" : text;
            this.fg = fg;
            this.bg = bg;
            setFont(UiKit.font(10));
            setOpaque(false);
            FontMetrics fm = getFontMetrics(getFont());
            Dimension d = new Dimension(fm.stringWidth(this.text) + 14, 17);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.setColor(fg);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** 1 像素分割线。 */
    public static JComponent hLine(Color color) {
        JComponent c = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(color);
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        c.setPreferredSize(new Dimension(1, 1));
        c.setMinimumSize(new Dimension(1, 1));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        c.setOpaque(false);
        return c;
    }

    /** 区域小标题。 */
    public static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(bold(13));
        l.setForeground(TEXT);
        return l;
    }

    /** 小号灰色说明文字。 */
    public static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(11));
        l.setForeground(TEXT_SUB);
        return l;
    }
}
