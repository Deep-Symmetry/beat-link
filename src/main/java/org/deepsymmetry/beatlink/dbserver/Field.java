package org.deepsymmetry.beatlink.dbserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * All dbserver messages are made up of lists of fields, which are type-tagged values.
 *
 * @author James Elliott
 */
public abstract class Field {

    private static final Logger logger = LoggerFactory.getLogger(Client.class.getName());

    /**
     * Get the bytes which represent this field when sent over the network, including the leading type tag.
     *
     * @return a freshly rewound buffer containing the full set of bytes which should be transmitted for this field.
     */
    public abstract ByteBuffer getBytes();

    /**
     * Get the size, in bytes, of the network representation of this field, excluding the leading type tag and
     * length bytes (if any).
     *
     * @return the number of bytes which will be written after the type tag (and length indicator, if present) when
     * sending this field.
     */
    public abstract long getSize();

    /**
     * Get the value which identifies the start of this field in the network stream.
     *
     * @return the tag which tells the recipient that this particular type of field is coming.
     */
    public abstract byte getTypeTag();

    /**
     * Get the value which identifies this type of field in a message argument list.
     *
     * @return the tag which is used instead of the type tag for some reason when putting together the argument
     *         type list for a dbserver message.
     */
    public abstract byte getArgumentTag();

    /**
     * Read a field from the supplied stream, starting with the tag that identifies the type, and reading enough
     * to collect the corresponding value.
     *
     * @param is the stream on which a type tag is expected to be the next byte, followed by the field value.
     *
     * @return the field that was found on the stream.
     *
     * @throws IOException if there is a problem reading the field.
     */
    public static Field read(DataInputStream is) throws IOException {
        final byte tag = is.readByte();
        final Field result;
        switch (tag) {
            case 0x0f:
            case 0x10:
            case 0x11:
                result = new NumberField(tag, is);
                break;

            case 0x14:
                result =  new BinaryField(is);
                break;

            case 0x26:
                result = new StringField(is);
                break;

            default:
                throw new IOException("Unable to read a field with type tag " + tag);
        }

        logger.debug("  received> {}", result);
        return result;
    }

    /**
     * Formats the bytes that make up this field as a hex string, for use by subclasses in their {@link #toString()}
     * methods.
     *
     * @return the hex representations of the bytes that this field will send over the network.
     */
    protected String getHexString() {
        final ByteBuffer bytes = getBytes();
        final byte[] array = new byte[bytes.remaining()];
        bytes.get(array);
        final StringBuilder sb = new StringBuilder();
        for (byte b : array) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString();
    }
}
