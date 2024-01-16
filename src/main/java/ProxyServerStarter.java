import lombok.extern.slf4j.Slf4j;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

public class ProxyServerStarter {

    private static final Logger logger = LogManager.getLogger(ProxyServerStarter.class);

    public static void main(String[] args) {
        try {
            CmdArgsParser cmdArgsParser = new CmdArgsParser();
            cmdArgsParser.parseArguments(args);

            Socks5ProxyServer proxyServer = new Socks5ProxyServer();
            proxyServer.start(cmdArgsParser.getProxyServerPort());
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }
}
