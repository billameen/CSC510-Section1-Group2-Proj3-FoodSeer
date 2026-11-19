package FoodSeer.constant;

import java.util.Locale;

/**
 * The four account roles. Every role that comes in from a request is parsed
 * through {@link #parse(String)}, so a missing, empty or invented role is
 * rejected with a 400 instead of being stored as-is.
 */
public enum UserRole {

    /** Orders food. Can self-register. */
    CUSTOMER,

    /** Delivers orders. Can self-register. */
    DRIVER,

    /** Restaurant staff. Created by an admin only. */
    STAFF,

    /** Manages users. Created by an admin only. */
    ADMIN;

    /**
     * @return the Spring Security authority for this role, for example ROLE_STAFF
     */
    public String authority () {
        return "ROLE_" + name();
    }

    /**
     * @return true if anyone may create an account with this role through the
     *         public registration form
     */
    public boolean isSelfRegistrable () {
        return this == CUSTOMER || this == DRIVER;
    }

    /**
     * Parses a role from a request. Accepts the bare name or the authority, in
     * any case: "staff", "STAFF" and "ROLE_STAFF" all mean STAFF.
     *
     * @param value
     *            the role from the request
     * @return the role
     * @throws IllegalArgumentException
     *             if the value is null, empty or not one of the four roles
     */
    public static UserRole parse ( final String value ) {
        if ( value == null || value.isBlank() ) {
            throw new IllegalArgumentException( "Please select a role." );
        }
        String name = value.trim().toUpperCase( Locale.ROOT );
        if ( name.startsWith( "ROLE_" ) ) {
            name = name.substring( "ROLE_".length() );
        }
        for ( final UserRole role : values() ) {
            if ( role.name().equals( name ) ) {
                return role;
            }
        }
        throw new IllegalArgumentException( "Unknown role: " + value.trim() );
    }

    /**
     * Reads a role stored on a user, which is always an authority string.
     *
     * @param authority
     *            the stored role, for example ROLE_CUSTOMER
     * @return the role, or null if the stored value is not a valid role
     */
    public static UserRole fromAuthority ( final String authority ) {
        try {
            return parse( authority );
        }
        catch ( final IllegalArgumentException e ) {
            return null;
        }
    }
}
