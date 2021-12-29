import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class DNSResolver implements InetNodeHandler {
    private static final Logger logger = LogManager.getLogger(DNSResolver.class);

    private static final int BYTE_BUFFER_DEFAULT_CAPACITY = 512;

    private static final int NO_INTERESTED_OPTIONS = 0;

    private DatagramChannel dnsResolverDatagramChannel;
    private SelectionKey dnsResolverSelectionKey;

    private final Queue<DNSRequest> requestsQueue = new ConcurrentLinkedDeque<>();
    private final Map<Name, ClientHandler> clientHandlersDnsResponses = new ConcurrentHashMap<>();

    private final ByteBuffer dnsRequestsBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);
    private final ByteBuffer dnsResponsesBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);

    private boolean isActive;

    private static DNSResolver instance;

    private DNSResolver() {
    }

    public static synchronized DNSResolver getInstance() {
        if (instance == null) {
            instance = new DNSResolver();
        }
        return instance;
    }

    public void startResolving(Selector proxyServerSelector) throws IOException {
        InetSocketAddress dnsResolverInetSocketAddress = ResolverConfig.getCurrentConfig().server();
        this.dnsResolverDatagramChannel = DatagramChannel.open();
        this.dnsResolverDatagramChannel.socket().connect(dnsResolverInetSocketAddress);
        NonBlockingChannelServiceman.setNonBlock(dnsResolverDatagramChannel);
        this.dnsResolverSelectionKey = this.dnsResolverDatagramChannel.
                register(proxyServerSelector, NO_INTERESTED_OPTIONS);
        Socks5ProxyServer.getInstance().putInetNodeHandlerByItsChannel(this.dnsResolverDatagramChannel, this);
        logger.info("DNS Resolver has started,,,");
    }

    public void addDNSRequestToQueue(DNSRequest request) {
        this.requestsQueue.add(request);
        this.dnsResolverSelectionKey.interestOps(
                this.dnsResolverSelectionKey.interestOps() | SelectionKey.OP_WRITE
        );
        logger.info("New DNS request " + request);
    }

    @Override
    public void handleEvent() {
        if (this.dnsResolverSelectionKey.isReadable()) {
            this.readDnsRemoteResolverResponse();
        }

        if (this.dnsResolverSelectionKey.isWritable()) {
            this.sendDnsRequestToRemoteResolver();
        }
    }

    private void readDnsRemoteResolverResponse() {
        this.dnsResponsesBuffer.clear();
        int readBytesNumber;
        try {
            readBytesNumber = this.dnsResolverDatagramChannel.read(dnsResponsesBuffer);
            if (isNoDataTransferThroughChannel(readBytesNumber)) {
                logger.debug("No data read from dns resolver datagram channel");
                return;
            }
            Message remoteResolverResponse = new Message(dnsResponsesBuffer);
            Name resolvingHostname = remoteResolverResponse.getQuestion().getName();
            List<Record> foundInetAddressRecords = remoteResolverResponse.getSection(Section.ANSWER);
            if (!this.clientHandlersDnsResponses.containsKey(resolvingHostname)) {
                logger.debug("No corresponding hostname in client handlers responses map");
                return;
            }
            ClientHandler correspondingClientHandler = this.clientHandlersDnsResponses.get(resolvingHostname);
            for (var foundRecord : foundInetAddressRecords) {
                if (foundRecord.getType() == Type.A) {
                    correspondingClientHandler.setRequiredHostInetAddress(
                            ((ARecord) foundRecord).getAddress());
                    return;
                }
            }
            correspondingClientHandler.setRequiredHostInetAddress(null);
            logger.warn("Required host inet address is null by dns resolver");
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
    }

    private void sendDnsRequestToRemoteResolver() {
        Message dnsMessage = new Message();
        Header dnsHeader = new Header();
        this.setRecursiveDesiredOption(dnsHeader);
        dnsMessage.setHeader(dnsHeader);

        DNSRequest requestToSent;
        try {
            requestToSent = this.requestsQueue.remove();
        } catch (NoSuchElementException exception) {
            logger.error(exception.getMessage());
            this.dnsResolverSelectionKey.interestOps(SelectionKey.OP_READ);
            return;
        }

        try {
            Name resolvingName = Name.fromString(requestToSent.getRequiredRemoteHostname(), Name.root);
            this.clientHandlersDnsResponses.put(resolvingName, requestToSent.getCorrespondingClientHandler());
            Record dnsRecord = Record.newRecord(
                    resolvingName,
                    Type.A,
                    DClass.IN);
            dnsMessage.addRecord(dnsRecord, Section.QUESTION);
            byte[] messageBytes = dnsMessage.toWire();
            this.dnsRequestsBuffer.clear();
            this.dnsRequestsBuffer.put(messageBytes);
            this.dnsRequestsBuffer.flip();
            this.dnsResolverDatagramChannel.write(this.dnsRequestsBuffer);
            this.dnsResolverSelectionKey.interestOps(
                    this.dnsResolverSelectionKey.interestOps() | SelectionKey.OP_READ
            );
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
    }

    private void setRecursiveDesiredOption(Header requestHeader) {
        requestHeader.setFlag(Flags.RD);
    }

    private boolean isNoDataTransferThroughChannel(int readBytesNumber) {
        return readBytesNumber <= 0;
    }
}
