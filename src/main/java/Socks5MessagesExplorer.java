import java.util.Arrays;

public class Socks5MessagesExplorer {
    private static final int MESSAGE_SOCKS_VERSION_INDEX = 0;
    private static final int MESSAGE_METHODS_NUMBER_INDEX = 1;
    private static final int MESSAGE_METHODS_BEGINNING_INDEX = 2;
    private static final int MESSAGE_IPv4_BEGINNING_INDEX = 4;
    private static final int MESSAGE_IPv4_END_INDEX = 8;

    private static final int MESSAGE_REQUESTED_COMMAND_INDEX = 1;
    private static final int MESSAGE_INET_ADDRESS_TYPE_INDEX = 3;
    private static final int MESSAGE_DOMAIN_NAME_LENGTH_INDEX = 4;
    private static final int MESSAGE_DOMAIN_NAME_BEGINNING_INDEX = 5;

    private static final byte SOCKS_5_VERSION_INDICATOR = 0x05;
    private static final byte AUTHENTICATION_IS_NOT_REQUIRED_INDICATOR = 0x00;
    private static final byte ESTABLISH_TCP_IP_CONNECTION_INDICATOR = 0x01;
    private static final byte COMMAND_NOT_SUPPORTED_INDICATOR = 0x07;
    private static final byte SUCCEEDED_INDICATOR = 0x00;
    private static final byte HOST_UNREACHABLE_INDICATOR = 0x04;
    private static final byte ADDRESS_TYPE_NOT_SUPPORTED_INDICATOR = 0x08;
    private static final byte NO_ACCEPTABLE_METHODS_INDICATOR = (byte) 0xFF;

    public static byte getAddressTypeNotSupportedIndicator() {
        return ADDRESS_TYPE_NOT_SUPPORTED_INDICATOR;
    }

    public static byte getSocksVersionFromMessage(byte[] message) {
        return message[MESSAGE_SOCKS_VERSION_INDEX];
    }

    public static byte getAuthMethodsNumberFromMessage(byte[] message) {
        return message[MESSAGE_METHODS_NUMBER_INDEX];
    }

    public static boolean isNotSocksVersion5(byte[] message) {
        return Socks5MessagesExplorer.getSocksVersionFromMessage(message) != SOCKS_5_VERSION_INDICATOR;
    }

    public static byte getIthAuthMethodFromMessage(byte[] message, int i) {
        return message[MESSAGE_METHODS_BEGINNING_INDEX + i];
    }

    public static boolean isNotRequiredAuthMethodDetected(byte methodIndicator) {
        return methodIndicator == AUTHENTICATION_IS_NOT_REQUIRED_INDICATOR;
    }

    public static byte getNoAcceptableMethodsIndicator() {
        return NO_ACCEPTABLE_METHODS_INDICATOR;
    }

    public static byte getAuthenticationIsNotRequiredIndicator() {
        return AUTHENTICATION_IS_NOT_REQUIRED_INDICATOR;
    }

    public static byte getSocks5VersionIndicator() {
        return SOCKS_5_VERSION_INDICATOR;
    }

    public static byte getCommandNotSupportedIndicator() {
        return COMMAND_NOT_SUPPORTED_INDICATOR;
    }

    public static byte getRequestedCommandTypeFromMessage(byte[] message) {
        return message[MESSAGE_REQUESTED_COMMAND_INDEX];
    }

    public static boolean isEstablishConnectionDetected(byte commandType) {
        return commandType == ESTABLISH_TCP_IP_CONNECTION_INDICATOR;
    }

    public static byte getInetAddressTypeFromMessage(byte[] message) {
        return message[MESSAGE_INET_ADDRESS_TYPE_INDEX];
    }

    public static byte[] getRemoteHostPortFromMessage(byte[] message, int length) {
        return Arrays.copyOfRange(message, length - 2, length);
    }

    public static byte[] getRemoteHostIPv4AddressFromMessage(byte[] message) {
        return Arrays.copyOfRange(message, MESSAGE_IPv4_BEGINNING_INDEX, MESSAGE_IPv4_END_INDEX);
    }

    public static byte getDomainNameLengthFromMessage(byte[] message) {
        return message[MESSAGE_DOMAIN_NAME_LENGTH_INDEX];
    }

    public static String getDomainNameFromMessage(byte[] message) {
        return new String(Arrays.copyOfRange(message, MESSAGE_DOMAIN_NAME_BEGINNING_INDEX,
                Socks5MessagesExplorer.getDomainNameLengthFromMessage(message) + MESSAGE_DOMAIN_NAME_BEGINNING_INDEX));
    }

    public static boolean isResponseTypeSucceeded(byte responseType) {
        return responseType == SUCCEEDED_INDICATOR;
    }

    public static byte getSucceededIndicator() {
        return SUCCEEDED_INDICATOR;
    }

    public static byte getHostUnreachableIndicator() {
        return HOST_UNREACHABLE_INDICATOR;
    }
}
