import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Socks5ProxyServer {
    private static final Logger logger = LogManager.getLogger(Socks5ProxyServer.class);

    private static final String PROXY_SERVER_IPv4_ADDRESS = "127.0.0.1";
    private int proxyPort;

    private ServerSocketChannel proxyServerSocketChannel;
    private SelectionKey proxyServerSelectionKey;

    private Selector eventsSelector;

    private final Map<SelectableChannel, InetNodeHandler> inetNodeHandlersByTheirChannels = new ConcurrentHashMap<>();

    private static Socks5ProxyServer instance;

    private Socks5ProxyServer() {
    }

    public static synchronized Socks5ProxyServer getInstance() {
        if (instance == null) {
            instance = new Socks5ProxyServer();
        }
        return instance;
    }

    public void start(int proxyPort) {
        this.proxyPort = proxyPort;
        try {
            this.configureProxyServer();
        } catch (IOException exception) {
            logger.error(exception.getMessage());
            return;
        }
        this.processClientsInLoop();
    }

    private void configureProxyServer() throws IOException {
        this.eventsSelector = SelectorProvider.provider().openSelector();
        this.proxyServerSocketChannel = ServerSocketChannel.open();
        NonBlockingChannelServiceman.setNonBlock(proxyServerSocketChannel);
        this.proxyServerSocketChannel.bind(new InetSocketAddress(PROXY_SERVER_IPv4_ADDRESS, this.proxyPort));
        this.proxyServerSelectionKey = this.proxyServerSocketChannel.
                register(this.eventsSelector, SelectionKey.OP_ACCEPT);
        DNSResolver.getInstance().startResolving(this.eventsSelector);
    }

    private void processClientsInLoop() {
        logger.info("Proxy server starts working, IPv4: " + PROXY_SERVER_IPv4_ADDRESS + ". Port: " + this.proxyPort);
        try {
            while (true) {
                this.eventsSelector.select();
                this.processSelectedEvents(this.eventsSelector.selectedKeys().iterator());
            }
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
    }

    private void processSelectedEvents(Iterator<SelectionKey> selectedEventsKeys) {
        while (selectedEventsKeys.hasNext()) {
            SelectionKey eventKey = selectedEventsKeys.next();
            selectedEventsKeys.remove();
            if (eventKey.isValid()) {
                if (eventKey.isAcceptable()) {
                    this.acceptNewClient(eventKey);
                } else {
                    this.inetNodeHandlersByTheirChannels.get(eventKey.channel()).handleEvent();
                }
            }
        }
    }

    private void acceptNewClient(SelectionKey acceptKey) {
        try {
            ClientHandler clientHandler = new ClientHandler(acceptKey);
            this.putInetNodeHandlerByItsChannel(acceptKey.channel(), clientHandler);
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
    }

    public void putInetNodeHandlerByItsChannel(SelectableChannel channel, InetNodeHandler handler) {
        inetNodeHandlersByTheirChannels.put(channel, handler);
    }

    public void removeInetNodeHandlerByItsChannel(SelectableChannel channel) {
        inetNodeHandlersByTheirChannels.remove(channel);
    }
}
