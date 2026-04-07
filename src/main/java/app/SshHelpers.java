package app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class SshHelpers {

    private SshHelpers() {}

    public record SshStartResult(int pid, String outLog, String errLog) {}

    private static final Pattern NEEDS_INPUT = Pattern.compile(
            "(?i)(password|passphrase|keyboard-interactive|verification code|one-time|otp|enter.*pass|permission denied)"
    );

    public static SshStartResult startSshTunnelSmart(
            Path appDir,
            Path appKnownHosts,
            String localBind,
            int localPort,
            String sshAlias,
            String jumpHosts,
            String sshOptions,
            String rdpHost,
            int rdpPort
    ) throws IOException, InterruptedException {

        Files.createDirectories(appDir);

        SshStartResult r1 = startTunnelHidden(
                appDir, appKnownHosts, localBind, localPort, sshAlias, jumpHosts, sshOptions, rdpHost, rdpPort,
                true,
                null
        );

        Thread.sleep(300);
        if (isProcessAlive(r1.pid())) return r1;

        String tail = tailTextFile(r1.errLog(), 120);
        if (NEEDS_INPUT.matcher(tail).find()) {
            Path askpassCmd = resolveAskPassProgram();
            askpassCmd.toFile().deleteOnExit();

            SshStartResult r2 = startTunnelHidden(
                    appDir, appKnownHosts, localBind, localPort, sshAlias, jumpHosts, sshOptions, rdpHost, rdpPort,
                    false,
                    askpassCmd.toAbsolutePath().toString()
            );

            Thread.sleep(300);
            if (isProcessAlive(r2.pid())) return r2;

            String tail2 = tailTextFile(r2.errLog(), 160);
            String outTail2 = tailTextFile(r2.outLog(), 80);
            throw new IOException("SSH tunnel start failed (askpass).\nSTDERR:\n"
                    + tail2 + "\nSTDOUT:\n" + outTail2);
        }

        throw new IOException("SSH tunnel start failed.\n" + tail);
    }

    private static SshStartResult startTunnelHidden(
            Path appDir,
            Path appKnownHosts,
            String localBind,
            int localPort,
            String sshAlias,
            String jumpHosts,
            String sshOptions,
            String rdpHost,
            int rdpPort,
            boolean batchMode,
            String askpassCmd
    ) throws IOException, InterruptedException {

        String forward = localBind + ":" + localPort + ":" + rdpHost + ":" + rdpPort;

        String tempDir = System.getenv("TEMP");
        String outLog = Paths.get(tempDir, "rdp-launcher-ssh-out.log").toString();
        String errLog = Paths.get(tempDir, "rdp-launcher-ssh-err.log").toString();

        List<String> args = new ArrayList<>();
        args.add("-N");
        args.add("-T");
        args.add("-L");
        args.add(forward);

        args.add("-o"); args.add("ExitOnForwardFailure=yes");
        args.add("-o"); args.add("ConnectTimeout=10");
        args.add("-o"); args.add("StrictHostKeyChecking=accept-new");
        args.add("-o"); args.add("UserKnownHostsFile=" + appKnownHosts.toAbsolutePath());
        args.add("-o"); args.add("BatchMode=" + (batchMode ? "yes" : "no"));

        if (jumpHosts != null && !jumpHosts.isBlank() && !containsProxyJumpOption(sshOptions)) {
            args.add("-J");
            args.add(normalizeJumpHosts(jumpHosts));
        }

        args.addAll(splitSshOptions(sshOptions));
        args.add(sshAlias);

        Map<String, String> env = new HashMap<>();
        if (!batchMode && askpassCmd != null) {
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

    private static boolean containsProxyJumpOption(String sshOptions) {
        if (sshOptions == null || sshOptions.isBlank()) return false;
        String normalized = " " + sshOptions.toLowerCase() + " ";
        return normalized.contains(" -j ")
                || normalized.contains("proxyjump")
                || normalized.contains("proxycommand");
    }

    private static String normalizeJumpHosts(String jumpHosts) {
        String[] parts = jumpHosts.split(",");
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.isEmpty()) cleaned.add(value);
        }
        return String.join(",", cleaned);
    }

    private static Path createAskPassCmd() throws IOException {
        String javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String cp = System.getProperty("java.class.path");
        String appExe = ProcessHandle.current().info().command().orElse("");

        Path cmd = Files.createTempFile("rdp-launcher-askpass-", ".cmd");
        String command;
        if (isPackagedAppExe(appExe)) {
            command = "\"" + appExe + "\" %*";
        } else {
            command = "\"" + javaw + "\" -cp \"" + cp + "\" app.AskPassMain %*";
        }

        String content = "@echo off\r\n" + command + "\r\n";
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
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
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
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static Path resolveAskPassProgram() throws IOException {
        // Use a tiny .cmd shim consistently. OpenSSH for Windows handles this
        // more reliably than passing the packaged GUI exe directly as SSH_ASKPASS.
        return createAskPassCmd();
    }

    private static boolean isPackagedAppExe(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String name = Path.of(command).getFileName().toString().toLowerCase();
        return name.endsWith(".exe") && !name.equals("java.exe") && !name.equals("javaw.exe");
    }
}
