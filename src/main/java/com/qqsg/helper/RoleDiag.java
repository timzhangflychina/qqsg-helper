package com.qqsg.helper;

import java.util.List;

/**
 * 角色名解析自检工具：枚举当前所有窗口，逐个尝试从游戏日志解析角色信息。
 * 只读磁盘目录与窗口枚举，不发送任何按键 / 点击。
 *
 * <p>用法：{@code java -cp "build_test;<jar>" com.qqsg.helper.RoleDiag}
 */
public class RoleDiag {

    public static void main(String[] args) {
        System.out.println("========== RoleDiag ==========");
        List<WindowInfo> windows = WindowUtils.getAllWindowsWithIds();
        System.out.println("### 共发现窗口 " + windows.size() + " 个");

        int hit = 0;
        for (WindowInfo w : windows) {
            System.out.println("### 窗口: " + w.getTitle() + "  (PID " + w.getPid() + ")");
            if (w.getPid() <= 0) {
                System.out.println("###   跳过：PID 无效");
                continue;
            }
            GameRoleInfo info = GameRoleInfo.resolve(w.getPid());
            System.out.println("###   角色名 : " + info.getRoleName());
            System.out.println("###   账号   : " + info.getAccount());
            System.out.println("###   日志   : " + info.getLogFileName());
            System.out.println("###   说明   : " + info.getError());
            if (info.hasRoleName()) {
                hit++;
            }
        }
        System.out.println("### 识别成功 " + hit + " / " + windows.size());
        System.out.println("========== RoleDiag END ==========");
    }
}
