import java.io.IOException;
import java.nio.channels.SelectableChannel;

public class NonBlockingChannelServiceman {
    public static void setNonBlock(SelectableChannel chanel) throws IOException {
        chanel.configureBlocking(false);
    }
}
