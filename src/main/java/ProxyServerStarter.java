import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.util.Arrays;

public class ProxyServerStarter {
    private static final Logger logger = LogManager.getLogger(ProxyServerStarter.class);

    public static void main(String[] args) {
        try {
            CmdArgsParser cmdArgsParser = new CmdArgsParser();
            cmdArgsParser.parseArguments(args);

            Socks5ProxyServer.getInstance().start(cmdArgsParser.getProxyServerPort());
        } catch (Exception exception) {
            logger.error(exception.getMessage());
            exception.printStackTrace();
        }
    }
}
