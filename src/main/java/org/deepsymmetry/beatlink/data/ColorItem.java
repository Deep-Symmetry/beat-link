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
     * Constructor simply sets the immutable value fields, looking up the color and name associated
     * with the id.
     *
     * @param id the database ID associated with this item, for searches
     * @param label the text label used to show this item to the user
     */
    public ColorItem(int id, String label) {
        super(id, label);
        color = colorForId(id);
        colorName = colorNameForId(id);
    }

    @Override
    public String toString() {
        return "ColorItem[id:" + id + ", label:" + label + ", colorName:" + colorName + ", color:" + color + "]";
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + color.hashCode();
        result = 31 * result + colorName.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        final ColorItem other = (ColorItem) obj;
        return other.id == id && other.label.equals(label) && other.colorName.equals(colorName) &&
                other.color.equals(color);
    }

    /**
     * Returns the color represented by a color label assigned to a track in rekordbox. This is also used in the user
     * interface color tint settings that can be set up for an exported media library (and returned in the
     * {@link org.deepsymmetry.beatlink.MediaDetails} response).
     *
     * @param colorId the id of the color label assigned to a track or the <i>col</i> value in the {@link org.deepsymmetry.beatlink.MediaDetails}
     *
     * @return the color that should be displayed (or which the UI should be tinted with)
     */
    public static Color colorForId(int colorId) {
        switch (colorId) {
            case 0:
                return new Color(0, 0, 0, 0);

            case 1:
                return Color.PINK;

            case 2:
                return Color.RED;

            case 3:
                return Color.ORANGE;

            case 4:
                return Color.YELLOW;

            case 5:
                return Color.GREEN;

            case 6:
                return Color.CYAN;

            case 7:
                return Color.BLUE;

            case 8:
                return new Color(128, 0, 128);

            default:
                return new Color(0, 0, 0, 0);
        }
    }

    /**
     * Returns the name of the color represented by a color label assigned to a track in rekordbox. This is also used
     * in the user interface color tint settings that can be set up for an exported media library (and returned in the
     * {@link org.deepsymmetry.beatlink.MediaDetails} response).
     *
     * @param colorId the id of the color label assigned to a track or the <i>col</i> value in the {@link org.deepsymmetry.beatlink.MediaDetails}
     *
     * @return the color that should be displayed (or which the UI should be tinted with)
     */
    public static String colorNameForId(int colorId) {
        switch (colorId) {
            case 0:
                return "No Color";

            case 1:
                return "Pink";

            case 2:
                return "Red";

            case 3:
                return "Orange";

            case 4:
                return "Yellow";

            case 5:
                return "Green";

            case 6:
                return "Aqua";

            case 7:
                return "Blue";

            case 8:
                return  "Purple";

            default:
                return "Unknown Color";
        }
    }
}