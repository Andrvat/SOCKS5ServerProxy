import java.util.HashMap;
import java.util.Map;

public enum RemoteHostAddressType {
    IPv4((byte) 0x01),
    DOMAIN_NAME((byte) 0x03),
    IPv6((byte) 0x04);

    private final byte value;

    private static final Map<Byte, RemoteHostAddressType> map = new HashMap<>() {{
        for (var type : RemoteHostAddressType.values()) {
            put(type.value, type);
        }
    }};

    public static RemoteHostAddressType getTypeByCode(byte code) {
        return map.get(code);
    }

    RemoteHostAddressType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }
}
