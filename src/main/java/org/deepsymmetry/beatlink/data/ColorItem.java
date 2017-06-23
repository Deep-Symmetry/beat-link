package org.deepsymmetry.beatlink.data;

import java.awt.*;

/**
 * Represents a track color label. This is a specialized {@link SearchableItem}, since in addition to a specific color,
 * it has an ID and label that can be used as a way to select tracks in a dynamic playlist request,
 * and on which playlists can be sorted.
 *
 * A simple immutable value class.
 *
 * @author James Elliott
 */
public class ColorItem extends SearchableItem {

    /**
     * The color that is represented by this item.
     */
    public final Color color;

    /**
     * The name of the color represented by this item, for textual display.
     */
    public final String colorName;

    /**
     * Constructor simply sets the immutable value fields
     *
     * @param id the database ID associated with this item, for searches
     * @param label the text label used to show this item to the user
     * @param color the color represented by this item
     * @param colorName the name of the color represented by this item, for textual display
     */
    public ColorItem(int id, String label, Color color, String colorName) {
        super(id, label);
        this.color = color;
        this.colorName = colorName;
    }

    @Override
    public String toString() {
        return "ColorItem[id:" + id + ", label:" + label + ", colorName:" + colorName + ", color:" + color + "]";
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + color.hashCode();
        result = 31 * result + colorName.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof ColorItem)) {
            return false;
        }
        final ColorItem other = (ColorItem) obj;
        return other.id == id && other.label.equals(label) && other.colorName.equals(colorName) && other.color == color;
    }
}
