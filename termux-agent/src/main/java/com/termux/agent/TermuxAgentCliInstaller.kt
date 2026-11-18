package com.termux.agent

import android.system.Os
import android.util.Log
import java.io.File

private const val TAG = "TermuxAgentCliInstall"

object TermuxAgentCliInstaller {

    private const val SCRIPT_VERSION_MARKER = "# TERMUX_AGENT_TDEV_VERSION=3"

    fun installIfPrefixReady(prefixBinDirPath: String, socketPath: String) {
        val binDir = File(prefixBinDirPath)
        if (!binDir.isDirectory || !binDir.canWrite()) return

        val target = File(binDir, "tdev")
        val existingHeader = runCatching {
            if (target.isFile) target.bufferedReader().use { (it.readLine() ?: "") + "\n" + (it.readLine() ?: "") }
            else ""
        }.getOrDefault("")
        if (existingHeader.contains(SCRIPT_VERSION_MARKER)) return

        val bashPath = File(binDir, "bash").absolutePath
        val tmp = File(binDir, ".tdev.tmp.${android.os.Process.myPid()}")
        val script = buildTdevScript(bashPath, socketPath)
        runCatching {
            tmp.writeText(script, Charsets.UTF_8)
            Os.chmod(tmp.absolutePath, 0b111_000_000)
            if (!tmp.renameTo(target)) {
                target.writeText(script, Charsets.UTF_8)
                Os.chmod(target.absolutePath, 0b111_000_000)
                tmp.delete()
            }
            Log.i(TAG, "Installed $target")
        }.onFailure {
            tmp.delete()
            Log.w(TAG, "Failed to install $target", it)
        }
    }

