package com.qqsg.helper;

/**
 * 任务单次同步执行的结果（供各任务类的 {@code runOnce()} 返回）。
 *
 * <p>引入它是为了让「一键日常」这种<b>同步顺序执行</b>的编排器能拿到每个任务的
 * 成败与摘要 —— 原来 {@code start()} 是异步的，只有 {@code finished} 回调，
 * 编排器既等不到结果、也拿不到成败。
 */
public final class TaskOutcome {

    /** 是否正常跑完（被中止 / 抛异常都算失败）。 */
    public final boolean ok;

    /** 给用户看的摘要（与 finished 回调里的 summary 同一套文案）。 */
    public final String summary;

    public TaskOutcome(boolean ok, String summary) {
        this.ok = ok;
        this.summary = summary;
    }
}
