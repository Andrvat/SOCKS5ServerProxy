import lombok.Builder;

@Builder
public class DNSRequest {
    private final ClientHandler correspondingClientHandler;
    private final String requiredRemoteHostname;

    public ClientHandler getCorrespondingClientHandler() {
        return correspondingClientHandler;
    }

    public String getRequiredRemoteHostname() {
        return requiredRemoteHostname;
    }
}
