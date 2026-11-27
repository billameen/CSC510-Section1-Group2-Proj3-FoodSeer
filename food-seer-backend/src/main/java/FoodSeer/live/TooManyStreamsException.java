package FoodSeer.live;

/** A user already has the maximum number of live streams open (429). */
public class TooManyStreamsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param max
     *            the limit
     */
    public TooManyStreamsException ( final int max ) {
        super( "You already have " + max + " live connections open. Close a tab and try again." );
    }
}
