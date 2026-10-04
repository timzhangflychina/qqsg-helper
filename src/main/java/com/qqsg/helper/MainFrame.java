package com.qqsg.helper;

import com.qqsg.helper.ui.UiKit;
import com.qqsg.helper.ui.UiKit.Glyph;
import com.qqsg.helper.ui.UiKit.Mode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主窗口。
 *
 * <p>界面布局分三块：
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │ ① 顶部工具条（在白色卡条上）                    │
 *   ├──────────────────────────────────────────────┤
 *   │ ② 控制窗口卡片列表（每个窗口一张卡片）            │  ← 自适应宽度、按钮自动换行
 *   ├──────────────────────────────────────────────┤
 *   │ ③ 运行日志                                     │
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>按钮排布采用「固定宽高 + 自动换行」：每张卡片的任务按钮区会根据当前宽度算出一行能放几个，
 * 放不下就整体换到下一行。所以以后继续往卡片里加按钮，也只会让卡片变高一点，
 * 永远不会把按钮挤出可视区域、也不需要左右拉滚动条。
 */
public class MainFrame extends JFrame {

    /* ------------------------- 卡片布局常量（改这里就能整体调节按钮大小/间距） */

    /**
     * 任务按钮宽度。
     *
     * <p>2026-09-23 应用户要求「按钮太大了、更紧凑一点，长度大约缩一半」：
     * 由 112 缩到 62（0.55 倍），刚好能放下「军团任务」「集体召唤」这类
     * 四字文案 + 图标（四字 40px + 图标 10px + 间隙 3px = 53px，余量 9px）。
     */
    private static final int TASK_W = 62;
    /** 任务按钮高度（原 34，同步压缩到 26）。 */
    private static final int TASK_H = 26;
    /** 任务按钮之间的间距（原 8，缩到 6）。 */
    private static final int TASK_GAP = 6;
    /** 卡片列表四周留白。 */
    private static final int OUTER_PAD = 12;
    /** 卡片左右内边距。 */
    private static final int CARD_PAD_X = 14;
    /** 卡片之间的竖直间距。 */
    private static final int CARD_GAP = 10;

    private JComboBox<String> windowComboBox;
    private List<GameWindowController> controllers;
    private JTextArea logArea;
    private JCheckBox minimizeCheckBox;
    private JCheckBox hidePlayersCheckBox;
    private JCheckBox xiaolianDryRunCheckBox;
    private JSpinner roundsSpinner;
    private JPanel controllersPanel;              // 卡片列表容器
    private JScrollPane controllersScrollPane;    // 卡片列表滚动区（仅纵向）
    private final List<ControllerCard> cards = new ArrayList<>();
    private JLabel countLabel;                    // 「共 N 个」
    private JPanel emptyCard;                     // 空状态提示
    private int windowNumber = 1;                 // 窗口编号计数

    public MainFrame() {
        super("QQ三国助手");
        controllers = new ArrayList<>();
        initializeUI();
        refreshWindowList();

        // 移除了启动时自动添加窗口的功能，用户需要手动添加窗口

        // 界面预览用：-Dqqsg.ui.preview=2 会直接把前 N 个窗口加进来，方便截图看排版
        String preview = System.getProperty("qqsg.ui.preview");
        if (preview != null) {
            setAlwaysOnTop(true);
            setLocation(0, 0);
        }
        if (preview != null && windowComboBox.getItemCount() > 0) {
            int n = 1;
            try {
                n = Integer.parseInt(preview);
            } catch (NumberFormatException ignored) {
                // 用默认值
            }
            for (int i = 0; i < Math.min(n, windowComboBox.getItemCount()); i++) {
                windowComboBox.setSelectedIndex(i);
                addController();
            }
        }
    }

    /* ===================================================================== 界面 */

