package org.deepsymmetry.beatlink.data;

/**
 * Represents an item with an ID and label that can be used as a way to select tracks in a dynamic playlist request,
 * and on which playlists can be sorted. Many track metadata entries, like artist, genre, and the color labels,
 * are represented this way.
 *
 * A simple immutable value class.
 *
 * @author James Elliott
 */
@SuppressWarnings("WeakerAccess")
public class SearchableItem {

    /**
     * The database ID associated with this item, for searches.
     */
    public final int id;

    /**
     * The text label used to show this item to the user.
     */
    public final String label;

    /**
     * Constructor simply sets the immutable value fields.
     *
     * @param id the database ID associated with this item, for searches
     * @param label, the text label used to show this item to the user
     */
    public SearchableItem(int id, String label) {
        this.id = id;
        this.label = label;
    }

    @Override
    public String toString() {
        return "SearchableItem[id:" + id + ", label:" + label + "]";
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + label.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final SearchableItem other = (SearchableItem) obj;
        return id == other.id && label.equals(other.label);
    }
}
