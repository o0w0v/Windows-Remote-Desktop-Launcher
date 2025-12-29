package app;

import java.io.IOException;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public final class SshHelpers {

    private SshHelpers() {}

    public record SshStartResult(int pid, String outLog, String errLog) {}

    private static final Pattern NEEDS_INPUT = Pattern.compile(
            "(?i)(password|passphrase|keyboard-interactive|verification code|one-time|otp|enter.*pass|permission denied)"
    );

    /**
     * 1) まず BatchMode=yes (鍵/agent想定)
     * 2) 落ちたらログを見て入力が必要っぽければ askpass(BatchMode=no) で再実行
     */
    public static SshStartResult startSshTunnelSmart(
            Path appDir,
            Path appKnownHosts,
            String localBind,
            int localPort,
            String sshAlias,
            String sshOptions,
            String rdpHost,
            int rdpPort
    ) throws IOException, InterruptedException {

        Files.createDirectories(appDir);

        // まずは鍵/agent前提（入力なし）
        SshStartResult r1 = startTunnelHidden(
                appDir, appKnownHosts, localBind, localPort, sshAlias, sshOptions, rdpHost, rdpPort,
                true,  // batchMode
                null   // askpassCmd
        );

        // 少し待って生存チェック（即死ならフォールバック）
        Thread.sleep(300);
        if (isProcessAlive(r1.pid())) return r1;

        String tail = tailTextFile(r1.errLog(), 120);
        if (NEEDS_INPUT.matcher(tail).find()) {
            // 入力が必要そう -> askpass で再実行
            Path askpassCmd = resolveAskPassProgram();
            askpassCmd.toFile().deleteOnExit();

            SshStartResult r2 = startTunnelHidden(
                    appDir, appKnownHosts, localBind, localPort, sshAlias, sshOptions, rdpHost, rdpPort,
                    false, // batchMode=no
                    askpassCmd.toAbsolutePath().toString()
            );

            Thread.sleep(300);
            if (isProcessAlive(r2.pid())) return r2;

            String tail2 = tailTextFile(r2.errLog(), 160);
            throw new IOException("SSH tunnel start failed (askpass).\n" + tail2);
        }

        // 入力要求ではない失敗
        throw new IOException("SSH tunnel start failed.\n" + tail);
    }

    private static SshStartResult startTunnelHidden(
            Path appDir,
            Path appKnownHosts,
            String localBind,
            int localPort,
            String sshAlias,
            String sshOptions,
            String rdpHost,
            int rdpPort,
            boolean batchMode,
            String askpassCmd // null のとき askpass 無効
    ) throws IOException, InterruptedException {

        String forward = localBind + ":" + localPort + ":" + rdpHost + ":" + rdpPort;

        String tempDir = System.getenv("TEMP");
        String outLog = Paths.get(tempDir, "rdp-launcher-ssh-out.log").toString();
        String errLog = Paths.get(tempDir, "rdp-launcher-ssh-err.log").toString();

        List<String> args = new ArrayList<>();
        args.add("-N");
        args.add("-T"); // pseudo-tty 不要
        args.add("-L");
        args.add(forward);

        args.add("-o"); args.add("ExitOnForwardFailure=yes");
        args.add("-o"); args.add("ConnectTimeout=10");

        // known_hosts をアプリ専用に（ユーザーの known_hosts を汚さない）
        args.add("-o"); args.add("StrictHostKeyChecking=accept-new");
        args.add("-o"); args.add("UserKnownHostsFile=" + appKnownHosts.toAbsolutePath());

        // BatchMode 切替
        args.add("-o"); args.add("BatchMode=" + (batchMode ? "yes" : "no"));

        // ユーザー指定（-p / -i など）
        args.addAll(splitSshOptions(sshOptions));

        args.add(sshAlias);

        Map<String, String> env = new HashMap<>();
        if (!batchMode && askpassCmd != null) {
            // GUI askpass を強制
            env.put("SSH_ASKPASS", askpassCmd);
            env.put("SSH_ASKPASS_REQUIRE", "force");
            env.put("DISPLAY", "1");
        }

        HiddenExec.StartResult sr = HiddenExec.startHiddenWithLogs(
                "ssh.exe",
                args,
                outLog,
                errLog,
                env
        );

        return new SshStartResult(sr.pid(), outLog, errLog);
    }

    /** askpass 用の .cmd を作る（ssh.exe が prompt を引数で渡してくる -> %* で転送） */
    private static Path createAskPassCmd() throws IOException {
        String javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String cp = System.getProperty("java.class.path");

        Path cmd = Files.createTempFile("rdp-launcher-askpass-", ".cmd");
        String content = ""
                + "@echo off\r\n"
                + "\"" + javaw + "\" -cp \"" + cp + "\" app.AskPassMain %*\r\n";
        Files.writeString(cmd, content, Charset.forName("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
        return cmd;
    }

    public static void stopSshIfNeeded(int pid) throws IOException, InterruptedException {
        if (pid <= 0) return;
        HiddenExec.runHiddenAndWait("taskkill.exe", List.of("/PID", String.valueOf(pid), "/T", "/F"));
    }

    public static boolean isProcessAlive(int pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean waitLocalPortOpen(String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (canConnect(host, port, 300)) return true;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static int findFreePort(String localBind) throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName(localBind))) {
            return s.getLocalPort();
        }
    }

    public static String tailTextFile(String path, int maxLines) {
        if (path == null || path.isBlank()) return "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(path), Charset.defaultCharset());
            if (lines.size() <= maxLines) return String.join(System.lineSeparator(), lines);
            return String.join(System.lineSeparator(), lines.subList(lines.size() - maxLines, lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    public static List<String> splitSshOptions(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String t = s.trim();
        if (t.isEmpty()) return out;

        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
    private static Path resolveAskPassProgram() throws IOException {
        String self = ProcessHandle.current().info().command().orElse(null);
        if (self != null) {
            String name = java.nio.file.Path.of(self).getFileName().toString().toLowerCase();
            // jpackage 実行時は .exe、開発時は java/javaw になりがち
            if (name.endsWith(".exe") && !name.equals("java.exe") && !name.equals("javaw.exe")) {
                return Path.of(self); // ←これを SSH_ASKPASS に入れる
            }
        }
        // 開発中（gradlew run 等）: exe じゃない場合は askpass は安定しないので、
        // 最低限のフォールバックとして従来の .cmd を返す（※ OpenSSH によっては動かない）
        return createAskPassCmd();
    }

}
