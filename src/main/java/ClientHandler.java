import lombok.Setter;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

public class ClientHandler implements InetNodeHandler, Closeable {
    private static final Logger logger = LogManager.getLogger(ClientHandler.class);

    private static final int BYTE_BUFFER_DEFAULT_CAPACITY = 512;

    private static final int NO_INTERESTED_OPTIONS = 0;

    private final SocketChannel clientSocketChannel;
    private final SelectionKey clientSelectionKey;

    private ClientStatement clientState;

    private byte authenticationMethod;

    @Setter
    private byte serverResponseType;

    private String requiredHostName;
    private InetAddress requiredHostInetAddress;
    private int requiredHostPort;
    private RemoteHostHandler remoteHostHandler;

    private final Socks5ProxyServer associatingProxyServer;

    private boolean isActive;

    public ClientHandler(SelectionKey serverSocketSelectionKey, Socks5ProxyServer proxyServer) throws IOException {
        this.associatingProxyServer = proxyServer;
        this.clientSocketChannel = ((ServerSocketChannel) serverSocketSelectionKey.channel()).accept();
        NonBlockingChannelServiceman.setNonBlock(clientSocketChannel);
        // ??? Duplicate?
        this.associatingProxyServer.putInetNodeHandlerByItsChannel(this.clientSocketChannel, this);
        this.clientSelectionKey = clientSocketChannel.register(
                serverSocketSelectionKey.selector(), SelectionKey.OP_READ);
        this.clientState = ClientStatement.SENDING_METHODS;
        this.isActive = true;
    }

