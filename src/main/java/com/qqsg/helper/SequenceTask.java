package com.qqsg.helper;

import java.util.ArrayList;
import java.util.List;

/**
 * 「一键日常」：按 <b>工资 → 军团 → 霸王城</b> 的顺序自动跑完三个任务。
 *
 * <h3>实现方式</h3>
 * 每个任务复用各自的 {@code runOnce()}（<b>同步</b>执行、自带后台模式作用域）。
 * 序列整体再包一层后台作用域（进入时记下用户设置并强制开后台，结束时还原），
 * 这样几个任务之间不会反复切换前后台。
 *
 * <h3>失败策略</h3>
 * 某个任务失败或被中止时<b>继续下一个</b> —— 日常任务相互独立，一个出问题
 * 不该拖累其它的（比如军团 NPC 找不到，工资/霸王城照样该跑）。
 * 最后汇总「成功 N/3」；但用户点中止时整条链立刻停下。
 *
 * <h3>与单个任务按钮的互斥</h3>
 * 序列运行期间 {@link GameWindowController#getDailyTask()} 非 null 且 running，
 * 各单任务按钮会检查这个槽位并拒绝同时启动。
 *
 * <p><b>注：本序列只有 3 项，不含孝廉</b>（2026-09-21 应用户要求移出一键序列）；
 * 孝廉仍可作为单任务，单独点界面上的「孝廉」按钮运行。构造器保留 xiaolian
 * 参数与 requestStop 转发，便于以后需要时恢复四步序列。
 */
public class SequenceTask {

    /** 进度 / 结果回调。回调在后台线程触发，界面层需自行切回 EDT。 */
    public interface Listener {
        void log(String message);

        void alert(String title, String message);

        void finished(boolean success, String summary);
    }

    /** 用于中断流程的内部异常。 */
    private static class Abort extends RuntimeException {
        Abort(String message) {
            super(message);
        }
    }

    /** 序列里的一项：任务名 + 怎么同步跑它。 */
    private interface Step {
        String name();

        TaskOutcome run();
    }

    private static final int GAP_MS = 2000;

    private final GameWindowController controller;
    private final Listener listener;
    private final SalaryTask salary;
    private final LegionTask legion;
    private final BawangTask bawang;
    private final XiaolianTask xiaolian;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;

    public SequenceTask(GameWindowController controller, Listener listener,
                        SalaryTask salary, LegionTask legion,
                        BawangTask bawang, XiaolianTask xiaolian) {
        this.controller = controller;
        this.listener = listener;
        this.salary = salary;
        this.legion = legion;
        this.bawang = bawang;
        this.xiaolian = xiaolian;
    }

    public boolean isRunning() {
        return running;
    }

    /** 请求中止整条链：正在跑的任务立即 Abort，还没跑到的直接跳过。 */
    public void requestStop() {
        stopRequested = true;
        salary.requestStop();
        legion.requestStop();
        bawang.requestStop();
        xiaolian.requestStop();
    }

    public void start() {
        if (running) {
            listener.log("一键日常已在运行中");
            return;
        }
        // 2026-09-26 事故修复：这里原来漏了 running = true，isRunning() 恒 false ——
        // 后果是 ①一键日常与其它任务/另一次一键日常的互斥全部失效（07:08 连点 30+ 次
        // 起了 30+ 个线程互相打架，全军覆没 0/3）；②「再点一次=中止」也点不出来。
        running = true;
        stopRequested = false;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean allOk = false;
                String summary;
                // 序列级后台作用域：整条链期间保持后台，结束时还原用户设置
                boolean savedBg = controller.isRunInBackground();
                int done = 0;
                List<String> failed = new ArrayList<String>();
                try {
                    controller.setRunInBackground(true);
                    listener.log("====== 一键日常 开始：工资 → 军团 → 霸王城（全后台） ======");

                    List<Step> steps = new ArrayList<Step>();
                    steps.add(new Step() {
                        public String name() { return "工资"; }
                        public TaskOutcome run() { return salary.runOnce(); }
                    });
                    steps.add(new Step() {
                        public String name() { return "军团任务"; }
                        public TaskOutcome run() { return legion.runOnce(); }
                    });
                    steps.add(new Step() {
                        public String name() { return "霸王城"; }
                        public TaskOutcome run() { return bawang.runOnce(); }
                    });

                    for (int i = 0; i < steps.size(); i++) {
                        Step s = steps.get(i);
                        if (stopRequested) {
                            throw new Abort("已被用户中止");
                        }
                        listener.log("—— 一键日常 第 " + (i + 1) + "/" + steps.size()
                                + " 项：" + s.name() + " ——");
                        TaskOutcome o = s.run();
                        if (o.ok) {
                            done++;
                        } else {
                            failed.add(s.name() + "（" + o.summary + "）");
                        }
                        listener.log((o.ok ? "✅ " : "⛔ ") + s.name() + "：" + o.summary);
                        // 项与项之间歇一下，让游戏把上一个任务的界面弹窗消化完
                        if (i < steps.size() - 1 && !stopRequested) {
                            sleep(GAP_MS);
                        }
                    }

                    allOk = failed.isEmpty();
                    summary = "一键日常完成：" + done + "/" + steps.size() + " 成功"
                            + (failed.isEmpty() ? "" : "；未成功：" + join(failed));
                } catch (Abort a) {
                    summary = "一键日常中止：" + a.getMessage() + "（已完成 " + done + "/3）";
                } catch (Throwable e) {
                    e.printStackTrace();
                    summary = "执行异常：" + e;
                } finally {
                    controller.setRunInBackground(savedBg);
                    running = false;
                }
                try {
                    listener.finished(allOk, summary);
                } catch (Throwable ignore) {
                    // 回调异常不影响线程收尾
                }
            }
        }, "SequenceTask");
        t.setDaemon(true);
        t.start();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
