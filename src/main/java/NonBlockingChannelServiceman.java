import java.io.IOException;
import java.nio.channels.SelectableChannel;
import lombok.experimental.UtilityClass;

@UtilityClass
public class NonBlockingChannelServiceman {
    public static void setNonBlock(SelectableChannel channel) throws IOException {
        channel.configureBlocking(false);
    }
}
