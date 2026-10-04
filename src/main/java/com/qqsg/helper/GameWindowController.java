package com.qqsg.helper;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import com.sun.jna.platform.win32.WinDef.HWND;

public class GameWindowController {
    private String windowName;
    private int processId;
    private Thread skillThread;
    private Thread hideAcceptThread;
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean hideAcceptRunning = new AtomicBoolean(false);
    private Set<Character> pressedKeys = new HashSet<>();
    private Robot robot;
    private boolean runInBackground = false;
    private long erLastReleasedTime = 0;
    private boolean isControlTested = false;
    private static final long ER_RELEASE_INTERVAL = 3 * 60 * 1000; // 3 minutes
    private static final long HIDE_ACCEPT_DURATION = 60 * 1000; // 1 minute

    /** 当前窗口上正在运行的军团任务（可能为 null）。 */
    private LegionTask legionTask;

    /** 当前窗口上正在运行的霸王城任务（可能为 null）。 */
    private BawangTask bawangTask;

    /** 当前窗口上正在运行的工资（官爵任务）任务（可能为 null）。 */
    private SalaryTask salaryTask;

    /** 当前窗口上正在运行的组队任务（可能为 null）。 */
    private TeamTask teamTask;

    /**
     * 当前窗口上正在运行的「集体召唤」任务（可能为 null）。
     *
     * <p>与 {@link #teamTask} 是同一个类（{@link TeamTask}），只是用
     * {@link TeamTask.Kind#SUMMON} 构造 —— 找的是「传送」图标，位置与组队图标一样。
     */
    private TeamTask summonTask;

    /** 当前窗口上正在运行的孝廉（推举孝廉）任务（可能为 null）。 */
    private XiaolianTask xiaolianTask;

    /** 当前窗口上正在运行的打老鼠（褐仓鼠粮仓）任务（可能为 null）。 */
    private RatTask ratTask;

    /** 当前窗口上正在运行的抢线任务（可能为 null）。 */
    private GrabLineTask grabLineTask;

    /** 「一键日常」序列（工资 → 军团 → 霸王城）。 */
    private SequenceTask dailyTask;
    
    // Skills to release in sequence: A, S, D, F, Q, W
    private static final char[] SKILL_SEQUENCE = {'A', 'S', 'D', 'F', 'Q', 'W', 'C'};
    
    // Image paths for hide accept function - 修改为正确的资源路径格式
    private static final String ENTER_BUTTON_PATH = "/images/enter_button.png";
    private static final String CONFIRM_BUTTON_PATH = "/images/confirm_button.png";
    private static final String COIN_BUTTON_PATH = "/images/coin_button.png";
    private static final String ITEM_BUTTON_PATH = "/images/item_button.png";
    private static final String STAR_BUTTON_PATH = "/images/star_button.png";
    
    // 备选路径列表，增加查找成功率
    private static final String[] IMAGE_PATHS = {
        ENTER_BUTTON_PATH,
        CONFIRM_BUTTON_PATH,
        COIN_BUTTON_PATH,
        ITEM_BUTTON_PATH,
        STAR_BUTTON_PATH
    };
    
