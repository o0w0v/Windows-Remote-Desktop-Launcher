package app;

public record Session(
        String name,
        boolean useBastion,
        String sshAlias,
        String jumpHosts,
        String sshOptions,
        boolean useRdGateway,
        String rdGatewayHost,
        boolean rdGatewayUseCurrentUser,
        boolean rdGatewayShareCreds,
        String rdpHost,
        int rdpPort,
        String username,
        String domain,
        boolean fullscreen,
        Integer width,
        Integer height,
        boolean multimon,
        boolean span,
        String selectedMonitors
) {
    @Override public String toString() { return name; }
}
