package org.json;

public class ParseTerminationException extends RuntimeException
{
    private static final long serialVersionUID = 0;

    /**
     * Constructs a JSONException with an explanatory message.
     *
     * @param message
     *            Detail about the reason for the exception.
     */
    public ParseTerminationException(final String message) {
        super(message);
    }

    /**
     * Constructs a JSONException with an explanatory message and cause.
     *
     * @param message
     *            Detail about the reason for the exception.
     * @param cause
     *            The cause.
     */
    public ParseTerminationException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new JSONException with the specified cause.
     *
     * @param cause
     *            The cause.
     */
    public ParseTerminationException(final Throwable cause) {
        super(cause.getMessage(), cause);
    }

}
