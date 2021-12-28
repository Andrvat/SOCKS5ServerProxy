import java.io.IOException;
import java.nio.channels.SelectableChannel;

public class NonBlockingChanelServiceman {
    public static void setNonBlockingConfigToChanel(SelectableChannel chanel) throws IOException {
        chanel.configureBlocking(false);
    }
}
