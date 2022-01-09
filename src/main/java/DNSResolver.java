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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

public class DNSResolver implements InetNodeHandler {
    private static final Logger logger = LogManager.getLogger(DNSResolver.class);

    private static final int BYTE_BUFFER_DEFAULT_CAPACITY = 512;

    private static final int NO_INTERESTED_OPTIONS = 0;

    private static final int RESEND_REQUEST_PERIOD_MILLIS = 10000;

    private DatagramChannel dnsResolverDatagramChannel;
    private SelectionKey dnsResolverSelectionKey;

    private Queue<DNSRequest> requestsQueue;
    private Map<Name, ClientHandler> clientHandlersDnsResponses;

    private Map<Name, CustomPair<DNSRequest, Instant>> pendingConfirmationRequests;

    public void startResolving(Selector proxyServerSelector, Socks5ProxyServer proxyServer) throws IOException {
        InetSocketAddress dnsResolverInetSocketAddress = ResolverConfig.getCurrentConfig().server();
        this.dnsResolverDatagramChannel = DatagramChannel.open();
        this.dnsResolverDatagramChannel.socket().connect(dnsResolverInetSocketAddress);
        NonBlockingChannelServiceman.setNonBlock(dnsResolverDatagramChannel);
        this.dnsResolverSelectionKey = this.dnsResolverDatagramChannel.
                register(proxyServerSelector, NO_INTERESTED_OPTIONS);
        proxyServer.putInetNodeHandlerByItsChannel(this.dnsResolverDatagramChannel, this);
        requestsQueue = new ConcurrentLinkedDeque<>();
        pendingConfirmationRequests = new ConcurrentHashMap<>();
        clientHandlersDnsResponses = new ConcurrentHashMap<>();
        logger.info("DNS Resolver has started,,,");
    }

    public void addDNSRequestToQueue(DNSRequest request) {
        this.requestsQueue.add(request);
        this.dnsResolverSelectionKey.interestOps(
                this.dnsResolverSelectionKey.interestOps() | SelectionKey.OP_WRITE
        );
        logger.info("Added new dns request: " + request);
    }

    @Override
    public void handleEvent() {
        long currentTime = System.currentTimeMillis();
        for (var item : this.pendingConfirmationRequests.entrySet()) {
            if (Math.abs(item.getValue().getSecond().toEpochMilli() - currentTime) > RESEND_REQUEST_PERIOD_MILLIS) {
                this.pendingConfirmationRequests.remove(item.getKey());
                this.requestsQueue.add(item.getValue().getFirst());
            }
        }

        if (this.dnsResolverSelectionKey.isReadable()) {
            this.readDnsRemoteResolverResponse();
        }

        if (this.dnsResolverSelectionKey.isWritable()) {
            this.sendDnsRequestToRemoteResolver();
        }
    }

    private void readDnsRemoteResolverResponse() {
        ByteBuffer dnsResponsesBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);
        int readBytesNumber;
        try {
            readBytesNumber = this.dnsResolverDatagramChannel.read(dnsResponsesBuffer);
            if (isNoDataTransferThroughChannel(readBytesNumber)) {
                logger.warn("No data read from dns resolver datagram channel");
                return;
            }
            Message remoteResolverResponse = new Message(dnsResponsesBuffer.array());
            Name resolvingHostname = remoteResolverResponse.getQuestion().getName();
            this.pendingConfirmationRequests.remove(resolvingHostname);
            List<Record> foundInetAddressRecords = remoteResolverResponse.getSection(Section.ANSWER);
            ClientHandler correspondingClientHandler = this.clientHandlersDnsResponses.get(resolvingHostname);
            for (var foundRecord : foundInetAddressRecords) {
                if (foundRecord.getType() == Type.A) {
                    correspondingClientHandler.setRequiredHostInetAddress(
                            ((ARecord) foundRecord).getAddress());
                    return;
                }
            }
            correspondingClientHandler.setRequiredHostInetAddress(null);
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
            ByteBuffer dnsRequestsBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);
            byte[] messageBytes = dnsMessage.toWire();
            dnsRequestsBuffer.put(messageBytes);
            dnsRequestsBuffer.flip();
            logger.info("Sending dns request: " + requestToSent);
            this.dnsResolverDatagramChannel.write(dnsRequestsBuffer);
            this.pendingConfirmationRequests.put(resolvingName, new CustomPair<>(requestToSent, Instant.now()));
            this.dnsResolverSelectionKey.interestOps(
                    this.dnsResolverSelectionKey.interestOps() | SelectionKey.OP_READ
            );
            if (this.requestsQueue.isEmpty()) {
                this.dnsResolverSelectionKey.interestOps(SelectionKey.OP_READ);
            }
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