    private void initializeUI() {
        setSize(1020, 680);
        setMinimumSize(new Dimension(860, 520));
        setLocationRelativeTo(null);
        getContentPane().setBackground(UiKit.APP_BG);
        setLayout(new BorderLayout());

        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        // 关闭监听，确保停止所有控制器
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopAllControllers();
            }
        });
    }

    /** ① 顶部工具条：选择窗口 / 添加窗口 / 全局参数。 */
    private JPanel buildTopBar() {
        JPanel rows = new JPanel();
        rows.setOpaque(true);
        rows.setBackground(UiKit.CARD_BG);
        rows.setBorder(new EmptyBorder(12, 16, 12, 16));
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));

        // ---- 第一行：选择游戏窗口
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row1.setOpaque(false);

        JLabel windowLabel = new JLabel("游戏窗口");
        windowLabel.setFont(UiKit.font(12));
        windowLabel.setForeground(UiKit.TEXT_SUB);
        row1.add(windowLabel);

        windowComboBox = new JComboBox<>();
        windowComboBox.setPreferredSize(new Dimension(360, 30));
        windowComboBox.setFont(UiKit.font(12));
        row1.add(windowComboBox);

        JButton refreshButton = new UiKit.FlatButton("刷新", Glyph.REFRESH, Mode.GHOST, UiKit.BLUE, 88, 30);
        refreshButton.setToolTipText("重新扫描当前所有 QQ三国 窗口");
        refreshButton.addActionListener(e -> refreshWindowList());
        row1.add(refreshButton);

        // ---- 第二行：添加窗口 + 全局参数
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row2.setOpaque(false);

        JButton addControllerButton = new UiKit.FlatButton("添加控制窗口", Glyph.PLUS, Mode.FILLED,
                UiKit.BLUE, 148, 30);
        addControllerButton.setToolTipText("把上面选中的游戏窗口加入下方列表，之后就能对它一键执行各种任务");
        addControllerButton.addActionListener(e -> addController());
        row2.add(addControllerButton);

        minimizeCheckBox = new JCheckBox("允许最小化到后台");
        minimizeCheckBox.setSelected(true);
        minimizeCheckBox.setOpaque(false);
        minimizeCheckBox.setFont(UiKit.font(12));
        minimizeCheckBox.setToolTipText("勾选后，游戏窗口切到后台/最小化时仍继续发送按键");
        row2.add(minimizeCheckBox);

        JLabel roundsLabel = new JLabel("军团任务轮次");
        roundsLabel.setFont(UiKit.font(12));
        roundsLabel.setForeground(UiKit.TEXT_SUB);
        row2.add(roundsLabel);

        roundsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
        roundsSpinner.setPreferredSize(new Dimension(64, 30));
        roundsSpinner.setFont(UiKit.font(12));
        roundsSpinner.setToolTipText("军团初级任务要刷的轮数，默认 5 轮（对应「军团任务 X/5」）");
        row2.add(roundsSpinner);

        hidePlayersCheckBox = new JCheckBox("隐藏周围玩家(F11)");
        hidePlayersCheckBox.setSelected(true);
        hidePlayersCheckBox.setOpaque(false);
        hidePlayersCheckBox.setFont(UiKit.font(12));
        hidePlayersCheckBox.setToolTipText("开始前按一次 F11，隐藏周围玩家/摊位，画面更干净");
        row2.add(hidePlayersCheckBox);

        xiaolianDryRunCheckBox = new JCheckBox("孝廉演练模式");
        xiaolianDryRunCheckBox.setSelected(false);
        xiaolianDryRunCheckBox.setOpaque(false);
        xiaolianDryRunCheckBox.setFont(UiKit.font(12));
        xiaolianDryRunCheckBox.setToolTipText("<html>勾上后点「孝廉」只做识别与提示，<b>不会真的点选项</b>。<br>"
                + "用来在不赌上答题机会的前提下，验证读题 / 题库匹配是否准确。</html>");
        row2.add(xiaolianDryRunCheckBox);

        rows.add(row1);
        rows.add(Box.createVerticalStrut(10));
        rows.add(row2);

        // 白色工具条下面压一条分隔线
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(UiKit.CARD_BG);
        top.add(rows, BorderLayout.CENTER);
        top.add(UiKit.hLine(UiKit.LINE), BorderLayout.SOUTH);
        return top;
    }

    /** ② + ③ 中间区域：控制窗口卡片列表 + 运行日志。 */
    private JPanel buildCenter() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(UiKit.APP_BG);
        root.setBorder(new EmptyBorder(10, OUTER_PAD, 10, OUTER_PAD));

        // ---- 卡片列表（宽度跟随视口，只纵向滚动）
        controllersPanel = new UiKit.ScrollablePanel(null, true);
        controllersPanel.setLayout(new BoxLayout(controllersPanel, BoxLayout.Y_AXIS));

        emptyCard = buildEmptyCard();
        controllersPanel.add(emptyCard);
        controllersPanel.add(Box.createVerticalGlue());

        controllersScrollPane = new JScrollPane(controllersPanel);
        controllersScrollPane.setBorder(null);
        controllersScrollPane.getViewport().setBackground(UiKit.APP_BG);
        controllersScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        controllersScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        controllersScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        controllersScrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayoutCards();
            }
        });

        JPanel sectionHeader = new JPanel(new BorderLayout());
        sectionHeader.setOpaque(false);
        sectionHeader.setBorder(new EmptyBorder(0, 2, 8, 2));
        sectionHeader.add(UiKit.sectionTitle("控制窗口"), BorderLayout.WEST);

        countLabel = UiKit.hint("共 0 个");
        sectionHeader.add(countLabel, BorderLayout.EAST);

        JPanel listArea = new JPanel(new BorderLayout());
        listArea.setOpaque(false);
        listArea.add(sectionHeader, BorderLayout.NORTH);
        listArea.add(controllersScrollPane, BorderLayout.CENTER);

        root.add(listArea, BorderLayout.CENTER);
        root.add(buildLogArea(), BorderLayout.SOUTH);
        return root;
    }

    /** 空状态卡片。 */
    private JPanel buildEmptyCard() {
        UiKit.CardPanel card = new UiKit.CardPanel(new BorderLayout(), 18, 18, 18, 18);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel("还没有控制窗口：在上方选择游戏窗口，然后点「添加控制窗口」。");
        l.setFont(UiKit.font(12));
        l.setForeground(UiKit.TEXT_SUB);
        card.add(l, BorderLayout.WEST);
        return card;
    }

    /** ③ 运行日志。 */
    private JPanel buildLogArea() {
        UiKit.CardPanel card = new UiKit.CardPanel(new BorderLayout(0, 6), 10, 14, 12, 14);
        card.setPreferredSize(new Dimension(1, 168));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.sectionTitle("运行日志"), BorderLayout.WEST);

        JButton clearButton = new UiKit.FlatButton("清空", null, Mode.GHOST, UiKit.GRAY, 60, 22);
        clearButton.setToolTipText("清空日志内容");
        clearButton.addActionListener(e -> logArea.setText(""));
        head.add(clearButton, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(UiKit.font(12));
        logArea.setForeground(new Color(0x33404F));
        logArea.setBackground(new Color(0xFAFBFC));
        logArea.setMargin(new Insets(6, 8, 6, 8));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(UiKit.LINE));
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        card.add(scrollPane, BorderLayout.CENTER);
        return card;
    }

    /* ============================================================ 控制窗口卡片 */

    /**
     * 单个游戏窗口对应的卡片：上面一行是角色名 + 启停类小按钮，
     * 下面一块是可自动换行的任务按钮区。
     */
    private class ControllerCard extends UiKit.CardPanel {
        private final JPanel taskPanel;
        private final List<Component> taskButtons = new ArrayList<>();
        private int cols = -1;

        ControllerCard() {
            super(new BorderLayout(0, 10), 12, CARD_PAD_X, 12, CARD_PAD_X);
            setAlignmentX(Component.LEFT_ALIGNMENT);

            taskPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, TASK_GAP, TASK_GAP));
            taskPanel.setOpaque(false);
        }

        /** 添加一个任务按钮，并立刻按当前列数排一次版。 */
        void addTask(Component c) {
            taskButtons.add(c);
            taskPanel.add(c);
            cols = -1;
        }

        void build(JPanel header) {
            add(header, BorderLayout.NORTH);
            add(taskPanel, BorderLayout.CENTER);
        }

        /**
         * 按视口宽度计算一行能放几个任务按钮，并据此固定任务区高度。
         *
         * <p>高度是自己算出来显式设置的（而不是交给布局去推），
         * 这样卡片高度稳定、不会出现「按钮换了行但卡片没长高」被裁掉的情况。
         */
        void updateColumns(int viewportWidth) {
            int avail = viewportWidth - OUTER_PAD * 2 - 4 /* 预留纵向滚动条 */ - CARD_PAD_X * 2;
            int c = Math.max(1, (avail + TASK_GAP) / (TASK_W + TASK_GAP));
            if (c == cols) {
                return;
            }
            cols = c;
            int rows = Math.max(1, (taskButtons.size() + c - 1) / c);
            int h = rows * TASK_H + (rows + 1) * TASK_GAP;
            Dimension d = new Dimension(0, h);
            taskPanel.setPreferredSize(d);
            taskPanel.setMinimumSize(d);
            taskPanel.revalidate();
            revalidate();
            repaint();
        }
    }

    /** 视口宽度变化时重排所有卡片。 */
    private void relayoutCards() {
        int w = controllersScrollPane.getViewport().getWidth();
        if (w <= 0) {
            return;
        }
        for (ControllerCard c : cards) {
            c.updateColumns(w);
        }
        controllersPanel.revalidate();
        controllersPanel.repaint();
    }

    /* ================================================================= 窗口列表 */

    private void refreshWindowList() {
        windowComboBox.removeAllItems();
        // Get real window list using WindowUtils with IDs
        List<WindowInfo> windowsWithIds = WindowUtils.getAllWindowsWithIds();

        // 同一 PID 只解析一次角色信息（resolve 内部另有 3 秒缓存）
        Map<Integer, GameRoleInfo> roleCache = new HashMap<>();

        // 显示标题：优先用游戏日志里解析出的「角色名」，解析不到则回退窗口标题（区服 + 线路）
        List<String> displayTitles = new ArrayList<>();
        for (WindowInfo window : windowsWithIds) {
            displayTitles.add(displayTitleOf(window, roleCache));
        }

        // 统计每个显示标题出现的次数
        Map<String, Integer> titleCountMap = new HashMap<>();
        for (String title : displayTitles) {
            titleCountMap.put(title, titleCountMap.getOrDefault(title, 0) + 1);
        }

        // 为每个窗口准备显示名称，如果标题重复则添加编号
        // 注意：末尾必须保留 "(PID: xxx)"，addController()/getPidFromWindowName() 依赖它
        Map<String, Integer> titleIndexMap = new HashMap<>();
        for (int i = 0; i < windowsWithIds.size(); i++) {
            WindowInfo window = windowsWithIds.get(i);
            String title = displayTitles.get(i);
            String displayName;

            if (titleCountMap.get(title) == 1) {
                displayName = title;
            } else {
                // 对于重复的标题，添加编号
                int index = titleIndexMap.getOrDefault(title, 0) + 1;
                titleIndexMap.put(title, index);
                displayName = title + " (" + index + ")";
            }

            if (window.getPid() > 0) {
                displayName = displayName + " (PID: " + window.getPid() + ")";
            }

            windowComboBox.addItem(displayName);
        }

        log("Refreshed window list, found " + windowsWithIds.size() + " windows");
        for (WindowInfo window : windowsWithIds) {
            GameRoleInfo info = roleCache.get(window.getPid());
            if (info != null && info.hasRoleName()) {
                log("  识别到角色「" + info.getRoleName() + "」"
                        + (info.getAccount() != null ? "，账号 " + info.getAccount() : "")
                        + "（PID " + window.getPid() + "）");
            }
        }
    }

    /**
     * 计算窗口的显示标题：优先使用游戏日志里解析出的角色名，
     * 解析不到（未登录 / 非 QQ三国窗口）时回退到窗口原始标题。
     */
    private String displayTitleOf(WindowInfo window, Map<Integer, GameRoleInfo> cache) {
        int pid = window.getPid();
        if (pid <= 0) {
            return window.getTitle();
        }
        GameRoleInfo info = cache.get(pid);
        if (info == null) {
            info = GameRoleInfo.resolve(pid);
            cache.put(pid, info);
        }
        return info.hasRoleName() ? info.getRoleName() : window.getTitle();
    }

    private void addController() {
        if (controllers.size() >= 15) {
            log("Maximum 15 windows can be controlled!");
            return;
        }

        String windowName = (String) windowComboBox.getSelectedItem();
        if (windowName == null) {
            log("Please select a game window first!");
            return;
        }

        // Check if this window has already been added
        for (GameWindowController controller : controllers) {
            if (controller.getWindowName().equals(windowName)) {
                log("This window is already being controlled!");
                return;
            }
        }

        // Get window info and create controller with process ID
        int pid = WindowUtils.getPidFromWindowName(windowName);

        // 添加错误处理和日志记录
        log("Attempting to add window: " + windowName);
        log("Extracted PID: " + pid);

        // 即使PID解析失败，也继续创建控制器，但记录警告
        if (pid == -1) {
            log("WARNING: Failed to extract PID from window name, controller may have limited functionality");
        }

        try {
            GameWindowController controller = new GameWindowController(windowName, pid);
            controllers.add(controller);

            // Create UI components for this controller
            createControllerUI(controller);

            log("Successfully added control window: " + windowName);
        } catch (Exception e) {
            log("ERROR: Failed to create controller for window " + windowName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 为控制器创建一张卡片。 */
    private void createControllerUI(final GameWindowController controller) {
        if (emptyCard != null) {
            controllersPanel.remove(emptyCard);
            emptyCard = null;
        }
        if (countLabel != null) {
            countLabel.setText("共 " + (cards.size() + 1) + " 个");
        }

        final ControllerCard card = new ControllerCard();

        /* ---------- 卡片头部：角色名 + 启停类按钮 ---------- */
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        // 角色名（解析不到就退回窗口标题），详细账号 / 线路 / PID 放悬浮提示
        final GameRoleInfo roleInfo = controller.getProcessId() > 0
                ? GameRoleInfo.resolve(controller.getProcessId())
                : null;
        String labelText = (roleInfo != null && roleInfo.hasRoleName())
                ? roleInfo.getRoleName()
                : controller.getWindowName();

        String subText = controller.getWindowName();
        if (roleInfo != null && roleInfo.hasRoleName() && roleInfo.getAccount() != null) {
            subText = "账号 " + roleInfo.getAccount() + "  ·  " + controller.getWindowName();
        }
        if (subText.length() > 44) {
            subText = subText.substring(0, 43) + "…";
        }

        JPanel nameBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nameBox.setOpaque(false);
        nameBox.add(UiKit.dot(10, controller.isWindowAvailable() ? UiKit.GREEN : UiKit.GRAY));

        JPanel texts = new JPanel();
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        texts.setOpaque(false);
        JLabel nameLabel = new JLabel(labelText);
        nameLabel.setFont(UiKit.bold(14));
        nameLabel.setForeground(UiKit.TEXT);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subLabel = new JLabel(subText);
        subLabel.setFont(UiKit.font(11));
        subLabel.setForeground(UiKit.TEXT_SUB);
        subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        texts.add(nameLabel);
        texts.add(Box.createVerticalStrut(2));
        texts.add(subLabel);
        nameBox.add(texts);

        // 窗口编号小标签
        String displayNumber = windowNumber + "号";
        String windowName = controller.getWindowName();
        if (windowName.contains("线")) {
            int lineIndex = windowName.lastIndexOf("线");
            if (lineIndex > 0) {
                int numStartIndex = lineIndex - 1;
                while (numStartIndex >= 0 && Character.isDigit(windowName.charAt(numStartIndex))) {
                    numStartIndex--;
                }
                if (numStartIndex < lineIndex - 1) {
                    displayNumber = windowName.substring(numStartIndex + 1, lineIndex) + "号";
                }
            }
        }
        final String numberText = displayNumber;
        nameBox.add(new UiKit.Chip(numberText, UiKit.BLUE, UiKit.alpha(UiKit.BLUE, 0.12)));
        windowNumber++;

        if (roleInfo != null && roleInfo.hasRoleName()) {
            String tip = "<html>" + roleInfo.describe().replace("\n", "<br>")
                    + "<br>窗口：" + windowName + "</html>";
            nameLabel.setToolTipText(tip);
            subLabel.setToolTipText(tip);
        }

        // 右侧小按钮：启动 / 停止 / 后台 / 移除
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);

        final UiKit.FlatButton startButton = new UiKit.FlatButton("启动", Glyph.PLAY, Mode.FILLED,
                UiKit.GREEN, 76, 30);
        startButton.setToolTipText("开始对该窗口自动释放技能");
        startButton.addActionListener(e -> {
            controller.start();
            log("Started skill release for: " + controller.getWindowName());
        });

        final UiKit.FlatButton stopButton = new UiKit.FlatButton("停止", Glyph.STOP, Mode.OUTLINE,
                UiKit.RED, 76, 30);
        stopButton.setToolTipText("停止该窗口的全部自动化动作");
        stopButton.addActionListener(e -> {
            controller.stop();
            log("Stopped skill release for: " + controller.getWindowName());
        });

        final UiKit.FlatButton backgroundButton = new UiKit.FlatButton("后台", Glyph.MONITOR, Mode.GHOST,
                UiKit.BLUE, 74, 30);
        backgroundButton.setActive(false);
        backgroundButton.setToolTipText("<html>切换「后台运行」：<br>"
                + "开启后<b>截图用 PrintWindow、点击用窗口消息</b>，<br>"
                + "游戏窗口被遮挡 / 切到后台也能自动操作，<br>"
                + "且不抢焦点、不移动你的真实鼠标。</html>");
        backgroundButton.addActionListener(e -> {
            boolean on = !backgroundButton.isActive();
            backgroundButton.setActive(on);
            controller.setRunInBackground(on);
            log((on ? "【已开启后台运行】" : "【已关闭后台运行】")
                    + " 窗口：" + controller.getWindowName()
                    + (on ? "（截图/点击/按键都不再抢前台）" : "（恢复前台截图与真实鼠标点击）"));
        });

        final UiKit.FlatButton removeButton = new UiKit.FlatButton("移除", Glyph.CLOSE, Mode.GHOST,
                UiKit.GRAY, 74, 30);
        removeButton.setToolTipText("停止并把这个窗口从列表里移除");
        removeButton.addActionListener(e -> {
            controller.stop();
            removeController(controller);
            cards.remove(card);
            controllersPanel.remove(card);
            if (cards.isEmpty() && emptyCard == null) {
                emptyCard = buildEmptyCard();
                controllersPanel.add(emptyCard, 0);
            }
            if (countLabel != null) {
                countLabel.setText("共 " + cards.size() + " 个");
            }
            controllersPanel.revalidate();
            controllersPanel.repaint();
        });

        actions.add(startButton);
        actions.add(stopButton);
        actions.add(backgroundButton);
        actions.add(removeButton);

        header.add(nameBox, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);

        /* ---------- 任务按钮：自动换行 ---------- */

        // 军团任务按钮
        final UiKit.FlatButton legionButton = new UiKit.FlatButton("军团任务", Glyph.FLAG, Mode.OUTLINE,
                UiKit.BLUE, TASK_W, TASK_H);
        legionButton.setToolTipText("<html>一键完成军团初级任务（<b>全后台运行</b>）：<br>"
                + "① 关闭游戏广告弹窗（游戏活动展示 / 热点活动）<br>"
                + "② 第 1 轮完整流程：O → 回到军团 → G → 进入军团大厅<br>"
                + "③ 每轮：G 出对话菜单 → <b>点「对话/任务」→ 点「1级军团任务」</b> → 点「确定」<br>"
                + "④ <b>全程鼠标点击、一次回车都不按</b><br>"
                + "&nbsp;&nbsp;回车在游戏里是聊天输入框的开关，一旦漏到主界面就会把字母打进<br>"
                + "&nbsp;&nbsp;聊天框，后面的 G 再也唤不出对话（改成点击后彻底避开）<br>"
                + "缺少物品或弹出验证时会自动停止并提醒<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，窗口被遮挡也能跑</html>");
        legionButton.addActionListener(e -> toggleLegionTask(controller, legionButton));

        // 霸王城按钮
        final UiKit.FlatButton bawangButton = new UiKit.FlatButton("霸王城", Glyph.CASTLE, Mode.OUTLINE,
                UiKit.PURPLE, TASK_W, TASK_H);
        bawangButton.setToolTipText("<html>一键完成霸王城静修全套流程（<b>全后台运行</b>）：<br>"
                + "① 按 T 回城（成都）<br>"
                + "② 右上角「寻路」→ 坐标填 10 / 16 → 点「移动」<br>"
                + "③ G 与大司马对话 → 「垓下学艺」→「送我到霸王城内」→「出发」<br>"
                + "④ 进霸王城后 G → 「我要开始静修」→「延长学艺10分钟」<br>"
                + "⑤ G →「离开霸王城」<br>"
                + "对话选项<b>靠 OCR 认字后点文字本身</b>（最优）→ 固定坐标（辅助）→ <b>Enter 确认</b>（兜底），点完还会复检；<br>"
                + "进图后会读右上角地图名，确认真的进了霸王城；<br>"
                + "<b>正常情况下不按回车</b>，仅识图+定点都失败时用 Enter 兜底，且会自动关掉误开的聊天框<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，窗口被遮挡也能跑</html>");
        bawangButton.addActionListener(e -> toggleBawangTask(controller, bawangButton));

        // 工资按钮
        final UiKit.FlatButton salaryButton = new UiKit.FlatButton("工资", Glyph.COIN, Mode.OUTLINE,
                UiKit.AMBER, TASK_W, TASK_H);
        salaryButton.setToolTipText("<html>一键完成官爵任务（领工资）（<b>全后台运行</b>）：<br>"
                + "① F11 屏蔽其他玩家 → 关广告 → O 回军团 → T 回主城<br>"
                + "② 右上角「寻路」→ 坐标填 11 / 7 → 点「移动」（约 12 秒）<br>"
                + "③ G → 逐层 Enter 确认「对话/任务」→「官爵任务」→「请交给我吧」<br>"
                + "④ 循环：点「任务追踪」面板里的红色 NPC 名 → 自动寻路过去 → G → Enter 确认<br>"
                + "⑤ 「任务追踪」里没有 NPC 名了就结束（约 4 个 NPC），跑不动会停下来提醒<br>"
                + "跑完会自动再按一次 F11 还原显示<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，窗口被遮挡也能跑</html>");
        salaryButton.addActionListener(e -> toggleSalaryTask(controller, salaryButton));

        // 组队按钮
        final UiKit.FlatButton teamButton = new UiKit.FlatButton("组队", Glyph.PEOPLE, Mode.OUTLINE,
                UiKit.TEAL, TASK_W, TASK_H);
        teamButton.setToolTipText("<html>一键组队（<b>全后台运行</b>）：<br>"
                + "① 在底部中间（技能栏上方）持续扫描「组队图标」<br>"
                + "&nbsp;&nbsp;（橙色六边形群 + 蓝色底座，图标瞬时出现、约 10 秒消失）<br>"
                + "② 找到后用模板匹配精准点中图标<br>"
                + "③ 屏幕中央弹出红色确定按钮 → 自动点它<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，游戏窗口被遮挡或切后台也能完成；<br>"
                + "最多扫描约 2 分钟，图标没出现会提醒你重试</html>");
        teamButton.addActionListener(e -> toggleTeamTask(controller, teamButton));

        // 孝廉按钮
        final UiKit.FlatButton xiaolianButton = new UiKit.FlatButton("孝廉", Glyph.BOOK, Mode.OUTLINE,
                UiKit.TEAL, TASK_W, TASK_H);
        xiaolianButton.setToolTipText("<html>一键完成「推举孝廉」答题（<b>全后台运行</b>）：<br>"
                + "<b>简化版：脚本不再寻路</b> —— 请先把角色走到成都「诰令司丞」面前，再点本按钮。<br>"
                + "① G →「对话/任务」→ 选「推举孝廉」→ 确认参加<br>"
                + "② 答题（约 10 题，每题限时 15 秒）：<br>"
                + "&nbsp;&nbsp;截图 → 系统 OCR 读题 → 本地 " + 2256 + " 条题库模糊匹配<br>"
                + "&nbsp;&nbsp;→ 点中正确选项 →（没翻页就补一次回车）<br>"
                + "③ 再对话一轮把任务交掉<br>"
                + "题库查不到时才调 DeepSeek 兜底；出现验证码会立刻弹窗提醒你手工操作<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，窗口被遮挡也能跑</html>");
        xiaolianButton.addActionListener(e -> toggleXiaolianTask(controller, xiaolianButton));

        // 打老鼠按钮 —— 进「褐仓鼠横行的粮仓」（太仓尉，子城 29,16，每天一次、耗五铢+活力）
        final UiKit.FlatButton ratButton = new UiKit.FlatButton("打老鼠", Glyph.STAR, Mode.OUTLINE,
                UiKit.RED, TASK_W, TASK_H);
        ratButton.setToolTipText("<html>一键进入「褐仓鼠横行的粮仓」打老鼠（<b>全后台运行</b>）：<br>"
                + "① O 回军团 → T 回城（确保在成都·子城）<br>"
                + "② 右上角「寻路」→ 坐标 29 / 16 →「移动」到太仓尉<br>"
                + "③ <b>点击 NPC「太仓尉」激活对话</b>（OCR 找名字，G 兜底）<br>"
                + "④ 点「进入褐仓鼠横行的粮仓」→ 消耗说明框点正文 → 回车<br>"
                + "⑤ 弹出「确定/取消」→ 自动点「确定」→ 进入粮仓，任务完成<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，窗口被遮挡也能跑</html>");
        ratButton.addActionListener(e -> toggleRatTask(controller, ratButton));

        // 一键日常按钮 —— 顺序自动跑完 工资 → 军团 → 霸王城
        final UiKit.FlatButton dailyButton = new UiKit.FlatButton("一键日常", Glyph.STAR, Mode.FILLED,
                UiKit.AMBER, TASK_W, TASK_H);
        dailyButton.setToolTipText("<html><b>一键顺序完成三件日常</b>（全后台运行）：<br>"
                + "① <b>工资</b>（官爵任务，约 1~2 分钟）<br>"
                + "② <b>军团任务</b>（按左侧轮数设置跑 N 轮）<br>"
                + "③ <b>霸王城</b>（静修 10 分钟全套）<br>"
                + "<b>不含孝廉</b>（也不含打老鼠）<br>"
                + "&nbsp;&nbsp;这几个请单独点对应按钮；<br>"
                + "上一个跑完自动开始下一个；<br>"
                + "某个失败<b>不影响后面的任务</b>，最后汇总成功几项；<br>"
                + "<b>全程不抢焦点、不移动鼠标</b>，窗口被遮挡也能跑；<br>"
                + "再点一次按钮 = 中止整条链</html>");
        dailyButton.addActionListener(e -> toggleDailyTask(controller, dailyButton));

        // 抢线按钮 —— OCR 认出目标线路并反复点击，直到切进该线路
        final UiKit.FlatButton grabLineButton = new UiKit.FlatButton("抢线", Glyph.PEOPLE, Mode.OUTLINE,
                UiKit.AMBER, TASK_W, TASK_H);
        grabLineButton.setToolTipText("<html><b>抢线</b>（切服务器线路，全后台运行）：<br>"
                + "① 点按钮 → 弹出输入框，填线路号（1~16，比如 9 = 九线）<br>"
                + "&nbsp;&nbsp;<b>填完点确定就行，不用你按任何键</b><br>"
                + "② <b>若角色在军团里</b>（军团地图 / 军团大厅，按右上角地图名条判断）：<br>"
                + "&nbsp;&nbsp;脚本先按 T 用一张回城符回城 —— 在军团里切不了线路<br>"
                + "&nbsp;&nbsp;不在军团就跳过，不浪费回城符<br>"
                + "③ <b>脚本自己按 ESC</b> 打开「系统」菜单（按之前先确认菜单没开着，免得按关掉）<br>"
                + "④ 脚本 OCR 找「服务器选线」并点击 → 弹出选线面板<br>"
                + "⑤ OCR 认出面板上每条「N线（状态）」的位置 → 点你填的那条线<br>"
                + "&nbsp;&nbsp;OCR 认不出来就退固定点位表（16 条线各有一个坐标）<br>"
                + "⑥ <b>挤不进去时</b>屏幕正中会弹「线路繁忙」提示框 —— 脚本按 Enter 关掉它，<br>"
                + "&nbsp;&nbsp;然后重新打开面板接着抢，一轮一轮挤（最多 60 轮）<br>"
                + "⑦ <b>面板消失 且 中间不再弹提示框 = 进线成功</b>；<br>"
                + "&nbsp;&nbsp;（先查弹窗再判面板：弹窗会盖住面板文字，反着判会误报成功）<br>"
                + "抢的过程中随时再点一次本按钮 = 中止</html>");
        grabLineButton.addActionListener(e -> toggleGrabLineTask(controller, grabLineButton));

        // 集体召唤按钮 —— 与「组队」同一套流程，只是要找的图标换成「传送」
        final UiKit.FlatButton summonButton = new UiKit.FlatButton("集体召唤", Glyph.GEAR, Mode.OUTLINE,
                UiKit.PURPLE, TASK_W, TASK_H);
        summonButton.setToolTipText("<html><b>集体召唤</b>（把队友传送到身边，全后台运行）：<br>"
                + "① 游戏里「集体传送」图标出现时点本按钮<br>"
                + "② 脚本扫描底部中间（技能栏上方）—— 图标位置与组队<b>完全一样</b><br>"
                + "&nbsp;&nbsp;模板 = 橙色「传送」二字 + 蓝色箭头<br>"
                + "③ 找到 → 点图标 → 点屏幕中央的「确定」<br>"
                + "图标只存在约 10 秒，最多扫 2 分钟；扫描中再点一次本按钮 = 中止</html>");
        summonButton.addActionListener(e -> toggleSummonTask(controller, summonButton));

        card.addTask(legionButton);
        card.addTask(bawangButton);
        card.addTask(salaryButton);
        card.addTask(teamButton);
        card.addTask(xiaolianButton);
        card.addTask(ratButton);
        card.addTask(dailyButton);
        card.addTask(grabLineButton);      // 抢线
        card.addTask(summonButton);        // 集体召唤（传送图标）
        // ↓↓↓ 以后新增按钮只要在这里继续 addTask(...) 即可，会自动换行、不会撑出横向滚动条

        // 开发期自检：-Dqqsg.ui.extra=5 额外塞几个占位按钮，用来验证「多加按钮也不会出横向滚动条」
        int extra = Integer.getInteger("qqsg.ui.extra", 0);
        for (int i = 0; i < extra; i++) {
            card.addTask(new UiKit.FlatButton("任务" + (i + 1), Glyph.STAR, Mode.OUTLINE,
                    UiKit.GRAY, TASK_W, TASK_H));
        }

        card.build(header);

        cards.add(card);
        controllersPanel.add(card);
        card.updateColumns(controllersScrollPane.getViewport().getWidth());
        controllersPanel.revalidate();
        controllersPanel.repaint();
        SwingUtilities.invokeLater(this::relayoutCards);
    }

    /* ================================================================== 任务逻辑 */

    /**
     * 点按钮即执行：把原本「是否开始？」确认框里的流程说明写成一行日志。
     *
     * <p>2026-09-23 应用户要求去掉所有二次确认弹窗 —— 点一下按钮直接开跑，
     * 想中止再点一次同一个按钮。信息不丢，只是从「弹窗打断」改成「日志留痕」。
     */
    private void logTaskPlan(String taskName, String plan) {
        log("\u25b6 启动\u300c" + taskName + "\u300d\uff1a" + plan);
    }

    /**
     * 「抢线」按钮逻辑：先弹输入框收线路号（1~16），再交给 {@link GrabLineTask} 去抢。
     *
     * <p>与其它按钮不同，这里<b>刻意保留一个输入框</b>（2026-09-23 用户明确要求）——
     * 每次要抢的线路号都不一样，没法做成固定参数。它只是「填参数」，不是「二次确认」：
     * 点确定后直接开跑，不再问第二遍。
     */
    private void toggleGrabLineTask(GameWindowController controller, UiKit.FlatButton button) {
        GrabLineTask current = controller.getGrabLineTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止抢线任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始抢线：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行抢线：" + controller.getWindowName());
            return;
        }

        String input = JOptionPane.showInputDialog(this,
                "要抢哪条线？（填 1 ~ 16 的数字，例如 9 = 九线）\n\n"
                        + "点「确定」就开始，不用你按任何键：\n"
                        + "脚本自己按 ESC 开系统菜单 → 点「服务器选线」→ 反复点该线路；\n"
                        + "线路挤不进去时游戏弹的「线路繁忙」提示框，\n"
                        + "脚本会自动按 Enter 关掉，然后接着抢。",
                "抢线", JOptionPane.QUESTION_MESSAGE);
        if (input == null) {
            log("抢线：已取消（没填线路号）");
            return;
        }
        int line;
        try {
            line = Integer.parseInt(input.trim());
        } catch (NumberFormatException ex) {
            log("\u26d4 抢线：线路号必须是 1~16 的数字，收到的是「" + input.trim() + "」");
            return;
        }
        if (line < 1 || line > 16) {
            log("\u26d4 抢线：线路号必须是 1~16，收到 " + line);
            return;
        }

        logTaskPlan("抢线 " + line + " 线",
                "在军团就先按 T 用回城符回城（按右上角地图名条判断，不在军团则不按）"
                        + " \u2192 脚本自己按 ESC 开系统菜单 \u2192 点\u300c服务器选线\u300d"
                        + " \u2192 OCR 认面板上每条 N线 的位置 \u2192 点 " + line + " 线"
                        + " \u2192 屏幕中间一旦弹出\u300c线路繁忙\u300d就按 Enter 关掉、接着抢"
                        + " \u2192 直到面板消失且中间不再弹提示框 = 进线成功"
                        + "（最多 60 轮，想停再点一次本按钮）");

        GrabLineTask task = new GrabLineTask(controller, new GrabLineTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "抢线任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        }, line);

        controller.setGrabLineTask(task);
        button.setRunning(true);
        task.start();
    }

    /**
     * 「军团任务」按钮逻辑：启动 / 中止军团初级任务。
     *
     * <p>把控制器 + 界面参数交给 {@link LegionTask} 执行，日志统一回灌到窗口日志区，
     * 出现异常时弹窗提醒人工处理。
     */
    private void toggleLegionTask(GameWindowController controller, UiKit.FlatButton button) {
        LegionTask current = controller.getLegionTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止军团任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始军团任务：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行军团任务：" + controller.getWindowName());
            return;
        }

        int rounds = (Integer) roundsSpinner.getValue();
        boolean hidePlayers = hidePlayersCheckBox.isSelected();

        logTaskPlan("军团任务", rounds + " 轮初级任务（关广告 \u2192 O 回军团 \u2192 名条OCR确认 \u2192 G 进入大厅 \u2192 之后每轮："
                + "G \u2192 点\u300c对话/任务\u300d \u2192 点\u300c1级军团任务\u300d \u2192 面板弹出来点\u300c确定\u300d"
                + "（面板字读不出就点确定标定位；对话自动关=已处理）；认出\u300c明日请早/做满\u300d自动收工；"
                + "OCR 认字 + 面板判据，鼠标点击不按回车；全后台）");

        LegionTask task = new LegionTask(controller, new LegionTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "军团任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        }, rounds, hidePlayers, false);

        controller.setLegionTask(task);
        button.setRunning(true);
        task.start();
    }

    /**
     * 「霸王城」按钮逻辑：启动 / 中止霸王城静修流程。
     *
     * <p>流程见 {@link BawangTask}。全程约 40 秒，会接管键鼠。
     */
    private void toggleBawangTask(GameWindowController controller, UiKit.FlatButton button) {
        BawangTask current = controller.getBawangTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止霸王城任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始霸王城：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行霸王城任务：" + controller.getWindowName());
            return;
        }

        logTaskPlan("霸王城", "T 回城 \u2192 寻路 (10,16) \u2192 垓下学艺 \u2192 送我到霸王城内 \u2192 静修 10 分钟 \u2192 离开（需在成都\u00b7子城）；对话选项 OCR 认字后点击、每步复检；识图\u2192定点\u2192Enter 只当最后一级兜底");

        BawangTask task = new BawangTask(controller, new BawangTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "霸王城结束：" + summary);
                    button.setRunning(false);
                });
            }
        });

        controller.setBawangTask(task);
        button.setRunning(true);
        task.start();
    }

    /**
     * 「工资」按钮逻辑：启动 / 中止官爵任务流程。
     *
     * <p>流程见 {@link SalaryTask}。全程约 1~2 分钟（含 4 个 NPC 的自动寻路），会接管键鼠。
     */
    private void toggleSalaryTask(GameWindowController controller, UiKit.FlatButton button) {
        SalaryTask current = controller.getSalaryTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止工资任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始工资任务：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行工资任务：" + controller.getWindowName());
            return;
        }

        logTaskPlan("工资", "F11 屏蔽玩家 \u2192 关广告 \u2192 O 回军团 \u2192 T 回城 \u2192 寻路 (11,7) \u2192 官爵任务 \u2192 逐个点任务追踪里的 NPC 交任务，跑完还原 F11（需在成都\u00b7子城）");

        SalaryTask task = new SalaryTask(controller, new SalaryTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "工资任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        });

        controller.setSalaryTask(task);
        button.setRunning(true);
        task.start();
    }

    /**
     * 「组队」按钮逻辑：启动 / 中止组队流程。
     *
     * <p>流程见 {@link TeamTask}。会在底部中间扫描组队图标（最多约 2 分钟），
     * 找到后点图标、再点屏幕中央的红色确定按钮。
     */
    private void toggleTeamTask(GameWindowController controller, UiKit.FlatButton button) {
        TeamTask current = controller.getTeamTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止组队任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始组队：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行组队任务：" + controller.getWindowName());
            return;
        }

        logTaskPlan("组队", "扫描底部组队图标 \u2192 点图标 \u2192 点中央红色确定（图标只存在约 10 秒，最多扫 2 分钟）");

        TeamTask task = new TeamTask(controller, new TeamTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "组队任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        });

        controller.setTeamTask(task);
        button.setRunning(true);
        task.start();
    }

    /**
     * 「集体召唤」按钮逻辑：启动 / 中止集体召唤（「传送」图标）流程。
     *
     * <p>与 {@link #toggleTeamTask} <b>完全同一套流程</b> —— 只是构造 {@link TeamTask}
     * 时传 {@link TeamTask.Kind#SUMMON}：扫的是「传送」图标（用户确认位置与组队图标
     * 一模一样），找到后点图标、点确定，连后台模式与中止方式都一样。
     */
    private void toggleSummonTask(GameWindowController controller, UiKit.FlatButton button) {
        TeamTask current = controller.getSummonTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止集体召唤任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始集体召唤：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行集体召唤：" + controller.getWindowName());
            return;
        }

        logTaskPlan("集体召唤", "扫描底部「传送」图标（位置同组队）\u2192 点图标 \u2192 点中央红色确定"
                + "（图标只存在约 10 秒，最多扫 2 分钟）");

        TeamTask task = new TeamTask(controller, new TeamTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "集体召唤任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        }, TeamTask.Kind.SUMMON);

        controller.setSummonTask(task);
        button.setRunning(true);
        task.start();
    }

    /**
     * 「孝廉」按钮逻辑：启动 / 中止「推举孝廉」答题流程。
     *
     * <p>流程见 {@link XiaolianTask}。最花时间的是 10 道题的读屏 + 决策，
     * 每次读屏（OCR）约 0.5 秒，如果本地题库没命中还要等 DeepSeek 2~4 秒。
     */
    private void toggleXiaolianTask(GameWindowController controller, UiKit.FlatButton button) {
        toggleQuestTask(controller, button, XiaolianTask.Mode.XIAOLIAN);
    }

    /**
     * 答题类任务（孝廉）共用的启动 / 中止逻辑。
     *
     * @param mode {@link XiaolianTask.Mode#XIAOLIAN}
     */
    private void toggleQuestTask(GameWindowController controller, UiKit.FlatButton button,
                                 XiaolianTask.Mode mode) {
        String label = mode.label();

        XiaolianTask current = controller.getTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止" + label + "任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始" + label + "：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行" + label + "任务：" + controller.getWindowName());
            return;
        }

        // 孝廉要题库（答题用）
        int bankSize = XiaolianBank.size();
        if (bankSize == 0) {
            log("\u26d4 本地题库没能加载：" + XiaolianBank.loadError()
                    + "（题库文件应该在 jar 里的 /xiaolian/xiaolian_bank.tsv）");
            return;
        }

        boolean dryRun = xiaolianDryRunCheckBox != null && xiaolianDryRunCheckBox.isSelected();

        // 执行计划写一行日志
        //   2026-09-23：孝廉按钮已「去寻路」—— 用户自己走到诰令司丞面前，脚本从 G 开始接手。
        String plan = "已假定角色站在成都\u300c诰令司丞\u300d面前（不寻路）\u2192 G 对话选\u300c推举孝廉\u300d\u2192 "
                + "答题约 10 题（本地题库 " + bankSize + " 条模糊匹配，查不到才调 DeepSeek）\u2192 对话交任务";
        logTaskPlan(label + (dryRun ? "（演练）" : ""),
                plan + (dryRun ? "；当前为演练模式，只识别不点选项" : ""));

        boolean hidePlayers = hidePlayersCheckBox == null || hidePlayersCheckBox.isSelected();

        XiaolianTask task = new XiaolianTask(controller, new XiaolianTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + label + "任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        }, dryRun, hidePlayers, XiaolianTask.resolveApiKey(), mode);

        controller.setTask(task);
        button.setRunning(true);
        task.start();
    }

    /* ================================================================== 打老鼠 */

    /**
     * 「打老鼠」按钮逻辑：启动 / 中止褐仓鼠粮仓流程。
     *
     * <p>流程见 {@link RatTask}：O 回军团 → T 回城 → 寻路 (29,16) 太仓尉 →
     * 「进入褐仓鼠横行的粮仓」→ 回车 → 确定。与工资任务同款的三段保护
     * （已运行则中止、互斥、找不到窗口提醒）。
     */
    private void toggleRatTask(GameWindowController controller, UiKit.FlatButton button) {
        RatTask current = controller.getRatTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止打老鼠任务：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始打老鼠任务：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行打老鼠任务：" + controller.getWindowName());
            return;
        }

        logTaskPlan("打老鼠", "O 回军团 \u2192 T 回城 \u2192 寻路 (29,16) \u2192 点太仓尉 \u2192 进入褐仓鼠横行的粮仓 \u2192 点正文 \u2192 回车 \u2192 点确定（需 35800 五铢 + 508 活力，每天一次）");

        RatTask task = new RatTask(controller, new RatTask.Listener() {
            @Override
            public void log(String message) {
                SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
            }

            @Override
            public void alert(String title, String message) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
                    JOptionPane.showMessageDialog(MainFrame.this, message, title,
                            JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "打老鼠任务结束：" + summary);
                    button.setRunning(false);
                });
            }
        });

        controller.setRatTask(task);
        button.setRunning(true);
        task.start();
    }

    /* ================================================================== 一键日常 */

    /** 任务实例的日志中继（一键日常的子任务共用）：日志进日志区，告警弹窗。 */
    private void relayLog(String message) {
        SwingUtilities.invokeLater(() -> MainFrame.this.log(message));
    }

    private void relayAlert(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            MainFrame.this.log("⚠ " + title + " —— " + message.replace("\n", " | "));
            JOptionPane.showMessageDialog(MainFrame.this, message, title,
                    JOptionPane.WARNING_MESSAGE);
        });
    }

    /** 任一任务（含一键日常）正在这个窗口上跑，都返回 true —— 防止两个任务同时操作一个游戏窗口。 */
    private boolean anyTaskRunning(GameWindowController controller) {
        if (controller.getDailyTask() != null && controller.getDailyTask().isRunning()) {
            return true;
        }
        if (controller.getLegionTask() != null && controller.getLegionTask().isRunning()) {
            return true;
        }
        if (controller.getBawangTask() != null && controller.getBawangTask().isRunning()) {
            return true;
        }
        if (controller.getSalaryTask() != null && controller.getSalaryTask().isRunning()) {
            return true;
        }
        if (controller.getTeamTask() != null && controller.getTeamTask().isRunning()) {
            return true;
        }
        if (controller.getSummonTask() != null && controller.getSummonTask().isRunning()) {
            return true;
        }
        if (controller.getRatTask() != null && controller.getRatTask().isRunning()) {
            return true;
        }
        if (controller.getGrabLineTask() != null && controller.getGrabLineTask().isRunning()) {
            return true;
        }
        return controller.getTask() != null && controller.getTask().isRunning();
    }

    /** 互斥检查：一键日常与任何单个任务不能同时跑（都在操作同一个游戏窗口）。 */
    private String busyTaskName(GameWindowController controller) {
        if (controller.getDailyTask() != null && controller.getDailyTask().isRunning()) {
            return "一键日常";
        }
        if (controller.getLegionTask() != null && controller.getLegionTask().isRunning()) {
            return "军团任务";
        }
        if (controller.getBawangTask() != null && controller.getBawangTask().isRunning()) {
            return "霸王城";
        }
        if (controller.getSalaryTask() != null && controller.getSalaryTask().isRunning()) {
            return "工资任务";
        }
        if (controller.getTeamTask() != null && controller.getTeamTask().isRunning()) {
            return "组队任务";
        }
        if (controller.getSummonTask() != null && controller.getSummonTask().isRunning()) {
            return "集体召唤";
        }
        if (controller.getTask() != null && controller.getTask().isRunning()) {
            return "孝廉任务";
        }
        if (controller.getRatTask() != null && controller.getRatTask().isRunning()) {
            return "打老鼠任务";
        }
        if (controller.getGrabLineTask() != null && controller.getGrabLineTask().isRunning()) {
            return "抢线";
        }
        return null;
    }

    /**
     * 「一键日常」按钮逻辑：按 <b>工资 → 军团 → 霸王城</b> 顺序跑完三个任务。
     *
     * <p>三个任务实例在这里临时创建（各自带日志中继 listener），交给
     * {@link SequenceTask} 顺序同步执行 {@code runOnce()}；某个失败不影响后面的。
     * <b>不含孝廉</b>（2026-09-21 应用户要求移出一键序列，仍可单独点「孝廉」按钮跑）。
     * 再点一次按钮 = 中止整条链（当前任务立即停，后面的跳过）。
     */
    private void toggleDailyTask(GameWindowController controller, UiKit.FlatButton button) {
        SequenceTask current = controller.getDailyTask();
        if (current != null && current.isRunning()) {
            current.requestStop();
            log("已请求中止一键日常：" + controller.getWindowName());
            return;
        }

        String busy = busyTaskName(controller);
        if (busy != null) {
            log("「" + busy + "」正在运行，不能同时开始一键日常：" + controller.getWindowName());
            return;
        }

        if (!controller.isWindowAvailable()) {
            log("找不到游戏窗口，无法执行一键日常：" + controller.getWindowName());
            return;
        }

        // 一键日常不含孝廉 —— 不需要题库（孝廉单独跑时仍会自带题库检查）
        int rounds = (Integer) roundsSpinner.getValue();
        boolean hidePlayers = hidePlayersCheckBox == null || hidePlayersCheckBox.isSelected();

        logTaskPlan("一键日常", "工资 \u2192 军团（" + rounds + " 轮）\u2192 霸王城，共 3 件；上一个跑完自动接下一个，失败不影响后续，最后汇总 N/3（不含孝廉/打老鼠）");

        SalaryTask salary = new SalaryTask(controller, new SalaryTask.Listener() {
            @Override
            public void log(String message) {
                relayLog(message);
            }

            @Override
            public void alert(String title, String message) {
                relayAlert(title, message);
            }

            @Override
            public void finished(boolean success, String summary) {
                // runOnce() 不回调 finished；一键的结束由 SequenceTask 统一汇报
            }
        });

        LegionTask legion = new LegionTask(controller, new LegionTask.Listener() {
            @Override
            public void log(String message) {
                relayLog(message);
            }

            @Override
            public void alert(String title, String message) {
                relayAlert(title, message);
            }

            @Override
            public void finished(boolean success, String summary) {
            }
        }, rounds, hidePlayers, false);

        BawangTask bawang = new BawangTask(controller, new BawangTask.Listener() {
            @Override
            public void log(String message) {
                relayLog(message);
            }

            @Override
            public void alert(String title, String message) {
                relayAlert(title, message);
            }

            @Override
            public void finished(boolean success, String summary) {
            }
        });

        XiaolianTask xiaolian = new XiaolianTask(controller, new XiaolianTask.Listener() {
            @Override
            public void log(String message) {
                relayLog(message);
            }

            @Override
            public void alert(String title, String message) {
                relayAlert(title, message);
            }

            @Override
            public void finished(boolean success, String summary) {
            }
        }, false, hidePlayers, XiaolianTask.resolveApiKey(), XiaolianTask.Mode.XIAOLIAN);

        SequenceTask seq = new SequenceTask(controller, new SequenceTask.Listener() {
            @Override
            public void log(String message) {
                relayLog(message);
            }

            @Override
            public void alert(String title, String message) {
                relayAlert(title, message);
            }

            @Override
            public void finished(boolean success, String summary) {
                SwingUtilities.invokeLater(() -> {
                    MainFrame.this.log((success ? "✅ " : "⛔ ") + "一键日常结束：" + summary);
                    button.setRunning(false);
                });
            }
        }, salary, legion, bawang, xiaolian);

        controller.setDailyTask(seq);
        button.setRunning(true);
        seq.start();
    }

    /* ================================================================== 其它 */
    public void removeController(GameWindowController controller) {
        controllers.remove(controller);
        log("Removed control window: " + controller.getWindowName());
    }

    private void stopAllControllers() {
        for (GameWindowController controller : controllers) {
            controller.stop();
        }
    }

    public void log(String message) {
        Date now = new Date();
        logArea.append(now.toString() + ": " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        // 同时打到标准输出：以「java -jar > 日志文件」或 qqsg_gui.log（tee）留档 / 事后排查。
        // 带时间戳，才能看出每一步耗时、卡在哪。
        System.out.println("[" + now.toString() + "] " + message);
        System.out.flush();
    }

    public boolean isMinimizeAllowed() {
        return minimizeCheckBox.isSelected();
    }
}
