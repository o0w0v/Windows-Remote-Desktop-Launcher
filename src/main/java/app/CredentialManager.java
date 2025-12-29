package app;

import java.io.IOException;
import java.util.List;

public final class CredentialManager {
    private CredentialManager() {}

    private static String target(String host) {
        return "TERMSRV/" + host;
    }

    public static void addTempCredential(String host, String username, String password)
            throws IOException, InterruptedException {

        int exit = HiddenExec.runHiddenAndWait("cmdkey.exe", List.of(
                "/generic:" + target(host),
                "/user:" + username,
                "/pass:" + password
        ));
        if (exit != 0) throw new IOException("cmdkey add failed (exit=" + exit + ")");
    }

    public static void deleteTempCredential(String host) throws IOException, InterruptedException {
        if (host == null || host.isBlank()) return;
        HiddenExec.runHiddenAndWait("cmdkey.exe", List.of("/delete:" + target(host)));
    }
}
