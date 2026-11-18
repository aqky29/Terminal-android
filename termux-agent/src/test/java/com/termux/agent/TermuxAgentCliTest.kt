package com.termux.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxAgentCliTest {

    @Test
    fun `generated tdev script is valid bash syntax and handles subcommands`() {
        val script = TermuxAgentCliInstaller.buildTdevScript(
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/apps/com.termux/termux-agent/agent.sock"
        )
        assertTrue(script.startsWith("#!/data/data/com.termux/files/usr/bin/bash\n"))
        assertTrue(script.contains("# TERMUX_AGENT_TDEV_VERSION=3"))
        assertTrue(script.contains("-H \"Expect:\""))
        assertTrue(script.contains("http://localhost/v1/status"))
        assertTrue(script.contains("http://localhost/v1/exec"))

        val bashBin = File("/data/data/com.termux/files/usr/bin/bash")
        if (bashBin.isFile && bashBin.canExecute()) {
            val tmp = File.createTempFile("tdev-check-", ".sh")
            try {
                tmp.writeText(script)
                val proc = ProcessBuilder(bashBin.absolutePath, "-n", tmp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                assertEquals("bash -n failed: $out", 0, proc.waitFor())
            } finally {
                tmp.delete()
            }
        }
    }
}