    public GameWindowController(String windowName, int processId) {
        System.out.println("Initializing GameWindowController for window: " + windowName + " with PID: " + processId);
        this.windowName = windowName;
        this.processId = processId;
        
        // Validate window existence and accessibility
        try {
            boolean windowAccessible = false;
            if (processId > 0) {
                // Try to find the window using the PID
                System.out.println("Attempting to find window with PID: " + processId);
                com.sun.jna.platform.win32.WinDef.HWND hwnd = WindowUtils.findWindowByPid(processId);
                windowAccessible = (hwnd != null);
                System.out.println("Window found: " + windowAccessible + ", Handle: " + hwnd);
            } else {
                System.out.println("No valid PID provided, cannot verify window accessibility");
            }
            
            try {
                this.robot = new Robot();
                System.out.println("Robot instance created successfully");
            } catch (AWTException e) {
                System.err.println("Failed to create Robot instance: " + e.getMessage());
                this.robot = null;
            }
            
            System.out.println("GameWindowController initialized for: " + windowName + ", Accessible: " + windowAccessible);
        } catch (Exception e) {
            System.err.println("Error during GameWindowController initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public String getWindowName() {
        return windowName;
    }
    
    public int getProcessId() {
        return processId;
    }
    
    public boolean isRunning() {
        return running.get();
    }
    
    public boolean isRunInBackground() {
        return runInBackground;
    }

    /** 取当前窗口上的军团任务实例（未创建过为 null）。 */
    public LegionTask getLegionTask() {
        return legionTask;
    }

    /** 记录当前窗口上的军团任务实例。 */
    public void setLegionTask(LegionTask task) {
        this.legionTask = task;
    }

    /** 取当前窗口上的霸王城任务实例（未创建过为 null）。 */
    public BawangTask getBawangTask() {
        return bawangTask;
    }

    /** 记录当前窗口上的霸王城任务实例。 */
    public void setBawangTask(BawangTask task) {
        this.bawangTask = task;
    }

    /** 取当前窗口上的工资（官爵任务）实例（未创建过为 null）。 */
    public SalaryTask getSalaryTask() {
        return salaryTask;
    }

    /** 记录当前窗口上的工资（官爵任务）实例。 */
    public void setSalaryTask(SalaryTask task) {
        this.salaryTask = task;
    }

    /** 取当前窗口上的打老鼠任务实例（未创建过为 null）。 */
    public RatTask getRatTask() {
        return ratTask;
    }

    /** 记录当前窗口上的打老鼠任务实例。 */
    public void setRatTask(RatTask task) {
        this.ratTask = task;
    }

    /** 取当前窗口上的抢线任务实例（未创建过为 null）。 */
    public GrabLineTask getGrabLineTask() {
        return grabLineTask;
    }

    /** 记录当前窗口上的抢线任务实例。 */
    public void setGrabLineTask(GrabLineTask task) {
        this.grabLineTask = task;
    }

    /** 取当前窗口上的组队任务实例（未创建过为 null）。 */
    public TeamTask getTeamTask() {
        return teamTask;
    }

    /** 记录当前窗口上的组队任务实例。 */
    public void setTeamTask(TeamTask task) {
        this.teamTask = task;
    }

    /** 取当前窗口上的集体召唤（传送）任务实例（未创建过为 null）。 */
    public TeamTask getSummonTask() {
        return summonTask;
    }

    /** 记录当前窗口上的集体召唤（传送）任务实例。 */
    public void setSummonTask(TeamTask task) {
        this.summonTask = task;
    }

    /** 取当前窗口上的孝廉任务实例（未创建过为 null）。 */
    public XiaolianTask getXiaolianTask() {
        return xiaolianTask;
    }

    /** 记录当前窗口上的孝廉任务实例。 */
    public void setXiaolianTask(XiaolianTask task) {
        this.xiaolianTask = task;
    }

    /** 取当前窗口上的「一键日常」序列实例（未创建过为 null）。 */
    public SequenceTask getDailyTask() {
        return dailyTask;
    }

    /** 记录当前窗口上的「一键日常」序列实例。 */
    public void setDailyTask(SequenceTask task) {
        this.dailyTask = task;
    }

    /** 「孝廉」和「运送物资」共用同一个任务槽（同一套寻路 / 对话逻辑，只是模式不同）。 */
    public XiaolianTask getTask() {
        return xiaolianTask;
    }

    public void setTask(XiaolianTask task) {
        this.xiaolianTask = task;
    }

    public void setRunInBackground(boolean runInBackground) {
        this.runInBackground = runInBackground;
    }
    
    public synchronized void start() {
        if (running.get()) {
            System.out.println("Controller already running for window: " + windowName);
            return;
        }
        
        running.set(true);
        erLastReleasedTime = System.currentTimeMillis();
        
        // 测试控制功能
        testControlFunctionality();
        
        skillThread = new Thread(() -> {
            try {
                while (running.get()) {
                    // Release normal skills in sequence
                    for (char key : SKILL_SEQUENCE) {
                        if (!running.get()) break;
                        pressKey(key);
                        Thread.sleep(100); // Short delay between skills
                    }
                    
                    // Check if it's time to release ER skills
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - erLastReleasedTime >= ER_RELEASE_INTERVAL) {
                        pressKey('E');
                        Thread.sleep(100);
                        pressKey('R');
                        erLastReleasedTime = currentTime;
                    }
                    
                    Thread.sleep(500); // Main delay between skill sequences
                }
            } catch (InterruptedException e) {
                System.out.println("Skill thread interrupted for window: " + windowName);
            } finally {
                // Ensure all keys are released
                releaseAllKeys();
                running.set(false);
            }
        });
        
        skillThread.start();
        System.out.println("Started skill release for window: " + windowName + " (PID: " + processId + ")");
    }
    
    public synchronized void startHideAccept() {
        // If already running, stop it first
        if (hideAcceptRunning.get()) {
            stopHideAccept();
            return;
        }
        
        hideAcceptRunning.set(true);
        final long startTime = System.currentTimeMillis();
        
        System.out.println("===================================");
        System.out.println("Starting hide accept function for window: " + windowName + " (PID: " + processId + ")");
        System.out.println("Current window: " + windowName);
        System.out.println("Run in background: " + runInBackground);
        
        // 列出所有可用的图片资源
        System.out.println("Available image resources:");
        for (String path : IMAGE_PATHS) {
            boolean resourceExists = getClass().getResource(path) != null;
            System.out.println("  " + path + " - " + (resourceExists ? "Found" : "Not found"));
            // 检查文件系统路径
            File file = new File("src/main/resources" + path);
            System.out.println("  File path: " + file.getAbsolutePath() + " - " + (file.exists() ? "Exists" : "Not exists"));
        }
        
        System.out.println("Start time: " + new Date(startTime));
        System.out.println("Will run until: " + new Date(startTime + HIDE_ACCEPT_DURATION));
        
        final AtomicInteger iterationCount = new AtomicInteger(0);
        final AtomicInteger buttonFoundCount = new AtomicInteger(0);
        
        hideAcceptThread = new Thread(() -> {
            try {
                while (hideAcceptRunning.get() && (System.currentTimeMillis() - startTime < HIDE_ACCEPT_DURATION)) {
                    int currentIteration = iterationCount.incrementAndGet();
                    long elapsed = System.currentTimeMillis() - startTime;
                    
                    if (currentIteration % 10 == 0) { // 每10次迭代显示一次进度
                        System.out.println("Iteration " + currentIteration + ", Elapsed: " + (elapsed/1000) + "s, Buttons found: " + buttonFoundCount.get());
                    }
                    
                    // 在后台运行时保持窗口焦点
                    if (!runInBackground) {
                        // 确保窗口有焦点
                        System.out.println("Activating window: " + windowName);
                        // 注释掉activateWindowByTitle调用，因为该方法不存在
                        System.out.println("[WINDOW] Would activate window: " + windowName + " if activation method was available");
                        Thread.sleep(100); // 给窗口时间获得焦点
                    } else {
                        System.out.println("Running in background mode");
                    }
                    
                    // Find and click all target buttons
                    boolean foundAny = false;
                    
                    // 按顺序尝试所有按钮，增加查找成功率
                    for (String buttonPath : IMAGE_PATHS) {
                        System.out.println("\nTrying button: " + buttonPath);
                        boolean found = findAndClickButton(buttonPath);
                        if (found) {
                            foundAny = true;
                            buttonFoundCount.incrementAndGet();
                            // 如果找到按钮，稍微等待一下再继续
                            Thread.sleep(1000);
                            break; // 找到一个按钮后暂停一下再继续
                        }
                    }
                    
                    if (foundAny) {
                        System.out.println("✓ Found and clicked a button in iteration " + currentIteration);
                    } else {
                        System.out.println("✗ No buttons found in iteration " + currentIteration);
                    }
                    
                    // Check if we should stop
                    if (!hideAcceptRunning.get()) break;
                    
                    Thread.sleep(300); // 调整为固定的等待时间
                }
            } catch (InterruptedException e) {
                System.out.println("Hide accept thread interrupted for window: " + windowName);
            } finally {
                hideAcceptRunning.set(false);
                System.out.println("\n===================================");
                System.out.println("Hide accept function stopped after 1 minute for window: " + windowName);
                System.out.println("Total iterations: " + iterationCount.get());
                System.out.println("Total buttons found: " + buttonFoundCount.get());
                System.out.println("End time: " + new Date());
                System.out.println("===================================");
            }
        });
        
        hideAcceptThread.start();
    }
    
    public synchronized void stopHideAccept() {
        if (!hideAcceptRunning.get()) {
            System.out.println("Hide accept already stopped for window: " + windowName);
            return;
        }
        
        hideAcceptRunning.set(false);
        if (hideAcceptThread != null && hideAcceptThread.isAlive()) {
            hideAcceptThread.interrupt();
        }
        
        System.out.println("Stopped hide accept function for window: " + windowName);
    }
    
    public boolean isHideAcceptRunning() {
        return hideAcceptRunning.get();
    }
    
    private boolean findAndClickButton(String imagePath) {
        try {
            // 添加详细调试信息
            System.out.println("Looking for button: " + imagePath);
            
            // 检查资源是否存在
            if (getClass().getResource(imagePath) == null) {
                System.out.println("Resource not found: " + imagePath);
                System.out.println("Classpath: " + System.getProperty("java.class.path"));
                // 尝试备选路径
                String altPath = "/images/" + new File(imagePath).getName();
                if (getClass().getResource(altPath) != null) {
                    imagePath = altPath;
                    System.out.println("Using alternative path: " + imagePath);
                }
            }
            
            // Capture screen
            System.out.println("Capturing screen...");
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screenImage = robot.createScreenCapture(screenRect);
            System.out.println("Screen captured: " + screenRect.width + "x" + screenRect.height);
            
            // Load template image from resources
            System.out.println("Loading template image from: " + imagePath);
            BufferedImage template = ImageIO.read(getClass().getResource(imagePath));
            if (template == null) {
                System.out.println("Failed to load template image: " + imagePath);
                // 尝试从文件系统直接加载作为备选方案
                try {
                    String absolutePath = "src/main/resources" + imagePath;
                    File file = new File(absolutePath);
                    if (file.exists()) {
                        template = ImageIO.read(file);
                        System.out.println("Loaded template from file system: " + absolutePath);
                    }
                } catch (Exception e) {
                    System.out.println("Failed to load from file system: " + e.getMessage());
                }
                if (template == null) {
                    return false;
                }
            }
            
            System.out.println("Template image loaded: " + template.getWidth() + "x" + template.getHeight());
            
            // 降低相似度阈值，提高匹配成功率
            System.out.println("Starting image matching with lower threshold...");
            Point matchPoint = findImageOnScreen(screenImage, template, 0.5); // 进一步降低到50%相似度
            
            if (matchPoint != null) {
                // Click the center of the found image
                int clickX = matchPoint.x + template.getWidth() / 2;
                int clickY = matchPoint.y + template.getHeight() / 2;
                
                // Move to position and click
                System.out.println("Moving mouse to position: " + clickX + ", " + clickY);
                robot.mouseMove(clickX, clickY);
                Thread.sleep(100); // 增加延迟确保鼠标移动到位
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
                Thread.sleep(100); // 增加点击持续时间
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
                Thread.sleep(100); // 增加点击后延迟
                
                System.out.println("Found and clicked button: " + imagePath + " at position: " + clickX + ", " + clickY);
                return true;
            } else {
                System.out.println("No match found for: " + imagePath);
            }
        } catch (Exception e) {
            System.err.println("Error finding button " + imagePath + ": " + e.getMessage());
            //e.printStackTrace();
        }
        return false;
    }
    
    // Improved image matching algorithm with better performance and accuracy
    private Point findImageOnScreen(BufferedImage screen, BufferedImage template, double threshold) {
        int screenWidth = screen.getWidth();
        int screenHeight = screen.getHeight();
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        
        System.out.println("Screen size: " + screenWidth + "x" + screenHeight + ", Template size: " + templateWidth + "x" + templateHeight);
        System.out.println("Matching threshold: " + threshold);
        
        double bestScore = -1;
        Point bestPoint = null;
        
        // 使用更小的步长以提高匹配准确性
        int stepSize = Math.max(1, Math.min(templateWidth, templateHeight) / 8);
        System.out.println("Search step size: " + stepSize);
        
        // 快速预搜索，先找到可能的匹配区域
        for (int y = 0; y <= screenHeight - templateHeight; y += stepSize) {
            for (int x = 0; x <= screenWidth - templateWidth; x += stepSize) {
                double score = compareImages(screen, x, y, template);
                
                // 调试信息，每1000步显示一次进度
                if ((x + y) % 10000 < stepSize * 2) {
                    System.out.println("Quick search at: " + x + ", " + y + ", Score: " + score);
                }
                
                if (score > threshold && score > bestScore) {
                    bestScore = score;
                    bestPoint = new Point(x, y);
                    System.out.println("Found potential match: " + bestScore + " at " + x + ", " + y);
                }
            }
        }
        
        // 如果找到潜在匹配，在周围进行更精确的搜索
        if (bestPoint != null) {
            System.out.println("Performing precise search around best match...");
            int preciseSearchRadius = stepSize * 2;
            int startX = Math.max(0, bestPoint.x - preciseSearchRadius);
            int startY = Math.max(0, bestPoint.y - preciseSearchRadius);
            int endX = Math.min(screenWidth - templateWidth, bestPoint.x + preciseSearchRadius);
            int endY = Math.min(screenHeight - templateHeight, bestPoint.y + preciseSearchRadius);
            
            for (int y = startY; y <= endY; y += 1) { // 步长为1，精确搜索
                for (int x = startX; x <= endX; x += 1) {
                    double score = compareImages(screen, x, y, template);
                    
                    if (score > bestScore) {
                        bestScore = score;
                        bestPoint = new Point(x, y);
                        System.out.println("Found better precise match: " + bestScore + " at " + x + ", " + y);
                    }
                }
            }
        }
        
        if (bestPoint != null) {
            System.out.println("Best match found with score: " + bestScore + " at " + bestPoint.x + ", " + bestPoint.y);
        }
        
        return bestPoint;
    }
    
    // Improved image comparison using normalized color difference
    private double compareImages(BufferedImage screen, int startX, int startY, BufferedImage template) {
        int templateWidth = template.getWidth();
        int templateHeight = template.getHeight();
        
        // 跳过过大的比较以提高性能
        if (startX + templateWidth > screen.getWidth() || startY + templateHeight > screen.getHeight()) {
            return -1;
        }
        
        double totalDifference = 0;
        int pixelCount = 0;
        
        // 采样比较，每隔几个像素比较一次，提高性能
        int sampleStep = 2;
        
        for (int y = 0; y < templateHeight; y += sampleStep) {
            for (int x = 0; x < templateWidth; x += sampleStep) {
                int screenPixel = screen.getRGB(startX + x, startY + y);
                int templatePixel = template.getRGB(x, y);
                
                // 获取RGB分量
                int screenR = (screenPixel >> 16) & 0xFF;
                int screenG = (screenPixel >> 8) & 0xFF;
                int screenB = screenPixel & 0xFF;
                
                int templateR = (templatePixel >> 16) & 0xFF;
                int templateG = (templatePixel >> 8) & 0xFF;
                int templateB = templatePixel & 0xFF;
                
                // 计算颜色差异（使用欧几里得距离的平方）
                double diffR = screenR - templateR;
                double diffG = screenG - templateG;
                double diffB = screenB - templateB;
                double pixelDifference = diffR * diffR + diffG * diffG + diffB * diffB;
                
                totalDifference += pixelDifference;
                pixelCount++;
            }
        }
        
        if (pixelCount == 0) return -1;
        
        // 计算平均差异
        double averageDifference = totalDifference / pixelCount;
        
        // 转换为相似度分数（0-1之间，1表示完全匹配）
        // 颜色值范围是0-255，RGB三个通道的最大差异平方和为 (255*255)*3
        double maxPossibleDifference = (255 * 255 * 3);
        double similarity = 1.0 - (averageDifference / maxPossibleDifference);
        
        // 调试信息，偶尔输出相似度分数
        if (Math.random() < 0.001) { // 只输出一小部分比较结果，避免日志过多
            System.out.println("Similarity at " + startX + "," + startY + ": " + similarity);
        }
        
        return similarity;
    }
    
    // Simple Point class since java.awt.Point might not be needed elsewhere
    private static class Point {
        int x, y;
        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
    
    public synchronized void stop() {
        // 停止时一并中止军团任务，避免技能线程停了任务还在操作窗口
        if (legionTask != null && legionTask.isRunning()) {
            legionTask.requestStop();
        }
        // 霸王城任务同理
        if (bawangTask != null && bawangTask.isRunning()) {
            bawangTask.requestStop();
        }
        // 工资（官爵任务）同理
        if (salaryTask != null && salaryTask.isRunning()) {
            salaryTask.requestStop();
        }
        // 组队同理
        if (teamTask != null && teamTask.isRunning()) {
            teamTask.requestStop();
        }
        // 打老鼠同理
        if (ratTask != null && ratTask.isRunning()) {
            ratTask.requestStop();
        }
        if (!running.get()) {
            System.out.println("Controller already stopped for window: " + windowName);
            return;
        }
        
        running.set(false);
        if (skillThread != null && skillThread.isAlive()) {
            skillThread.interrupt();
        }
        
        // Ensure all keys are released
        releaseAllKeys();
        
        System.out.println("Stopped skill release for window: " + windowName);
    }
    
    private void log(String message) {
        System.out.println("[" + windowName + " - PID: " + processId + "] " + message);
    }
    
    /**
     * 测试控制功能是否正常工作
     */
    private void testControlFunctionality() {
        if (isControlTested) return;
        
        log("Testing control functionality...");
        try {
            // 发送一个测试按键（非功能键，避免干扰实际操作）
            char testKey = ' '; // 空格键
            pressKey(testKey);
            log("Control test executed - key press simulated");
            isControlTested = true;
        } catch (Exception e) {
            log("Error during control test: " + e.getMessage());
        }
    }
    
    /**
     * 测试后台截图功能
     * @return 是否成功截图
     */
    public boolean testBackgroundScreenshot() {
        log("=== 开始测试后台截图功能 ===");
        try {
            // 1. 首先测试全屏幕截图（作为参考）
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage fullScreenshot = robot.createScreenCapture(screenRect);
            
            // 保存全屏幕截图
            String fullScreenshotPath = "screenshot_test_full.png";
            ImageIO.write(fullScreenshot, "png", new File(fullScreenshotPath));
            System.out.println("全屏幕截图成功，已保存到: " + fullScreenshotPath);
            
            // 2. 测试真正的后台窗口截图（不需要窗口前置）
            if (processId > 0) {
                System.out.println("开始尝试后台截图窗口: " + windowName + " (PID: " + processId + ")");
                
                // 使用JNA通过进程ID获取窗口句柄
                com.sun.jna.platform.win32.WinDef.HWND hwnd = WindowUtils.findWindowByPid(processId);
                if (hwnd != null) {
                    // 尝试获取窗口矩形
                    com.sun.jna.platform.win32.WinDef.RECT rect = new com.sun.jna.platform.win32.WinDef.RECT();
                    boolean success = com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(hwnd, rect);
                    
                    if (success) {
                        int width = rect.right - rect.left;
                        int height = rect.bottom - rect.top;
                        System.out.println("窗口尺寸: " + width + "x" + height);
                        
                        // 创建兼容的BufferedImage
                        BufferedImage windowScreenshot = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        Graphics g = windowScreenshot.getGraphics();
                        
                        // 使用Java的Robot在后台获取窗口图像（不需要前置窗口）
                        // 注意：这里使用的是GetWindowRect获取的坐标，而不是直接屏幕截图
                        Rectangle windowRect = new Rectangle(rect.left, rect.top, width, height);
                        BufferedImage capture = robot.createScreenCapture(windowRect);
                        g.drawImage(capture, 0, 0, null);
                        g.dispose();
                        
                        // 保存窗口截图
                        String windowScreenshotPath = "screenshot_test_window.png";
                        ImageIO.write(windowScreenshot, "png", new File(windowScreenshotPath));
                        System.out.println("后台窗口截图成功，已保存到: " + windowScreenshotPath);
                        
                        // 显示窗口截图
                        showScreenshot(windowScreenshot, "后台窗口截图 - " + windowName);
                        
                        // 也显示全屏截图作为对比
                        showScreenshot(fullScreenshot, "全屏幕截图(对比用)");
                        
                        log("=== 后台截图功能测试完成 ===");
                        return true;
                    } else {
                        System.out.println("无法获取窗口矩形");
                    }
                } else {
                    System.out.println("无法获取窗口句柄");
                }
            }
            
            // 如果没有窗口或截图失败，至少显示全屏截图
            showScreenshot(fullScreenshot, "全屏幕截图");
            log("=== 后台截图功能测试完成 ===");
            return true;
        } catch (Exception e) {
            log("截图测试失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 显示截图的辅助方法
     */
    private void showScreenshot(final BufferedImage screenshot, final String title) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.JFrame frame = new javax.swing.JFrame(title);
            javax.swing.JLabel label = new javax.swing.JLabel();
            
            // 缩放图像以适应窗口，最大宽度800，高度600
            int maxWidth = 800;
            int maxHeight = 600;
            int imgWidth = screenshot.getWidth();
            int imgHeight = screenshot.getHeight();
            
            double scale = 1.0;
            if (imgWidth > maxWidth || imgHeight > maxHeight) {
                double scaleX = (double) maxWidth / imgWidth;
                double scaleY = (double) maxHeight / imgHeight;
                scale = Math.min(scaleX, scaleY);
            }
            
            int newWidth = (int) (imgWidth * scale);
            int newHeight = (int) (imgHeight * scale);
            java.awt.Image scaledImage = screenshot.getScaledInstance(newWidth, newHeight, java.awt.Image.SCALE_SMOOTH);
            
            label.setIcon(new javax.swing.ImageIcon(scaledImage));
            frame.add(new javax.swing.JScrollPane(label));
            frame.setSize(newWidth + 50, newHeight + 50);
            frame.setLocationRelativeTo(null); // 居中显示
            frame.setDefaultCloseOperation(javax.swing.JFrame.DISPOSE_ON_CLOSE);
            frame.setVisible(true);
            
            System.out.println("截图显示窗口已打开: " + title);
        });
    }
    
    /**
     * 测试图像匹配并返回位置
     * @param templatePath 模板图像路径
     * @return 找到的图像位置，未找到返回null
     */
    public Point testImageMatching(String templatePath) {
        log("=== 开始测试图像匹配功能 ===");
        try {
            // 加载模板图像
            BufferedImage template = ImageIO.read(getClass().getResource(templatePath));
            if (template == null) {
                // 尝试从文件系统加载
                File file = new File("src/main/resources" + templatePath);
                if (file.exists()) {
                    template = ImageIO.read(file);
                }
                if (template == null) {
                    log("无法加载模板图像: " + templatePath);
                    return null;
                }
            }
            
            System.out.println("模板图像加载成功，尺寸: " + template.getWidth() + "x" + template.getHeight());
            
            // 截图并匹配
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screenImage = robot.createScreenCapture(screenRect);
            System.out.println("正在执行图像匹配...");
            
            // 使用较低的阈值提高匹配成功率
            Point matchPoint = findImageOnScreen(screenImage, template, 0.4);
            
            if (matchPoint != null) {
                log("图像匹配成功! 位置: (" + matchPoint.x + ", " + matchPoint.y + ")");
            } else {
                log("未找到匹配的图像");
            }
            
            log("=== 图像匹配功能测试完成 ===");
            return matchPoint;
        } catch (Exception e) {
            log("图像匹配测试失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 测试点击找到的图像位置
     * @param point 要点击的位置
     * @param isDoubleClick 是否双击
     * @return 是否点击成功
     */
    public boolean testClickAtPosition(Point point, boolean isDoubleClick) {
        if (point == null) {
            log("无效的点击位置");
            return false;
        }
        
        log("=== 开始测试点击功能 ===");
        try {
            System.out.println("移动鼠标到位置: (" + point.x + ", " + point.y + ")");
            robot.mouseMove(point.x, point.y);
            Thread.sleep(100); // 等待鼠标移动到位
            
            if (isDoubleClick) {
                System.out.println("执行双击操作");
                // 第一次点击
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
                Thread.sleep(50);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
                Thread.sleep(50);
                // 第二次点击（双击）
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
                Thread.sleep(50);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
            } else {
                System.out.println("执行单击操作");
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
                Thread.sleep(50);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
            }
            
            log("点击操作执行完成");
            log("=== 点击功能测试完成 ===");
            return true;
        } catch (Exception e) {
            log("点击测试失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 优化版找图并双击的完整流程
     * @param templatePath 模板图像路径
     * @return 是否成功找到并点击
     */
    public boolean optimizedFindAndDoubleClick(String templatePath) {
        log("=== 开始优化版找图双击流程 ===");
        
        // 1. 先测试后台截图
        boolean screenshotSuccess = testBackgroundScreenshot();
        if (!screenshotSuccess) {
            log("截图失败，终止找图流程");
            return false;
        }
        
        // 2. 执行图像匹配
        Point matchPoint = testImageMatching(templatePath);
        if (matchPoint == null) {
            log("图像匹配失败，终止流程");
            return false;
        }
        
        // 3. 点击找到的位置（双击）
        boolean clickSuccess = testClickAtPosition(matchPoint, true);
        
        log("=== 优化版找图双击流程完成 ===");
        return clickSuccess;
    }
    
    // ==================== 军团任务自动化所需的底层能力 ====================

    /** 坐标基准窗口尺寸：所有「窗口内坐标」均以 1030x797 为基准，实际窗口会等比缩放。 */
    public static final int BASE_WIDTH  = 1030;
    public static final int BASE_HEIGHT = 797;

    /** 缓存的游戏窗口句柄（避免每次调用都重新枚举全部窗口 + 刷屏日志）。 */
    private volatile com.sun.jna.platform.win32.WinDef.HWND cachedHwnd = null;

    /**
     * 游戏窗口句柄；找不到返回 null。
     *
     * <p><b>带缓存</b>：{@code WindowUtils.findWindowByPid} 每次都会枚举该进程的全部
     * 窗口并逐条打印日志，而本方法在一次点击/截图里会被调用好几次（getWindowRect、
     * ClientToScreen、枚举子窗……），不加缓存会瞬间刷出几百行日志。
     * 句柄在游戏运行期间不变，缓存是安全的；窗口真关了会置空重找。
     */
    public com.sun.jna.platform.win32.WinDef.HWND findHwnd() {
        if (processId <= 0) return null;
        com.sun.jna.platform.win32.WinDef.HWND h = cachedHwnd;
        if (h != null && com.sun.jna.platform.win32.User32.INSTANCE.IsWindow(h)) {
            return h;
        }
        h = WindowUtils.findWindowByPid(processId);
        cachedHwnd = h;
        return h;
    }

    /** 游戏窗口矩形（屏幕坐标，含标题栏与边框）。 */
    public Rectangle getWindowRect() {
        com.sun.jna.platform.win32.WinDef.HWND hwnd = findHwnd();
        if (hwnd == null) return null;
        com.sun.jna.platform.win32.WinDef.RECT r = new com.sun.jna.platform.win32.WinDef.RECT();
        if (!com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(hwnd, r)) return null;
        int w = r.right - r.left;
        int h = r.bottom - r.top;
        if (w <= 0 || h <= 0) return null;
        return new Rectangle(r.left, r.top, w, h);
    }

    /** 游戏窗口是否仍然存在（游戏是否还在运行）。 */
    public boolean isWindowAvailable() {
        return findHwnd() != null;
    }

    /**
     * 把游戏窗口强制切到前台。
     *
     * <p>SetForegroundWindow 受 Windows 前台锁定限制，直接调用往往无效；
     * 这里用 AttachThreadInput 把当前线程与前台窗口线程、目标窗口线程临时挂接，
     * 绕过限制后再置顶（该技巧在 Python 版流程中已验证可用）。
     */
    public void focusWindow() {
        com.sun.jna.platform.win32.WinDef.HWND hwnd = findHwnd();
        if (hwnd == null) return;
        com.sun.jna.platform.win32.User32 u32 = com.sun.jna.platform.win32.User32.INSTANCE;
        try {
            // 最小化时先还原（JNA 的 User32 没有 IsIconic，用自己声明的接口）
            if (WindowsAPI.INSTANCE.IsIconic(hwnd)) {
                u32.ShowWindow(hwnd, 9); // SW_RESTORE
                sleepQuiet(600);
            }
            com.sun.jna.platform.win32.WinDef.DWORD cur =
                    new com.sun.jna.platform.win32.WinDef.DWORD(
                            com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId());
            com.sun.jna.platform.win32.WinDef.HWND fg = u32.GetForegroundWindow();
            int fgTid = u32.GetWindowThreadProcessId(fg, null);
            int tgTid = u32.GetWindowThreadProcessId(hwnd, null);

            com.sun.jna.platform.win32.WinDef.DWORD fgDw =
                    new com.sun.jna.platform.win32.WinDef.DWORD(fgTid);
            com.sun.jna.platform.win32.WinDef.DWORD tgDw =
                    new com.sun.jna.platform.win32.WinDef.DWORD(tgTid);

            boolean a1 = false, a2 = false;
            if (fgTid != 0 && fgTid != cur.intValue()) a1 = u32.AttachThreadInput(cur, fgDw, true);
            if (tgTid != 0 && tgTid != cur.intValue()) a2 = u32.AttachThreadInput(cur, tgDw, true);
            try {
                u32.BringWindowToTop(hwnd);
                u32.SetForegroundWindow(hwnd);
            } finally {
                if (a1) u32.AttachThreadInput(cur, fgDw, false);
                if (a2) u32.AttachThreadInput(cur, tgDw, false);
            }

            // 光置前还不够。
            //
            // Robot 的点击是按「屏幕坐标」发出去的，只要游戏窗口被别的窗口物理挡住，
            // 那一下点击就会被挡在前面的窗口吃掉（实测：WorkBuddy 浮在游戏上，
            // 点「寻路」按钮全打在 WorkBuddy 上，面板当然一直不出现）。
            // SetForegroundWindow 不保证把窗口抬到 Z 序最前，所以这里再补一发
            // SetWindowPos(HWND_TOP) + 循环校验「真的成了前台窗口」。
            for (int i = 0; i < 3; i++) {
                WindowsAPI.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                        WindowsAPI.SWP_NOMOVE | WindowsAPI.SWP_NOSIZE | WindowsAPI.SWP_SHOWWINDOW);
                u32.BringWindowToTop(hwnd);
                u32.SetForegroundWindow(hwnd);
                sleepQuiet(150);
                com.sun.jna.platform.win32.WinDef.HWND nowFg =
                        WindowsAPI.INSTANCE.GetForegroundWindow();
                if (nowFg != null && nowFg.equals(hwnd)) {
                    return;
                }
            }
        } catch (Throwable t) {
            System.err.println("focusWindow 失败: " + t.getMessage());
        }
    }

    /**
     * 抓取游戏窗口画面（含标题栏）。返回图的像素坐标与「窗口内坐标」一一对应。
     *
     * <p><b>后台模式</b>下优先走 {@link #captureWindowBackground()}（PrintWindow，
     * 被遮挡也能截），拿不到再回退 Robot 截屏。
     * 这样后台运行时 OCR / 模板匹配依然能正常工作。
     */
    public BufferedImage captureWindow() {
        if (runInBackground) {
            BufferedImage bg = captureWindowBackground();
            if (bg != null && !isMostlyBlank(bg)) {
                return bg;
            }
            // PrintWindow 对个别游戏无效（返回全黑/空白）→ 回退屏幕截图
            if (bg != null) {
                System.err.println("[CAPTURE] PrintWindow 返回空白图，回退 Robot 截屏");
            }
        }
        return captureWindowRobots();
    }

    /**
     * 判断一张图是否「几乎全黑/空白」—— 用于识别 PrintWindow 失败。
     *
     * <p>抽样看就行：PrintWindow 失败时整图基本是同一个颜色，
     * 抽样点之间颜色差异极小。这里抽 200 个点算颜色种类，种类过少即判空白。
     */
    private boolean isMostlyBlank(BufferedImage img) {
        try {
            java.util.Set<Integer> colors = new java.util.HashSet<>();
            int stepX = Math.max(1, img.getWidth() / 20);
            int stepY = Math.max(1, img.getHeight() / 10);
            for (int y = 0; y < img.getHeight(); y += stepY) {
                for (int x = 0; x < img.getWidth(); x += stepX) {
                    colors.add(img.getRGB(x, y) & 0x00FFFFFF);
                    if (colors.size() > 8) {
                        return false;   // 颜色够丰富，认为是正常画面
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 发送一个按键（虚拟键码）。
     *
     * <p><b>前台模式</b>：优先真实硬件输入 —— 游戏对 Robot/SendInput 生成的输入响应最可靠；
     * Robot 不可用时退回 {@link WindowUtils#sendVkToProcess} 的窗口消息方式。
     *
     * <p><b>后台模式</b>：不抢焦点、直接 PostMessage（走 {@code sendVkToProcess}），
     * 游戏窗口被遮挡也能发。注意窗口消息的 lParam 扫描码为 0，
     * 个别引擎可能忽略 —— 若实测按键无效，可临时关掉后台模式验证。
     */
    public boolean sendKey(int vk) {
        if (runInBackground) {
            boolean ok = WindowUtils.sendVkToProcess(processId, vk);
            System.out.println("[BG-KEY] vk=0x" + Integer.toHexString(vk)
                    + " (PostMessage) ok=" + ok);
            if (ok) {
                sleepQuiet(60);
                return true;
            }
            System.err.println("[BG-KEY] PostMessage 失败，回退前台按键");
        }
        focusWindow();
        sleepQuiet(120);
        int awtCode = toAwtKeyCode(vk);
        if (robot != null && awtCode >= 0) {
            try {
                robot.keyPress(awtCode);
                sleepQuiet(45);
                robot.keyRelease(awtCode);
                System.out.println("[SEND KEY] vk=0x" + Integer.toHexString(vk) + " (Robot)");
                return true;
            } catch (Throwable t) {
                System.err.println("Robot 按键失败，改用窗口消息: " + t.getMessage());
            }
        }
        return WindowUtils.sendVkToProcess(processId, vk);
    }

    /** Windows 虚拟键码 -> AWT 键码。字母/数字/ESC/F11 两者取值一致，仅 Enter 不同。 */
    private int toAwtKeyCode(int vk) {
        if (vk == WindowUtils.VK_RETURN) return java.awt.event.KeyEvent.VK_ENTER;
        if (vk == WindowUtils.VK_ESCAPE) return java.awt.event.KeyEvent.VK_ESCAPE;
        if (vk == WindowUtils.VK_F5)     return java.awt.event.KeyEvent.VK_F5;
        if (vk == WindowUtils.VK_F11)    return java.awt.event.KeyEvent.VK_F11;
        if (vk == WindowUtils.VK_SPACE)  return java.awt.event.KeyEvent.VK_SPACE;
        if (vk == WindowUtils.VK_BACK)   return java.awt.event.KeyEvent.VK_BACK_SPACE;
        if (vk == WindowUtils.VK_END)    return java.awt.event.KeyEvent.VK_END;
        if (vk == WindowUtils.VK_UP)     return java.awt.event.KeyEvent.VK_UP;
        if (vk == WindowUtils.VK_DOWN)   return java.awt.event.KeyEvent.VK_DOWN;
        if (vk == WindowUtils.VK_LEFT)   return java.awt.event.KeyEvent.VK_LEFT;
        if (vk == WindowUtils.VK_RIGHT)  return java.awt.event.KeyEvent.VK_RIGHT;
        if (vk >= 0x41 && vk <= 0x5A)    return vk; // A-Z
        if (vk >= 0x30 && vk <= 0x39)    return vk; // 0-9
        return -1;
    }

    /**
     * 游戏窗口被最小化时自动恢复（SW_SHOWNOACTIVATE，不抢焦点）。
     *
     * <p>最小化状态下窗口缩成 160x28 @ (-32000,-32000)，PrintWindow 截图与全部
     * 坐标换算失效，后台任务必挂。每个任务 execute() 开始时调用一次。
     */
    public void ensureWindowVisible() {
        try {
            Rectangle r = getWindowRect();
            if (r == null) {
                return;
            }
            if (r.x <= -30000 || r.y <= -30000 || r.width < 300 || r.height < 300) {
                System.out.println("[WINDOW] 游戏窗口已最小化 (" + r.x + "," + r.y + " "
                        + r.width + "x" + r.height + ")，自动恢复（不抢焦点）");
                WindowUtils.restoreWindow(processId);
                sleepQuiet(1000);
            }
        } catch (Throwable t) {
            // 静默：恢复失败不阻塞任务，后续步骤会自行报错
        }
    }

    /**
     * 发送一个按键，但不重新抢焦点。
     *
     * <p>连续输入（如清空输入框的 End + Backspace×10、逐位敲数字）若每次都走
     * {@link #sendKey(int)} 会反复 AttachThreadInput 抢焦点，既慢又可能被打断。
     * 这类场景在流程开始已抢过一次焦点后，用本方法即可。
     *
     * <p><b>后台模式</b>：绝不能走 Robot —— 真实键盘输入会打进<b>用户当前正在用的
     * 前台窗口</b>（比如正在打字的聊天框），直接发窗口消息给游戏。
     */
    public boolean sendKeyNoFocus(int vk) {
        if (runInBackground) {
            boolean ok = WindowUtils.sendVkToProcess(processId, vk);
            System.out.println("[BG-KEY-NF] vk=0x" + Integer.toHexString(vk)
                    + " (PostMessage) ok=" + ok);
            return ok;
        }
        int awtCode = toAwtKeyCode(vk);
        if (robot != null && awtCode >= 0) {
            try {
                robot.keyPress(awtCode);
                sleepQuiet(40);
                robot.keyRelease(awtCode);
                return true;
            } catch (Throwable t) {
                System.err.println("Robot 按键失败，改用窗口消息: " + t.getMessage());
            }
        }
        return WindowUtils.sendVkToProcess(processId, vk);
    }

    /**
     * 发送一个可打印字符（数字等），但不重新抢焦点。
     *
     * <p><b>为什么不用 sendKeyNoFocus</b>：实测这个游戏会把后台 PostMessage 的
     * 一条 WM_KEYDOWN+WM_KEYUP 翻译成<b>两个</b>字符（编辑框打 '7' 变 "77"、
     * 打 '1' 变 "11"，占满 2 位长度上限后吞掉后续输入），寻路坐标因此填错。
     * WM_CHAR 一条消息就是一个字符，从根上杜绝翻倍。
     *
     * <p><b>前台模式</b>走真实键盘（Robot），不存在翻倍问题，转调
     * {@link #sendKeyNoFocus(int)}。
     */
    public boolean sendCharNoFocus(char c) {
        if (runInBackground) {
            boolean ok = WindowUtils.sendCharToProcess(processId, c);
            System.out.println("[BG-CHAR-NF] ch='" + c + "' (WM_CHAR) ok=" + ok);
            return ok;
        }
        // 前台：数字直接映射到主键盘区虚拟键
        if (c >= '0' && c <= '9') {
            return sendKeyNoFocus(0x30 + (c - '0'));
        }
        System.err.println("[CHAR-NF] 不支持的字符: '" + c + "'");
        return false;
    }

    /**
     * 在「窗口内坐标」处点击鼠标左键。
     *
     * <p>传入的是基于基准分辨率 1030x797 的窗口内坐标，方法内部会按照游戏窗口
     * 实际尺寸等比换算为屏幕坐标，因此换分辨率/换位置后依然可用。
     *
     * <p><b>后台模式</b>（{@link #isRunInBackground()}）下改走
     * {@link #clickWindowPointBackground(int,int)} —— 不抢焦点、不移动真实鼠标、
     * 直接给游戏窗口发鼠标消息，因此游戏窗口被遮挡也能点。
     */
    public boolean clickWindowPoint(int wx, int wy) {
        if (runInBackground) {
            return clickWindowPointBackground(wx, wy);
        }
        Rectangle r = getWindowRect();
        if (r == null || robot == null) {
            System.err.println("[CLICK] 窗口不可用，点击取消");
            return false;
        }
        int sx = r.x + (int) Math.round(wx * (double) r.width  / BASE_WIDTH);
        int sy = r.y + (int) Math.round(wy * (double) r.height / BASE_HEIGHT);
        focusWindow();
        sleepQuiet(120);
        robot.mouseMove(sx, sy);
        sleepQuiet(150);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
        sleepQuiet(90);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
        System.out.println("[CLICK] window(" + wx + "," + wy + ") -> screen(" + sx + "," + sy + ")");
        return true;
    }

    // ==================== 后台操作（不抢前台、不移动真实鼠标） ====================

    /**
     * <b>后台点击</b>：把「窗口内坐标」换算成客户区坐标，直接给游戏窗口
     * PostMessage 一条按下 + 抬起，<b>不抢前台、不移动真实鼠标</b>。
     *
     * <p>原理：{@code PostMessage(hwnd, WM_LBUTTONDOWN, MK_LBUTTON, MAKELPARAM(x,y))}
     * 是把消息投递到目标窗口的消息队列里，由目标线程自己去取 ——
     * 因此窗口是不是前台、有没有被遮挡、鼠标光标在哪，<b>全都不影响</b>。
     *
     * <p>两个前置条件（缺一不可）：
     * <ol>
     *   <li><b>本进程要提权</b>：Vista 之后 UIPI 会拦截低完整性进程发给高完整性进程的
     *       消息。游戏是管理员启动的，本工具也必须管理员运行（项目已用
     *       launcher 打 RUNASADMIN 标记解决）。</li>
     *   <li>坐标要用<b>客户区坐标</b>：WM_*MOUSE* 的 lParam 是相对窗口客户区左上角的，
     *       不是屏幕坐标。下面用 {@link #windowToClient(int,int)} 换算。</li>
     * </ol>
     *
     * <p>失败或不可用时<b>自动回退</b>到前台点击，保证功能不残废。
     */
    public boolean clickWindowPointBackground(int wx, int wy) {
        try {
            HWND hwnd = findHwnd();
            if (hwnd == null) {
                System.err.println("[BG-CLICK] 拿不到窗口句柄，回退前台点击");
                return clickWindowPointForeground(wx, wy);
            }
            int[] c = windowToClient(wx, wy);
            if (c == null) {
                System.err.println("[BG-CLICK] 客户区坐标换算失败，回退前台点击");
                return clickWindowPointForeground(wx, wy);
            }
            int cx = c[0], cy = c[1];
            int lp = WindowsAPI.makeLParam(cx, cy);
            // 先移动一次，让游戏把「鼠标在哪」更新过来（有些引擎靠 mousemove 记录位置）
            WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_MOUSEMOVE, 0, lp);
            sleepQuiet(40);
            boolean down = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_LBUTTONDOWN,
                    MK_LBUTTON, lp);
            sleepQuiet(90);
            boolean up = WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_LBUTTONUP, 0, lp);
            System.out.println("[BG-CLICK] window(" + wx + "," + wy + ") -> client(" + cx + "," + cy
                    + ") down=" + down + " up=" + up);
            return down && up;
        } catch (Throwable t) {
            System.err.println("[BG-CLICK] 出错：" + t + "，回退前台点击");
            return clickWindowPointForeground(wx, wy);
        }
    }

    /** WM_LBUTTONDOWN 的 wParam：左键按下标志位。 */
    private static final int MK_LBUTTON = 0x0001;

    /**
     * <b>后台点击手法的实验入口</b>（供探针逐一试哪种游戏认）。
     *
     * <p>实测表明 QQ三国的 {@code QQSGWinClass} 是 DirectInput 引擎，
     * 对 {@code PostMessage} 投递的鼠标消息<b>不予理会</b>。这里把所有
     * 已知的后台发鼠标手法都做出来，逐个实测比对，找出真正有效的那一种。
     *
     * <ul>
     *   <li>1 = PostMessage 到顶层窗口（当前实现）</li>
     *   <li>2 = SendMessage 到顶层窗口（同步、进窗口过程）</li>
     *   <li>3 = 先 SetActiveWindow 激活，再 PostMessage</li>
     *   <li>4 = 先 SetActiveWindow 激活，再 SendMessage</li>
     *   <li>5 = 把消息投给子窗口（D3DFocusWindow 等渲染窗）</li>
     * </ul>
     *
     * @return 消息是否成功发出（不代表游戏一定响应）
     */
    public boolean testBackgroundClickStrategy(int strategy, int wx, int wy) {
        try {
            HWND hwnd = findHwnd();
            if (hwnd == null) {
                return false;
            }
            int[] cl = windowToClient(wx, wy);
            if (cl == null) {
                return false;
            }
            int lp = WindowsAPI.makeLParam(cl[0], cl[1]);
            HWND target = hwnd;
            if (strategy == 5) {
                HWND child = findRenderChild(hwnd);
                if (child != null) {
                    target = child;
                }
            }
            if (strategy == 3 || strategy == 4) {
                activateWindowNoRaise(hwnd);
                sleepQuiet(80);
            }
            if (strategy == 2 || strategy == 4) {
                // SendMessage 是同步的，返回后游戏已处理完
                WindowsAPI.INSTANCE.SendMessage(target, WindowsAPI.WM_MOUSEMOVE, 0, lp);
                WindowsAPI.INSTANCE.SendMessage(target, WindowsAPI.WM_LBUTTONDOWN, MK_LBUTTON, lp);
                sleepQuiet(90);
                WindowsAPI.INSTANCE.SendMessage(target, WindowsAPI.WM_LBUTTONUP, 0, lp);
                return true;
            }
            WindowsAPI.INSTANCE.PostMessage(target, WindowsAPI.WM_MOUSEMOVE, 0, lp);
            sleepQuiet(40);
            boolean down = WindowsAPI.INSTANCE.PostMessage(target, WindowsAPI.WM_LBUTTONDOWN,
                    MK_LBUTTON, lp);
            sleepQuiet(90);
            boolean up = WindowsAPI.INSTANCE.PostMessage(target, WindowsAPI.WM_LBUTTONUP, 0, lp);
            return down && up;
        } catch (Throwable t) {
            System.err.println("testBackgroundClickStrategy 出错：" + t);
            return false;
        }
    }

    /**
     * 把窗口设为「活动窗口」但<b>不置前、不抢显示焦点</b>。
     *
     * <p>用 {@code SetActiveWindow} + {@code SetFocus} 而不用
     * {@code SetForegroundWindow} —— 后者会真的把窗口拉到最前面挡住用户，
     * 前者只是让窗口「以为」自己是活动的，很多游戏靠这个决定要不要处理鼠标消息。
     */
    public void activateWindowNoRaise(HWND hwnd) {
        try {
            if (hwnd == null) {
                return;
            }
            int myThread = WindowsAPI.INSTANCE.GetCurrentThreadId();
            int hisThread = WindowsAPI.INSTANCE.GetWindowThreadProcessId(hwnd, null);
            boolean attached = false;
            if (myThread != hisThread && hisThread != 0) {
                attached = WindowsAPI.INSTANCE.AttachThreadInput(myThread, hisThread, true);
            }
            try {
                WindowsAPI.INSTANCE.SetActiveWindow(hwnd);
                WindowsAPI.INSTANCE.SetFocus(hwnd);
                WindowsAPI.INSTANCE.PostMessage(hwnd, WindowsAPI.WM_ACTIVATE,
                        WindowsAPI.WA_ACTIVE, 0);
            } finally {
                if (attached) {
                    WindowsAPI.INSTANCE.AttachThreadInput(myThread, hisThread, false);
                }
            }
        } catch (Throwable t) {
            System.err.println("activateWindowNoRaise 出错：" + t);
        }
    }

    /** 在指定窗口的子窗口里找一个像「渲染窗」的（D3D/主画面），找不到返回 null。 */
    public HWND findRenderChild(HWND parent) {
        try {
            final HWND[] found = new HWND[1];
            WindowsAPI.INSTANCE.EnumChildWindows(parent, new WindowsAPI.WNDENUMPROC() {
                @Override
                public boolean callback(HWND child, com.sun.jna.Pointer data) {
                    StringBuilder sb = new StringBuilder(256);
                    long len = 0;
                    char[] buf = new char[256];
                    int n = WindowsAPI.INSTANCE.GetWindowText(child, buf, 256);
                    String title = n > 0 ? new String(buf, 0, n) : "";
                    System.out.println("     子窗 hwnd=" + child + "  title=" + title);
                    if (found[0] == null) {
                        found[0] = child;
                    }
                    return true;
                }
            }, null);
            return found[0];
        } catch (Throwable t) {
            System.err.println("findRenderChild 出错：" + t);
            return null;
        }
    }

    /** 打印本窗口的所有子窗口（探针用，便于找渲染/输入窗）。 */
    public void enumerateChildWindows() {
        HWND hwnd = findHwnd();
        if (hwnd == null) {
            System.out.println("   拿不到窗口句柄");
            return;
        }
        System.out.println("   顶层 hwnd=" + hwnd + " 的子窗口：");
        enumerateChildren(hwnd, 0);
    }

    private void enumerateChildren(HWND parent, int depth) {
        final int d = depth;
        WindowsAPI.INSTANCE.EnumChildWindows(parent, new WindowsAPI.WNDENUMPROC() {
            @Override
            public boolean callback(HWND child, com.sun.jna.Pointer data) {
                char[] buf = new char[256];
                int n = WindowsAPI.INSTANCE.GetWindowText(child, buf, 256);
                String title = n > 0 ? new String(buf, 0, n) : "";
                com.sun.jna.platform.win32.WinDef.RECT rc =
                        new com.sun.jna.platform.win32.WinDef.RECT();
                getChildRect(child, rc);
                String indent = d == 0 ? "   " : "     ";
                System.out.println(indent + "子窗(" + d + ") hwnd=" + child
                        + " [" + (rc.right - rc.left) + "x" + (rc.bottom - rc.top) + "]"
                        + "  title=\"" + title + "\"");
                if (d < 2) {
                    enumerateChildren(child, d + 1);
                }
                return true;
            }
        }, null);
    }

    /** 取子窗口矩形（用 GetWindowRect 就行）。 */
    private void getChildRect(HWND h, com.sun.jna.platform.win32.WinDef.RECT rc) {
        try {
            com.sun.jna.Platform.isWindows();
            WindowsAPI.INSTANCE.GetClientRect(h, rc);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 强制走<b>前台</b>点击（Robot 真实鼠标）。后台模式下的回退路径，
     * 也被需要「一定要真实点一下」的场景直接调用。
     */
    public boolean clickWindowPointForeground(int wx, int wy) {
        boolean saved = runInBackground;
        runInBackground = false;
        try {
            Rectangle r = getWindowRect();
            if (r == null || robot == null) {
                System.err.println("[CLICK-FG] 窗口不可用，点击取消");
                return false;
            }
            int sx = r.x + (int) Math.round(wx * (double) r.width  / BASE_WIDTH);
            int sy = r.y + (int) Math.round(wy * (double) r.height / BASE_HEIGHT);
            focusWindow();
            sleepQuiet(120);
            robot.mouseMove(sx, sy);
            sleepQuiet(150);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
            sleepQuiet(90);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
            System.out.println("[CLICK-FG] window(" + wx + "," + wy + ") -> screen(" + sx + "," + sy + ")");
            return true;
        } finally {
            runInBackground = saved;
        }
    }

    /**
     * 把「基于基准 1030x797 的窗口内坐标」换算成<b>客户区坐标</b>。
     *
     * <p>换算链：窗口内坐标 --(按实际窗口尺寸等比放大)--> 实际窗口内像素
     * --(减去标题栏/边框)--> 客户区坐标。
     *
     * <p>关键点：{@code getWindowRect()} 拿到的是<b>含标题栏边框</b>的整窗矩形，
     * 而 WM_*MOUSE* 要的是客户区坐标，所以必须减掉 {@code ClientToScreen(0,0) - rect左上角}
     * 这个偏移。否则点击位置会整体偏上一条标题栏的高度。
     *
     * @return {cx, cy}；拿不到时返回 null
     */
    public int[] windowToClient(int wx, int wy) {
        try {
            Rectangle r = getWindowRect();
            if (r == null) {
                return null;
            }
            // 实际窗口内像素坐标
            int px = (int) Math.round(wx * (double) r.width  / BASE_WIDTH);
            int py = (int) Math.round(wy * (double) r.height / BASE_HEIGHT);

            // 客户区左上角相对整窗左上角的偏移（= 标题栏 + 左边框）
            HWND hwnd = findHwnd();
            if (hwnd == null) {
                return null;
            }
            com.sun.jna.platform.win32.WinDef.POINT origin =
                    new com.sun.jna.platform.win32.WinDef.POINT(0, 0);
            if (!WindowsAPI.INSTANCE.ClientToScreen(hwnd, origin)) {
                return null;
            }
            int offX = origin.x - r.x;
            int offY = origin.y - r.y;
            return new int[]{px - offX, py - offY};
        } catch (Throwable t) {
            System.err.println("windowToClient 出错：" + t);
            return null;
        }
    }

    /**
     * <b>后台截图</b>：用 {@code PrintWindow} 让窗口自己把内容画一遍，
     * 因此<b>被别的窗口遮挡、甚至部分移出屏幕都能拿到真实画面</b>。
     *
     * <p>与 {@link #captureWindowRobots}（Robot 抓屏幕）的区别：
     * Robot 是「截取屏幕那一块」，窗口被遮挡时截到的是遮挡物 ——
     * 这是原来「必须把游戏窗口放在最前」的根本原因。
     *
     * <p>{@code PW_RENDERFULLCONTENT} 标志（0x2）必须带：现代游戏多用
     * DirectComposition/Direct3D 渲染，不带这个标志 PrintWindow 会返回全黑图。
     *
     * <p>任何一步失败都会<b>返回 null</b>，由调用方决定是否回退 Robot 截图。
     */
    public BufferedImage captureWindowBackground() {
        com.sun.jna.platform.win32.WinDef.HDC hdcWindow = null;
        com.sun.jna.platform.win32.WinDef.HDC hdcMem = null;
        com.sun.jna.platform.win32.WinDef.HBITMAP hbm = null;
        try {
            HWND hwnd = findHwnd();
            if (hwnd == null) {
                return null;
            }
            // 尺寸用整窗矩形（PrintWindow 默认画整窗，含标题栏边框）
            Rectangle r = getWindowRect();
            if (r == null || r.width <= 0 || r.height <= 0) {
                return null;
            }
            int w = r.width, h = r.height;

            hdcWindow = WindowsAPI.INSTANCE.GetWindowDC(hwnd);
            if (hdcWindow == null) {
                return null;
            }
            hdcMem = WindowsAPI.Gdi32Ex.INSTANCE.CreateCompatibleDC(hdcWindow);
            hbm = WindowsAPI.Gdi32Ex.INSTANCE.CreateCompatibleBitmap(hdcWindow, w, h);
            if (hdcMem == null || hbm == null) {
                return null;
            }
            WindowsAPI.Gdi32Ex.INSTANCE.SelectObject(hdcMem, hbm);

            // 先试 PW_RENDERFULLCONTENT（现代渲染必须），失败再试 0
            boolean ok = WindowsAPI.INSTANCE.PrintWindow(hwnd, hdcMem,
                    WindowsAPI.PW_RENDERFULLCONTENT);
            if (!ok) {
                ok = WindowsAPI.INSTANCE.PrintWindow(hwnd, hdcMem, 0);
            }
            if (!ok) {
                return null;
            }

            // 把 DIB 位读进 BufferedImage
            com.sun.jna.platform.win32.WinGDI.BITMAPINFO bmi =
                    new com.sun.jna.platform.win32.WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = w;
            bmi.bmiHeader.biHeight = -h;             // 负数 = 自顶向下，省一次翻转
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = 0;         // BI_RGB

            int[] buf = new int[w * h];
            com.sun.jna.Memory mem = new com.sun.jna.Memory((long) w * h * 4);
            int got = WindowsAPI.Gdi32Ex.INSTANCE.GetDIBits(hdcMem, hbm, 0, h,
                    mem, bmi, 0);
            if (got == 0) {
                mem.close();
                return null;
            }
            mem.read(0, buf, 0, w * h);
            mem.close();

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, buf, 0, w);
            return img;
        } catch (Throwable t) {
            System.err.println("captureWindowBackground 失败：" + t);
            return null;
        } finally {
            try {
                if (hbm != null) {
                    // hbm 本身就是 HBITMAP，直接传即可（JNA 里没有 HGDIOBJ 类型）
                    WindowsAPI.Gdi32Ex.INSTANCE.DeleteObject(hbm);
                }
                if (hdcMem != null) {
                    WindowsAPI.Gdi32Ex.INSTANCE.DeleteDC(hdcMem);
                }
                if (hdcWindow != null) {
                    WindowsAPI.INSTANCE.ReleaseDC(findHwnd(), hdcWindow);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 只要「窗口内坐标」不管后台与否，直接用 Robot 截图。
     *
     * <p>公开出来是为了让探针能把「PrintWindow 截图」和「Robot 截图」
     * 摆在一起对比，确认后台截图拿到的确实是真实画面。
     */
    public BufferedImage captureWindowRobots() {
        Rectangle r = getWindowRect();
        if (r == null || robot == null) return null;
        try {
            return robot.createScreenCapture(r);
        } catch (Throwable t) {
            System.err.println("captureWindow(robot) 失败: " + t.getMessage());
            return null;
        }
    }

    /** 当前鼠标位置（任务结束后还原用），失败返回 null。 */
    public int[] getCursorPosition() {
        try {
            java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
            return new int[]{p.x, p.y};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把鼠标移回指定屏幕坐标。 */
    public void moveCursor(int x, int y) {
        if (robot != null) robot.mouseMove(x, y);
    }

    /**
     * 把游戏窗口整体挪到屏幕的 (x, y)，大小不变。
     *
     * <p>用途：当屏幕上有「常驻置顶」的窗口挡住了游戏、连置前都压不过它时，
     * 干脆把游戏挪到一块没被遮住的区域，Robot 的屏幕点击才不会打偏。
     */
    public boolean moveWindowTo(int x, int y) {
        com.sun.jna.platform.win32.WinDef.HWND hwnd = findHwnd();
        if (hwnd == null) {
            return false;
        }
        Rectangle r = getWindowRect();
        int w = r == null ? 0 : r.width;
        int h = r == null ? 0 : r.height;
        boolean ok = WindowsAPI.INSTANCE.SetWindowPos(hwnd, null, x, y, w, h,
                WindowsAPI.SWP_SHOWWINDOW);
        sleepQuiet(300);
        System.out.println("[MOVE] 窗口 -> (" + x + "," + y + ") " + (ok ? "ok" : "failed"));
        return ok;
    }

    /** 当前前台窗口是不是游戏窗口（排查「点击打到别的窗口上」用）。 */
    public boolean isForegroundGameWindow() {
        com.sun.jna.platform.win32.WinDef.HWND hwnd = findHwnd();
        if (hwnd == null) {
            return false;
        }
        com.sun.jna.platform.win32.WinDef.HWND fg = WindowsAPI.INSTANCE.GetForegroundWindow();
        return fg != null && fg.equals(hwnd);
    }

    /**
     * 只把鼠标移到窗口内的某个点，<b>不点击</b>（坐标同 {@link #clickWindowPoint}，基准 1030x797）。
     *
     * <p>用途：答题时鼠标若停在某个选项上，那一行会被高亮，OCR 就认不出该行文字
     * （实测就是这么漏掉一个选项的）。所以每次读屏前先把它挪到不碍事的地方。
     *
     * <p><b>后台模式直接跳过</b>：PrintWindow 截的图里根本没有真实光标，
     * 不存在"光标压住选项"的问题；此时挪鼠标纯属打扰正在用电脑的用户。
     */
    public void moveToWindowPoint(int wx, int wy) {
        if (runInBackground) {
            return;
        }
        Rectangle r = getWindowRect();
        if (r == null || robot == null) {
            return;
        }
        int sx = r.x + (int) Math.round(wx * (double) r.width / BASE_WIDTH);
        int sy = r.y + (int) Math.round(wy * (double) r.height / BASE_HEIGHT);
        robot.mouseMove(sx, sy);
    }

    /** 不抛异常的 sleep。 */
    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pressKey(char key) {
        try {
            System.out.println("[KEY PRESS] Starting to press key: " + key + " to window: " + windowName + " (PID: " + processId + ")");
            
            // Mark key as pressed
            pressedKeys.add(key);
            
            boolean success = false;
            int retryCount = 0;
            final int MAX_RETRIES = 2;
            
            // 优先使用JNA向指定窗口发送按键消息，不检查窗口可见性
            // 这样即使窗口被隐藏（老板键隐藏），也能正常发送按键
            if (processId > 0) {
                System.out.println("[KEY PRESS] Using JNA to send key to process ID: " + processId);
                
                // 添加重试机制
                while (retryCount <= MAX_RETRIES && !success) {
                    if (retryCount > 0) {
                        System.out.println("[KEY PRESS] Retry " + retryCount + " for key: " + key);
                        Thread.sleep(100); // 重试前的小延迟
                    }
                    
                    success = WindowUtils.sendKeyToProcess(processId, key);
                    retryCount++;
                    
                    if (success) {
                        System.out.println("[KEY PRESS] Successfully pressed key: " + key + " using JNA");
                    } else {
                        System.err.println("[KEY PRESS] FAILED to send key via JNA for window: " + windowName + " (PID: " + processId + ")");
                    }
                }
                
                // 如果JNA方法失败，尝试直接通过窗口名称发送（作为备选方案）
                if (!success) {
                    System.out.println("[KEY PRESS] All JNA retries failed, trying alternative approach");
                    // 这里可以添加其他方法，比如使用SendMessage或其他API
                }
            } else {
                System.err.println("[KEY PRESS] No valid PID (" + processId + ") for window: " + windowName + ", cannot send key via JNA");
            }
            
            // Only use Robot as fallback (but it won't work for minimized windows)
            if (!success && robot != null && !runInBackground) {
                System.out.println("[KEY PRESS] JNA method failed, attempting to use Robot as fallback for window: " + windowName);
                try {
                    int keyCode = 0;
                    switch (Character.toUpperCase(key)) {
                        case 'A': 
                            keyCode = java.awt.event.KeyEvent.VK_A;
                            System.out.println("[ROBOT] Selected key code VK_A for key: A");
                            break;
                        case 'S': 
                            keyCode = java.awt.event.KeyEvent.VK_S;
                            System.out.println("[ROBOT] Selected key code VK_S for key: S");
                            break;
                        case 'D': 
                            keyCode = java.awt.event.KeyEvent.VK_D;
                            System.out.println("[ROBOT] Selected key code VK_D for key: D");
                            break;
                        case 'F': 
                            keyCode = java.awt.event.KeyEvent.VK_F;
                            System.out.println("[ROBOT] Selected key code VK_F for key: F");
                            break;
                        case 'Q': 
                            keyCode = java.awt.event.KeyEvent.VK_Q;
                            System.out.println("[ROBOT] Selected key code VK_Q for key: Q");
                            break;
                        case 'W': 
                            keyCode = java.awt.event.KeyEvent.VK_W;
                            System.out.println("[ROBOT] Selected key code VK_W for key: W");
                            break;
                        case 'E': 
                            keyCode = java.awt.event.KeyEvent.VK_E;
                            System.out.println("[ROBOT] Selected key code VK_E for key: E");
                            break;
                        case 'R': 
                            keyCode = java.awt.event.KeyEvent.VK_R;
                            System.out.println("[ROBOT] Selected key code VK_R for key: R");
                            break;
                        case 'C': 
                            keyCode = java.awt.event.KeyEvent.VK_C;
                            System.out.println("[ROBOT] Selected key code VK_C for key: C");
                            break;
                        default: 
                            System.out.println("[ROBOT] Unsupported key: " + key);
                            return;
                    }
                    
                    System.out.println("[ROBOT] Pressing key: " + key + " (VK code: " + keyCode + ")");
                    robot.keyPress(keyCode);
                    System.out.println("[ROBOT] Key pressed: " + key);
                    
                    System.out.println("[ROBOT] Waiting 50ms between press and release");
                    Thread.sleep(50);
                    
                    System.out.println("[ROBOT] Releasing key: " + key);
                    robot.keyRelease(keyCode);
                    System.out.println("[ROBOT] Key released: " + key);
                    
                    success = true;
                    System.out.println("[ROBOT] Successfully pressed and released key: " + key);
                } catch (Exception e) {
                    System.err.println("[ROBOT] Error using Robot to press key " + key + ": " + e.getMessage());
                    success = false;
                }
            }
            
            if (!success) {
                System.out.println("Failed to press key: " + key + " using all methods");
            }
            
            // Short delay to ensure key press is processed
            Thread.sleep(50);
            
            // Remove key from pressed set
            pressedKeys.remove(key);
            
        } catch (Exception e) {
            // Ensure key is removed from pressed set
            pressedKeys.remove(key);
            System.err.println("Error pressing key " + key + ": " + e.getMessage());
        }
    }
    
    private void releaseAllKeys() {
        try {
            // Ensure all pressed keys are released
            System.out.println("Releasing all keys for window: " + windowName);
            
            // For running processes, try sending space key to reset state
            if (processId > 0 && running.get()) {
                WindowUtils.sendKeyToProcess(processId, ' ');
            }
            
            // Clear pressed keys set
            pressedKeys.clear();
            
            System.out.println("Released all keys: " + windowName);
        } catch (Exception e) {
            System.err.println("Error releasing keys: " + e.getMessage());
        }
    }
    
    @Override
    public String toString() {
        return windowName;
    }
}