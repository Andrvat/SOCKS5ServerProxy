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

    private final SocketChannel clientSocketChanel;
    private final SelectionKey clientSelectionKey;

    private ClientStatement clientState;

    private byte authenticationMethod;
    private byte serverResponseType;

    private String requiredHostName;
    private InetAddress requiredHostInetAddress;
    private int requiredHostPort;

    private boolean isActive;

    public ClientHandler(SelectionKey serverSocketSelectionKey) throws IOException {
        this.clientSocketChanel = ((ServerSocketChannel) serverSocketSelectionKey.channel()).accept();
        NonBlockingChanelServiceman.setNonBlockingConfigToChanel(clientSocketChanel);
        this.clientSelectionKey = clientSocketChanel.register(serverSocketSelectionKey.selector(), SelectionKey.OP_READ);
        this.clientState = ClientStatement.SENDING_OPTION;
        this.isActive = true;
    }

    private void readClientInitialOption() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);
        try {
            int readBytesNumber = this.clientSocketChanel.read(byteBuffer);
            if (isNoDataReadFromChanel(readBytesNumber)) {
                this.close();
                return;
            }

            byte[] message = Arrays.copyOfRange(byteBuffer.array(), 0, readBytesNumber);
            logger.info("Start to handle client initial option. Option is {" + Arrays.toString(message) + "}");
            if (Socks5MessagesExplorer.isNotSocksVersion5(message)) {
                logger.error("Proxy server doesn't service no other SOCKS versions except the 5 ver.");
                this.close();
                return;
            }

            if (isClientRequiresAuthentication(message)) {
                logger.error("Client requires authentication in all passed methods");
                authenticationMethod = Socks5MessagesExplorer.getNoAcceptableMethodsIndicator();
            } else {
                authenticationMethod = Socks5MessagesExplorer.getAuthenticationIsNotRequiredIndicator();
            }
            this.clientState = ClientStatement.READING_SELECTED_OPTION;
            this.clientSelectionKey.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private boolean isNoDataReadFromChanel(int readBytesNumber) {
        return readBytesNumber <= 0;
    }

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
            this.clientSocketChanel.write(message);
            this.clientState = ClientStatement.SENDING_REQUEST;
            this.clientSelectionKey.interestOps(SelectionKey.OP_READ);
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private void readClientRequestDetails() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(16 * BYTE_BUFFER_DEFAULT_CAPACITY);
        try {
            int readBytesNumber = this.clientSocketChanel.read(byteBuffer);
            if (isNoDataReadFromChanel(readBytesNumber)) {
                this.close();
                return;
            }
            byte[] message = Arrays.copyOfRange(byteBuffer.array(), 0, readBytesNumber);
            logger.info("Start to handle client request details. Request is {" + Arrays.toString(message) + "}");

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

            byte inetAddressTypeCode = Socks5MessagesExplorer.getInetAddressTypeFromMessage(message);
            switch (RemoteHostAddressType.getTypeByCode(inetAddressTypeCode)) {
                case IPv4 -> {
                    byte[] requiredHostIPv4Bytes = Socks5MessagesExplorer.getRemoteHostIPv4AddressFromMessage(message);
                    this.requiredHostInetAddress = InetAddress.getByAddress(requiredHostIPv4Bytes);
                    this.requiredHostName = this.requiredHostInetAddress.getHostAddress();
                    // TODO: init host handler
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
                    // TODO: DNS Resolver
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

    private void writeProxyAnswerToClient() {
        // TODO; использовать данный метод и для отправки последующих сообщений клиенту (не только ответ на первый запрос)
        ByteBuffer message = ByteBuffer.wrap(this.getDummyAnswerWithSpecifiedResponseType());
        try {
            this.clientSocketChanel.write(message);
            logger.info("Proxy answer was sent to the client. Answer is {" + Arrays.toString(message.array()) + "}");
            if (Socks5MessagesExplorer.isResponseTypeSucceeded(this.serverResponseType)) {
                this.clientState = ClientStatement.CONTINUE_STAY_CONNECT;
                this.clientSelectionKey.interestOps(SelectionKey.OP_READ);
            } else {
                logger.error("Proxy server detected not succeeded response type");
                this.close();
            }
        } catch (IOException exception) {
            this.handleException(exception);
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
        ByteBuffer byteBuffer = ByteBuffer.allocate(16 * BYTE_BUFFER_DEFAULT_CAPACITY);
        try {
            int readBytesNumber = this.clientSocketChanel.read(byteBuffer);
            if (isNoDataReadFromChanel(readBytesNumber)) {
                this.close();
                return;
            }
            // TODO: передать прочитанные байты от клиента к удаленному хосту
            // TODO: поменять ожидающие события удаленного хоста на сохрание его ожидающих событий + ожидание записи (чтобы удаленному хосту передать байты от клиента)
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private void writeMessageToClient() {
        // TODO: обработка записи данных от удаленного хоста
    }

    private void handleException(Exception exception) {
        logger.error(exception.getMessage());
        this.close();
    }

    public boolean isActive() {
        return isActive;
    }

    public Selector getAssociatingWithClientChanelSelector() {
        return clientSelectionKey.selector();
    }

    public void informAboutResponseReadiness() {
        this.clientState = ClientStatement.READING_PROXY_ANSWER;
        this.clientSelectionKey.interestOps(SelectionKey.OP_WRITE);
        logger.info("Client was informed about remote host response readiness. Sending expected soon...");
    }

    public void informAboutHostDataOccurrence() {
        this.clientSelectionKey.interestOps(this.clientSelectionKey.interestOps() | SelectionKey.OP_WRITE);
    }

    public void setServerResponseType(byte serverResponseType) {
        this.serverResponseType = serverResponseType;
    }

    @Override
    public void close() {
        clientSelectionKey.cancel();
        try {
            clientSocketChanel.close();
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
        logger.info(this.getClass().getSimpleName() + " of " + requiredHostName + " has finished its work");
        isActive = false;
    }

    @Override
    public void handle() {
        switch (clientState) {
            case SENDING_OPTION -> this.readClientInitialOption();
            case READING_SELECTED_OPTION -> this.writeSelectedMethodToClient();
            case SENDING_REQUEST -> this.readClientRequestDetails();
            case WAITING_DNS_RESOLVER -> {
            }
            case WAITING_REMOTE_HOST -> {
            }
            case READING_PROXY_ANSWER -> this.writeProxyAnswerToClient();
            case CONTINUE_STAY_CONNECT -> this.communicateWithClient();
        }
    }
}
