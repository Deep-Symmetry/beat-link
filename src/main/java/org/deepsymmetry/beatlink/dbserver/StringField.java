package org.deepsymmetry.beatlink.dbserver;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.Util;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A string field holds a UTF8-BE encoded string whose length is determined by the 4-byte big-endian
 * integer that follows the type tag.
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public class StringField extends Field {

    /**
     * The byte which identifies the specific type of string field that is coming next in a network stream.
     */
    private final byte typeTag = (byte)0x26;

    /**
     * The number of bytes making up the network representation of the value of this field, excluding the type tag.
     */
    private final int size;

    /**
     * Holds the value represented by this field.
     */
    private final String value;

    /**
     * Get the string represented by this field.
     *
     * @return the text that this field contains.
     */
    @API(status = API.Status.STABLE)
    public String getValue() {
        return value;
    }

    /**
     * Holds the actual bytes used to transmit this field, including the type tag.
     */
    private final ByteBuffer buffer;

    /**
     * Constructor for reading from the network.
     *
     * @param is the stream on which the field value is to be read.
     *
     * @throws IOException if there is a problem reading the value.
     */
    @API(status = API.Status.STABLE)
    public StringField(DataInputStream is) throws IOException {
        final byte[] sizeBytes = new byte[4];
        is.readFully(sizeBytes);
        size = (int) Util.bytesToNumber(sizeBytes, 0, 4) * 2;  // Network gets size in characters
        final byte[] bufBytes = new byte[size + 5];
        bufBytes[0] = typeTag;
        System.arraycopy(sizeBytes, 0, bufBytes, 1, 4);
        is.readFully(bufBytes, 5, size);
        buffer = ByteBuffer.wrap(bufBytes).asReadOnlyBuffer();
        value = new String(bufBytes, 5, (size -   2), StandardCharsets.UTF_16BE);  // Strip off trailing NUL.
    }

    /**
     * Constructor for code.
     *
     * @param text the value that this field will convey.
     */
    @API(status = API.Status.STABLE)
    public StringField(String text) {
        final byte[] bytes;
        final String delimited = text + '\0';  // Add the trailing NUL the protocol expects.
        bytes = delimited.getBytes(StandardCharsets.UTF_16BE);
        size = bytes.length;
        ByteBuffer scratch = ByteBuffer.allocate(size + 5);
        scratch.put(typeTag);
        scratch.putInt(size / 2);  // The protocol counts characters, not bytes, for the size header.
        scratch.put(bytes);
        buffer = scratch.asReadOnlyBuffer();
        value = text;
    }

    @Override
    public byte getTypeTag() {
        return typeTag;
    }

    @Override
    public byte getArgumentTag() {
        return (byte) 0x02;
    }

    @Override
    public ByteBuffer getBytes() {
        buffer.rewind();
        return buffer.slice();
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "StringField[ size: " + size + ", value: \"" + value + "\", bytes: " + getHexString() + "]";
    }
}
