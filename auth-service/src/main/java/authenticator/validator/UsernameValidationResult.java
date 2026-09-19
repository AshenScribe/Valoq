package authenticator.validator;

public enum UsernameValidationResult {
    VALID(""),
    INVALID_TOO_LONG("Username is too long, must be less than 32 characters"),
    INVALID_TOO_SHORT("Username is too short, must be at least 3 characters"),
    INVALID_CHARACTERS("Username contains invalid characters");

    private final String message;

    UsernameValidationResult(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public boolean isValid() {
        return this == VALID;
    }
}
