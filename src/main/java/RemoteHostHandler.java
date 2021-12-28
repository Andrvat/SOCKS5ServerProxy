import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class RemoteHostHandler implements InetNodeHandler, Closeable {
    private static final Logger logger = LogManager.getLogger(RemoteHostHandler.class);

    private static final int BYTE_BUFFER_DEFAULT_CAPACITY = 8192;

    private final SocketChannel remoteHostSocketChanel;
    private final SelectionKey remoteHostSelectionKey;

    private final ClientHandler associatingClientHandler;

    private final ByteBuffer requestsToHostBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);
    private final ByteBuffer responsesFromHostBuffer = ByteBuffer.allocate(BYTE_BUFFER_DEFAULT_CAPACITY);

    private boolean isActive;

    public RemoteHostHandler(ClientHandler clientHandler, InetAddress hostAddress, int hostPort)
            throws IOException {
        this.associatingClientHandler = clientHandler;
        this.remoteHostSocketChanel = SocketChannel.open();
        NonBlockingChanelServiceman.setNonBlockingConfigToChanel(remoteHostSocketChanel);
        logger.info("Start connecting to remote host " +
                "with address + {" + hostAddress.getHostAddress() + "} and " +
                "port {" + hostPort + "}");
        this.remoteHostSocketChanel.connect(new InetSocketAddress(hostAddress, hostPort));
        // TODO: добавление в прокси-сервер в селект этот канал
        this.remoteHostSelectionKey = this.remoteHostSocketChanel.register(
                clientHandler.getAssociatingWithClientChanelSelector(),
                SelectionKey.OP_CONNECT
        );
    }

    private void connectToRemoteHost() {
        try {
            this.remoteHostSocketChanel.finishConnect();
            this.isActive = true;
            logger.info("Remote host connection has finished. Change options to OP_READ...");
            this.remoteHostSelectionKey.interestOps(SelectionKey.OP_READ); // TODO: ack?
            this.associatingClientHandler.setServerResponseType(
                    Socks5MessagesExplorer.getSucceededIndicator());
            this.associatingClientHandler.informAboutResponseReadiness();
        } catch (IOException exception) {
            logger.error(exception.getMessage());
            this.associatingClientHandler.setServerResponseType(
                    Socks5MessagesExplorer.getHostUnreachableIndicator());
            this.associatingClientHandler.informAboutResponseReadiness();
        }
    }

    private void readRemoteHostAnswer() {
        try {
            int readBytesNumber = this.remoteHostSocketChanel.read(responsesFromHostBuffer);
            if (isNoDataTransferThroughChanel(readBytesNumber)) {
                this.close();
                return;
            }
            logger.info("Got {" + readBytesNumber + "} from remote host. Transfer to client...");
            this.associatingClientHandler.informAboutHostDataOccurrence();
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private void writeRequestToRemoteHost() {
        try {
            this.requestsToHostBuffer.flip();
            int writeBytesNumber = this.remoteHostSocketChanel.write(requestsToHostBuffer);
            if (isNoDataTransferThroughChanel(writeBytesNumber)) {
                this.close();
                return;
            }
            logger.info("Sent {" + writeBytesNumber + "} to remote host. Continuing processing...");
            if (isAllDataProcessed(requestsToHostBuffer)) {
                this.requestsToHostBuffer.clear();
                this.remoteHostSelectionKey.interestOps(SelectionKey.OP_READ);
            } else {
                this.requestsToHostBuffer.compact();
            }
        } catch (IOException exception) {
            this.handleException(exception);
        }
    }

    private boolean isAllDataProcessed(ByteBuffer byteBuffer) {
        return byteBuffer.remaining() == 0;
    }

    private void handleException(Exception exception) {
        logger.error(exception.getMessage());
        this.close();
    }

    private boolean isNoDataTransferThroughChanel(int readBytesNumber) {
        return readBytesNumber <= 0;
    }

    @Override
    public void handle() {
        if (this.remoteHostSelectionKey.isConnectable()) {
            this.connectToRemoteHost();
            return;
        }
        if (this.remoteHostSelectionKey.isReadable()) {
            this.readRemoteHostAnswer();
            return;
        }
        if (this.remoteHostSelectionKey.isWritable()) {
            this.writeRequestToRemoteHost();
        }
    }

    @Override
    public void close() {
        this.remoteHostSelectionKey.cancel();
        // TODO: удалить из селектора этот ключ
        try {
            this.remoteHostSocketChanel.close();
            logger.info("Remote host socket chanel was closed");
        } catch (IOException exception) {
            logger.error(exception.getMessage());
        }
        this.isActive = false;
        this.responsesFromHostBuffer.flip();
        if (isAllDataProcessed(responsesFromHostBuffer) && this.associatingClientHandler.isActive()) {
            this.associatingClientHandler.close();
        }
    }
}
