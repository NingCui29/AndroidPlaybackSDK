package com.cn.media.playback;

/**
 * 音轨切换操作注入点类。
 * <p>
 * 提供包级私有缝隙（Seam），供自动化测试在提交前准备阶段（pre-commit）或首个新 PCM 写入阶段注入模拟故障，
 * 用于验证音轨切换失败时的事务回滚机制与原音轨恢复能力。
 */
class AudioSwitchCalls {
    /**
     * 新音轨准备阶段前置拦截方法。
     *
     * @param stream   音轨索引序号
     * @param rollback 是否处于回滚原音轨流程中
     * @return true 允许继续，false 注入失败中断准备
     */
    boolean prepare(int stream, boolean rollback) { return true; }

    /**
     * 新音轨首次 PCM 写入前置拦截方法。
     *
     * @param stream 音轨索引序号
     * @return true 允许写入，false 注入失败触发回退
     */
    boolean firstWrite(int stream) { return true; }
}
