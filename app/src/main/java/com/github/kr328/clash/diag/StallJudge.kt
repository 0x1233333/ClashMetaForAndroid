package com.github.kr328.clash.diag

/**
 * 连接活跃度判定。
 *
 * 采样间隔 10 秒,字节计数只能回答"这一轮有没有增长"。旧口径把"idle_s > 5 秒"一律判为卡顿,
 * 而 5 秒小于采样间隔,等于"本轮没增长就算卡顿";又没有任何上限,长时间空闲的长连接会一直被判卡顿
 * —— 实测选路数据里 86% 的采样被判卡顿,卡顿率/误选率随之整体虚高。
 *
 * 现行判定满足三个前提:
 *   1) 阈值明显大于采样间隔:连续 3 轮(30 秒)无增长才算传输中断候选,排除采样抖动;
 *   2) 只对观测到过字节增长的连接判定:从未见增长的连接单列 nodata,不下结论;
 *   3) 无增长持续 6 轮(60 秒)以上已无法与"传输收尾/连接休眠"区分:单列 dormant,不计入卡顿。
 *
 * 为什么上限是 6 轮而不是 18 轮(2026-10-09 用手机真实采样回放得出,样本 13.9 万条):
 *   · 连接传完一段就闲置是常态(keep-alive、长轮询)。旧上限 180 秒把"传完就休眠"也算作卡顿:
 *     有传输史的采样里 50.1% 被判卡顿,据此算出的卡顿率/误选率整体虚高。
 *   · 上限收到 60 秒后同一份数据降到 20.9%;分层看 <10KB 小连接 29.9%、10~100KB 17.7%、
 *     >100KB 大流量连接 10.6% —— 剩下的判定集中在真的在跑量、却停住的连接上。
 *   · 屏熄 23.9% 高于屏亮 18.4%,与"熄屏后长连接闲置"的解释一致。
 *   · 按连接去重后,60 秒窗口下共 6305 次"卡顿后又恢复传输"的断流,可作事件级中断率使用。
 *
 * 判定为纯逻辑、不依赖 Android,便于用真实数据离线复现同一套规则。
 */
internal object StallJudge {
    /** 一轮采样的间隔,与 RoutingSampler 的采样周期一致(10 秒) */
    const val ROUND_MS = 10_000L
    /** 连续无增长达到该轮数 → 传输中断候选(3 轮 = 30 秒) */
    const val STALL_ROUNDS = 3
    /** 连续无增长达到该轮数 → 无法与收尾/休眠区分(6 轮 = 60 秒;180 秒的旧上限会把传完闲置算成卡顿) */
    const val DORMANT_ROUNDS = 6

    /** 本轮有字节增长 */
    const val ACTIVE = "active"
    /** 有过传输史,随后连续无增长,且未超过 dormant 上限 */
    const val STALLED = "stalled"
    /** 无增长时间过长,无法与传输收尾/连接休眠区分 */
    const val DORMANT = "dormant"
    /** 本次观测内从未出现字节增长,无从判定 */
    const val NODATA = "nodata"

    /** 连续无增长轮数 → 秒(仅作上报参考值,判定本身以轮数为准) */
    fun idleSeconds(idleRounds: Int): Double = idleRounds * ROUND_MS / 1000.0

    /** 依据"本轮是否增长 / 是否见过增长 / 连续无增长轮数"给出状态 */
    fun verdict(grew: Boolean, everGrew: Boolean, idleRounds: Int): String = when {
        grew -> ACTIVE
        !everGrew -> NODATA
        idleRounds < STALL_ROUNDS -> ACTIVE
        idleRounds < DORMANT_ROUNDS -> STALLED
        else -> DORMANT
    }
}