    internal fun buildTdevScript(bashPath: String, defaultSocketPath: String): String =
        listOf(
            "#!$bashPath",
            SCRIPT_VERSION_MARKER,
            "set -u",
            "",
            "SOCK=\"\${TERMUX_AGENT_SOCKET:-$defaultSocketPath}\"",
            "",
            "if [ ! -S \"\$SOCK\" ]; then",
            "    echo \"tdev: agent socket not found at \$SOCK (is Termux app running?)\" >&2",
            "    exit 1",
            "fi",
            "",
            "curl_sock() {",
            "    curl -sS --max-time 185 --unix-socket \"\$SOCK\" -H \"Expect:\" \"\$@\"",
            "}",
            "",
            "show_help() {",
            "    cat <<'EOF'",
            "tdev - Termux Agent Device Bridge CLI (UID 2000/0 without ADB CLI overhead)",
            "",
            "Usage:",
            "  tdev status                  Show privileged server, ADB port, and permission status (JSON)",
            "  tdev up [--no-wifi] [--no-tcp]",
            "                               Auto-start privileged server (Root -> TCP 5555 -> Wireless/No-Wi-Fi)",
            "  tdev pair [code] [host:port] Pair Wireless Debugging (uses mDNS if host:port omitted;",
            "                               or opens notification input if code omitted)",
            "  tdev tcp [port]              Switch adbd to classic TCP port (default: 5555)",
            "  tdev down                    Stop privileged server",
            "  tdev info                    Fast device inspection snapshot (JSON)",
            "  tdev top                     Show current foreground/resumed activity",
            "  tdev pkg [filter]            List installed packages",
            "  tdev log [lines]             Dump recent logcat lines (default: 100)",
            "  tdev sh [-j|--json] [-t ms] [--no-auto-up] [--] <command...>",
            "                               Run command via privileged server (UID 2000 or 0)",
            "EOF",
            "}",
            "",
            "run_exec_framed() {",
            "    local cmd=\"\$1\"",
            "    local timeout_ms=\"\$2\"",
            "    local auto_up=\"\$3\"",
            "    local tmp",
            "    tmp=\"\$(mktemp \"\${TMPDIR:-/tmp}/tdev.XXXXXX\")\" || return 1",
            "    trap \"rm -f '\$tmp'\" RETURN",
            "",
            "    local http_code",
            "    http_code=\"\$(printf '%s' \"\$cmd\" | curl -sS --max-time 185 --unix-socket \"\$SOCK\" \\",
            "        -H \"Expect:\" \\",
            "        -H \"Accept: application/x-termux-exec\" \\",
            "        -H \"Content-Type: text/plain\" \\",
            "        -X POST --data-binary @- \\",
            "        -o \"\$tmp\" -w '%{http_code}' \\",
            "        \"http://localhost/v1/exec?timeout_ms=\$timeout_ms&auto_up=\$auto_up\")\" || return 1",
            "",
            "    if [ \"\$http_code\" != \"200\" ]; then",
            "        cat \"\$tmp\" >&2",
            "        return 1",
            "    fi",
            "",
            "    local exit_code out_len err_len",
            "    {",
            "        IFS= read -r exit_code || exit_code=1",
            "        IFS= read -r out_len || out_len=0",
            "        IFS= read -r err_len || err_len=0",
            "    } < \"\$tmp\"",
            "",
            "    local hdr_bytes=\$(( \${#exit_code} + 1 + \${#out_len} + 1 + \${#err_len} + 1 ))",
            "    if [ \"\$out_len\" -gt 0 ] 2>/dev/null; then",
            "        tail -c +\"\$((hdr_bytes + 1))\" \"\$tmp\" | head -c \"\$out_len\"",
            "    fi",
            "    if [ \"\$err_len\" -gt 0 ] 2>/dev/null; then",
            "        tail -c +\"\$((hdr_bytes + out_len + 1))\" \"\$tmp\" | head -c \"\$err_len\" >&2",
            "    fi",
            "    return \"\$exit_code\"",
            "}",
            "",
            "cmd=\"\${1:-help}\"",
            "if [ \"\$#\" -gt 0 ]; then shift; fi",
            "",
            "case \"\$cmd\" in",
            "    -h|--help|help)",
            "        show_help",
            "        ;;",
            "    status)",
            "        curl_sock \"http://localhost/v1/status\"",
            "        echo",
            "        ;;",
            "    info)",
            "        curl_sock \"http://localhost/v1/info\"",
            "        echo",
            "        ;;",
            "    up)",
            "        no_wifi=1",
            "        tcp=1",
            "        while [ \"\$#\" -gt 0 ]; do",
            "            case \"\$1\" in",
            "                --no-wifi) no_wifi=1 ;;",
            "                --wifi-only) no_wifi=0 ;;",
            "                --no-tcp) tcp=0 ;;",
            "                --tcp) tcp=1 ;;",
            "            esac",
            "            shift",
            "        done",
            "        curl_sock -X POST \"http://localhost/v1/up?no_wifi=\$no_wifi&tcp=\$tcp\"",
            "        echo",
            "        ;;",
            "    down)",
            "        curl_sock -X POST \"http://localhost/v1/down\"",
            "        echo",
            "        ;;",
            "    tcp)",
            "        port=\"\${1:-5555}\"",
            "        curl_sock -X POST \"http://localhost/v1/tcp?port=\$port\"",
            "        echo",
            "        ;;",
            "    pair)",
            "        code=\"\${1:-}\"",
            "        addr=\"\${2:-}\"",
            "        q=\"\"",
            "        if [ -n \"\$code\" ]; then",
            "            q=\"code=\$code\"",
            "            if [ -n \"\$addr\" ]; then",
            "                host=\"\${addr%:*}\"",
            "                port=\"\${addr##*:}\"",
            "                q=\"\${q}&host=\${host}&port=\${port}\"",
            "            fi",
            "        fi",
            "        curl_sock -X POST \"http://localhost/v1/pair?\$q\"",
            "        echo",
            "        ;;",
            "    sh|exec)",
            "        json_mode=0",
            "        timeout_ms=30000",
            "        auto_up=1",
            "        while [ \"\$#\" -gt 0 ]; do",
            "            case \"\$1\" in",
            "                -j|--json) json_mode=1; shift ;;",
            "                -t|--timeout) timeout_ms=\"\${2:-30000}\"; shift 2 ;;",
            "                --no-auto-up) auto_up=0; shift ;;",
            "                --) shift; break ;;",
            "                *) break ;;",
            "            esac",
            "        done",
            "        if [ \"\$#\" -eq 0 ]; then",
            "            echo \"Usage: tdev sh [-j|--json] [-t ms] <command...>\" >&2",
            "            exit 2",
            "        fi",
            "        shell_cmd=\"\$*\"",
            "        if [ \"\$json_mode\" = \"1\" ]; then",
            "            printf '%s' \"\$shell_cmd\" | curl_sock -H \"Accept: application/json\" -H \"Content-Type: text/plain\" -X POST --data-binary @- \"http://localhost/v1/exec?timeout_ms=\$timeout_ms&auto_up=\$auto_up\"",
            "            echo",
            "        else",
            "            run_exec_framed \"\$shell_cmd\" \"\$timeout_ms\" \"\$auto_up\"",
            "        fi",
            "        ;;",
            "    top)",
            "        run_exec_framed \"dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 10\" 10000 1",
            "        ;;",
            "    pkg)",
            "        if [ -n \"\${1:-}\" ]; then",
            "            printf -v q_filter '%q' \"\$1\"",
            "            run_exec_framed \"pm list packages | grep -iF -- \$q_filter\" 15000 1",
            "        else",
            "            run_exec_framed \"pm list packages\" 15000 1",
            "        fi",
            "        ;;",
            "    log)",
            "        lines=\"\${1:-100}\"",
            "        printf -v q_lines '%q' \"\$lines\"",
            "        run_exec_framed \"logcat -d -t \$q_lines\" 15000 1",
            "        ;;",
            "    *)",
            "        echo \"tdev: unknown subcommand '\$cmd' (try: tdev help)\" >&2",
            "        exit 2",
            "        ;;",
            "esac",
            ""
        ).joinToString("\n")
}
