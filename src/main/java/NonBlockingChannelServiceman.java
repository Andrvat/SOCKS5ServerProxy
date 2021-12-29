import java.io.IOException;
import java.nio.channels.SelectableChannel;

public class NonBlockingChannelServiceman {
    public static void setNonBlock(SelectableChannel channel) throws IOException {
        channel.configureBlocking(false);
    }
}
