package org.deepsymmetry.beatlink.dbserver;

import java.nio.ByteBuffer;

/**
 * All dbserver messages are made up of lists of fields, which are type-tagged values.
 *
 * @author James Elliott
 */
public abstract class Field {

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
}
