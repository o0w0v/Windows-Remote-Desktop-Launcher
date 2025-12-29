package app;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

public final class HiddenExec {

    private HiddenExec() {}

    public record StartResult(int pid, String outLog, String errLog) {}

    public static int runHiddenAndWait(String exe, List<String> args)
            throws IOException, InterruptedException {

        String argList = toPsArgList(args);

        String ps = ""
                + "$p = Start-Process -FilePath '" + esc(exe) + "' "
                + " -ArgumentList @(" + argList + ") "
                + " -WindowStyle Hidden -Wait -PassThru; "
                + "exit $p.ExitCode;";

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", ps
        );
        Process p = pb.start();
        return p.waitFor();
    }

    public static StartResult startHiddenWithLogs(
            String exe,
            List<String> args,
            String outLog,
            String errLog,
            Map<String, String> extraEnv // child に渡す環境変数
    ) throws IOException, InterruptedException {

        String argList = toPsArgList(args);

        String envLines = "";
        if (extraEnv != null && !extraEnv.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var e : extraEnv.entrySet()) {
                sb.append("$env:").append(e.getKey()).append("='").append(esc(e.getValue())).append("';");
            }
            envLines = sb.toString();
        }

        String ps = ""
                + envLines
                + "$p = Start-Process -FilePath '" + esc(exe) + "' "
                + " -ArgumentList @(" + argList + ") "
                + " -WindowStyle Hidden -PassThru "
                + " -RedirectStandardOutput '" + esc(outLog) + "' "
                + " -RedirectStandardError '" + esc(errLog) + "'; "
                + "Write-Output $p.Id;";

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", ps
        );
        pb.redirectErrorStream(true);

        Process p = pb.start();

        String pidLine;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
            pidLine = br.readLine();
        }
        int exit = p.waitFor();
        if (exit != 0 || pidLine == null || pidLine.isBlank()) {
            throw new IOException("Failed to start hidden process. exe=" + exe + " exit=" + exit + " pidLine=" + pidLine);
        }

        int pid = Integer.parseInt(pidLine.trim());
        return new StartResult(pid, outLog, errLog);
    }

    private static String toPsArgList(List<String> args) {
        if (args == null) args = List.of();
        return args.stream()
                .map(a -> "'" + esc(a) + "'")
                .collect(Collectors.joining(","));
    }

    private static String esc(String s) {
        return (s == null) ? "" : s.replace("'", "''");
    }
}
