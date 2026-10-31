package com.github.kr328.clash.diag

/**
 * 诊断采集总开关。
 *
 * `false` = 发行版: 不采样、不落盘、不打包、不上传、不挂崩溃钩子。
 * 所有调用点(后台采样/上传/隧道钩子/设置界面)都以此为唯一门,改这一处即可切换回开发版。
 */
object DiagConfig {
    const val ENABLED: Boolean = false
}