    private void readClientInitialMethods() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);
        try {
            int readBytesNumber = this.clientSocketChannel.read(byteBuffer);
            if (isNoDataTransferAcrossChannel(readBytesNumber)) {
                this.close();
                return;
            }

            byte[] message = Arrays.copyOfRange(byteBuffer.array(), 0, readBytesNumber);
            logger.info("Start to handle client initial option. Option: " + Arrays.toString(message));
            if (Socks5MessagesExplorer.isNotSocksVersion5(message)) {
                logger.error("Proxy server doesn't service no other SOCKS versions except the 5 ver.");
                this.close();
                return;
            }

            if (isClientRequiresAuthentication(message)) {
                logger.error("Client requires authentication in all passed methods");
                this.authenticationMethod = Socks5MessagesExplorer.getNoAcceptableMethodsIndicator();
            } else {
                this.authenticationMethod = Socks5MessagesExplorer.getAuthenticationIsNotRequiredIndicator();
            }
            this.clientState = ClientStatement.WAITING_SELECTED_METHOD;
            this.clientSelectionKey.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException e) {
            this.handleException(e);
        }
    }

    private boolean isNoDataTransferAcrossChannel(int transferBytesNumber) {
        return transferBytesNumber <= 0;
    }

    // TODO: is it correct logic?
    private boolean isClientRequiresAuthentication(byte[] message) {
        boolean isAuthRequires = true;
        int authMethodsNumber = Socks5MessagesExplorer.getAuthMethodsNumberFromMessage(message);
        for (int i = 0; i < authMethodsNumber; ++i) {
            byte authMethodIndicator = Socks5MessagesExplorer.getIthAuthMethodFromMessage(message, i);
            if (Socks5MessagesExplorer.isNotRequiredAuthMethodDetected(authMethodIndicator)) {
                isAuthRequires = false;
                break;
            }
        }
        return isAuthRequires;
    }

    private void writeSelectedMethodToClient() {
        ByteBuffer message = ByteBuffer.wrap(new byte[]{
                Socks5MessagesExplorer.getSocks5VersionIndicator(),
                this.authenticationMethod
        });
        try {
            this.clientSocketChannel.write(message);
            this.clientState = ClientStatement.SENDING_REQUEST;
            this.clientSelectionKey.interestOps(SelectionKey.OP_READ);
        } catch (IOException e) {
            this.handleException(e);
        }
    }

    private void readClientRequestDetails() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(16 * BYTE_BUFFER_DEFAULT_CAPACITY);
        try {
            int readBytesNumber = this.clientSocketChannel.read(byteBuffer);
            if (isNoDataTransferAcrossChannel(readBytesNumber)) {
                this.close();
                return;
            }
            byte[] message = Arrays.copyOfRange(byteBuffer.array(), 0, readBytesNumber);
            logger.info("Start to handle client request details. Request: " + Arrays.toString(message));

            if (Socks5MessagesExplorer.isNotSocksVersion5(message)) {
                logger.error("Proxy server doesn't service no other SOCKS versions except the 5 ver.");
                this.close();
                return;
            }

            byte commandType = Socks5MessagesExplorer.getRequestedCommandTypeFromMessage(message);
            if (!Socks5MessagesExplorer.isEstablishConnectionDetected(commandType)) {
                logger.error("Provided command {" + commandType + "}. " +
                        "Proxy server can service only ESTABLISH TCP/IP CONNECTION command type");
                this.serverResponseType = Socks5MessagesExplorer.getCommandNotSupportedIndicator();
                this.clientState = ClientStatement.READING_PROXY_ANSWER;
                this.clientSelectionKey.interestOps(SelectionKey.OP_WRITE);
                return;
            }

            this.requiredHostPort = ByteBuffer.wrap(Arrays.copyOfRange(message, readBytesNumber - 2, readBytesNumber)).getShort();

            byte inetAddressTypeCode = Socks5MessagesExplorer.getInetAddressTypeFromMessage(message);
            switch (RemoteHostAddressType.getTypeByCode(inetAddressTypeCode)) {
                case IPv4 -> {
                    byte[] requiredHostIPv4Bytes = Socks5MessagesExplorer.getRemoteHostIPv4AddressFromMessage(message);
                    this.requiredHostInetAddress = InetAddress.getByAddress(requiredHostIPv4Bytes);
                    this.requiredHostName = this.requiredHostInetAddress.getHostAddress();
                    this.initCorrespondingRemoteHostHandler();
                    this.clientState = ClientStatement.WAITING_REMOTE_HOST;
                    this.clientSelectionKey.interestOps(NO_INTERESTED_OPTIONS);
                }
                case IPv6 -> {
                    logger.error("Proxy server doesn't service IPv6 addresses");
                    this.serverResponseType = Socks5MessagesExplorer.getAddressTypeNotSupportedIndicator();
                    this.clientState = ClientStatement.READING_PROXY_ANSWER;
                    this.clientSelectionKey.interestOps(SelectionKey.OP_WRITE);
                    return;
                }
                case DOMAIN_NAME -> {
                    this.requiredHostName = Socks5MessagesExplorer.getDomainNameFromMessage(message);
                    logger.info("Remote host has name {" + requiredHostName + "}");
                    DNSRequest dnsRequest = DNSRequest.builder()
                            .correspondingClientHandler(this)
                            .requiredRemoteHostname(requiredHostName)
                            .build();
                    this.associatingProxyServer.getDnsResolver().addDNSRequestToQueue(dnsRequest);
                    this.clientState = ClientStatement.WAITING_DNS_RESOLVER;
                    this.clientSelectionKey.interestOps(NO_INTERESTED_OPTIONS);
                }
            }

            byte[] requiredHostPortBytes = Socks5MessagesExplorer.getRemoteHostPortFromMessage(message, readBytesNumber);
            this.requiredHostPort = ByteBuffer.wrap(requiredHostPortBytes).getShort();
            logger.info("Remote host has port {" + requiredHostPort + "}");
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private void initCorrespondingRemoteHostHandler() {
        try {
            this.remoteHostHandler = new RemoteHostHandler(this,
                    this.requiredHostInetAddress,
                    this.requiredHostPort,
                    this.associatingProxyServer);
        } catch (IOException e) {
            logger.error(e.getMessage());
            this.close();
        }
    }

    private void writeProxyAnswerToClient() {
        ByteBuffer message = ByteBuffer.wrap(this.getDummyAnswerWithSpecifiedResponseType());
        try {
            this.clientSocketChannel.write(message);
            logger.info("Proxy answer was sent to the client. Answer is " + Arrays.toString(message.array()));
            if (Socks5MessagesExplorer.isResponseTypeSucceeded(this.serverResponseType)) {
                this.clientState = ClientStatement.CONTINUE_STAY_CONNECT;
                this.clientSelectionKey.interestOps(SelectionKey.OP_READ);
            } else {
                logger.error("Proxy server detected not succeeded response type");
                this.close();
            }
        } catch (IOException e) {
            this.handleException(e);
        }
    }

    private byte[] getDummyAnswerWithSpecifiedResponseType() {
        return new byte[]{
                Socks5MessagesExplorer.getSocks5VersionIndicator(),
                this.serverResponseType,
                0x00,
                RemoteHostAddressType.IPv4.getValue(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
        };
    }

    private void communicateWithClient() {
        if (this.clientSelectionKey.isReadable()) {
            this.readClientMessage();
            return;
        }
        if (this.clientSelectionKey.isWritable()) {
            this.writeMessageToClient();
        }
    }

    private void readClientMessage() {
        try {
            ByteBuffer correspondingRemoteHostHandlerBuffer = this.remoteHostHandler.getRequestsToHostBuffer();
            int readBytesNumber = this.clientSocketChannel.read(
                    correspondingRemoteHostHandlerBuffer
            );
            if (isNoDataTransferAcrossChannel(readBytesNumber)) {
                this.close();
                return;
            }
            logger.info("Client handler read {" + readBytesNumber + "} bytes from client");
            this.remoteHostHandler.getRemoteHostSelectionKey().interestOps(
                    this.remoteHostHandler.getRemoteHostSelectionKey().interestOps() | SelectionKey.OP_WRITE
            );
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private void writeMessageToClient() {
        ByteBuffer correspondingRemoteHostHandlerBuffer = this.remoteHostHandler.getResponsesFromHostBuffer();
        try {
            int transferBytesNumber;
            correspondingRemoteHostHandlerBuffer.flip();
            transferBytesNumber = this.clientSocketChannel.write(correspondingRemoteHostHandlerBuffer);
            if (this.remoteHostHandler.getRemoteHostSelectionKey().isValid()) {
                this.remoteHostHandler.getRemoteHostSelectionKey().interestOps(
                        this.remoteHostHandler.getRemoteHostSelectionKey().interestOps() | SelectionKey.OP_READ);
            }
            if (isNoDataTransferAcrossChannel(transferBytesNumber)) {
                this.close();
                return;
            }
            logger.info("Client handler wrote {" + transferBytesNumber + "} bytes to client");
            if (correspondingRemoteHostHandlerBuffer.remaining() != 0) {
                correspondingRemoteHostHandlerBuffer.compact();
                return;
            }
            correspondingRemoteHostHandlerBuffer.clear();
            this.clientSelectionKey.interestOps(SelectionKey.OP_READ);
            if (!this.remoteHostHandler.isActive()) {
                this.close();
            }
        } catch (IOException e) {
            this.handleException(e);
        }
    }

    private void handleException(Exception exception) {
        logger.error(exception.getMessage());
        this.close();
    }

    public boolean isActive() {
        return isActive;
    }

    public Selector getAssociatingWithClientChannelSelector() {
        return clientSelectionKey.selector();
    }

    public void informAboutResponseReadiness() {
        this.clientState = ClientStatement.READING_PROXY_ANSWER;
        this.clientSelectionKey.interestOps(SelectionKey.OP_WRITE);
        logger.info("Client was informed about remote host response readiness");
    }

    public void informAboutHostDataOccurrence() {
        this.clientSelectionKey.interestOps(this.clientSelectionKey.interestOps() | SelectionKey.OP_WRITE);
    }

    public void setRequiredHostInetAddress(InetAddress requiredHostInetAddress) {
        if (this.clientState.equals(ClientStatement.WAITING_DNS_RESOLVER)) {
            if (requiredHostInetAddress == null) {
                logger.warn("Dns resolver sent to client handler null inet address");
                this.serverResponseType = Socks5MessagesExplorer.getHostUnreachableIndicator();
                this.informAboutResponseReadiness();
            } else {
                logger.info("Dns resolver sent inet address: " + requiredHostInetAddress);
                this.requiredHostInetAddress = requiredHostInetAddress;
                this.clientState = ClientStatement.WAITING_REMOTE_HOST;
                this.initCorrespondingRemoteHostHandler();
            }
        }
    }

    @Override
    public void close() {
        clientSelectionKey.cancel();
        this.associatingProxyServer.removeInetNodeHandlerByItsChannel(this.clientSocketChannel);
        try {
            clientSocketChannel.close();
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
        logger.info(this.getClass().getSimpleName() + " of " + requiredHostName + " finished");
        isActive = false;
        if (this.remoteHostHandler != null && this.remoteHostHandler.isActive()) {
            this.remoteHostHandler.close();
        }
    }

    @Override
    public void handleEvent() {
        switch (clientState) {
            case SENDING_METHODS -> this.readClientInitialMethods();
            case WAITING_SELECTED_METHOD -> this.writeSelectedMethodToClient();
            case SENDING_REQUEST -> this.readClientRequestDetails();
            case READING_PROXY_ANSWER -> this.writeProxyAnswerToClient();
            case CONTINUE_STAY_CONNECT -> this.communicateWithClient();
            default -> logger.warn("Unexpected handling...");
        }
    }
}
