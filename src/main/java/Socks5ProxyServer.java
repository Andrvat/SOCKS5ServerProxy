import lombok.Getter;
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

    private Selector eventsSelector;

    @Getter
    private DNSResolver dnsResolver;

    private final Map<SelectableChannel, InetNodeHandler> inetNodeHandlersByTheirChannels = new ConcurrentHashMap<>();

    public void start(int proxyPort) {
        this.proxyPort = proxyPort;
        this.dnsResolver = new DNSResolver();
        try {
            this.configureProxyServer();
        } catch (IOException e) {
            logger.error(e.getMessage());
            return;
        }
        this.processClientsInLoop();
    }

    private void configureProxyServer() throws IOException {
        this.eventsSelector = SelectorProvider.provider().openSelector();
        ServerSocketChannel proxyServerSocketChannel = ServerSocketChannel.open();
        NonBlockingChannelServiceman.setNonBlock(proxyServerSocketChannel);
        proxyServerSocketChannel.bind(new InetSocketAddress(PROXY_SERVER_IPv4_ADDRESS, this.proxyPort));
        proxyServerSocketChannel.register(this.eventsSelector, SelectionKey.OP_ACCEPT);
        this.dnsResolver.startResolving(this.eventsSelector, this);
    }

    private void processClientsInLoop() {
        logger.info("Proxy server starts working, IPv4: " + PROXY_SERVER_IPv4_ADDRESS + ". Port: " + this.proxyPort);
        try {
            while (true) {
                this.eventsSelector.select();
                this.processSelectedEvents(this.eventsSelector.selectedKeys().iterator());
            }
        } catch (IOException e) {
            logger.error(e.getMessage());
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
            ClientHandler clientHandler = new ClientHandler(acceptKey, this);
            this.putInetNodeHandlerByItsChannel(acceptKey.channel(), clientHandler);
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
    }

    public void putInetNodeHandlerByItsChannel(SelectableChannel channel, InetNodeHandler handler) {
        inetNodeHandlersByTheirChannels.put(channel, handler);
    }

    public void removeInetNodeHandlerByItsChannel(SelectableChannel channel) {
        inetNodeHandlersByTheirChannels.remove(channel);
    }
}
