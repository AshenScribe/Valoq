package authenticator.validator;

public enum PasswordValidationResult {
    VALID(""),
    INVALID_TOO_SHORT("Password is too short, must be at least 8 characters"),
    INVALID_TOO_LONG("Password is too long, must be less than 64 characters"),
    INVALID_NO_UPPERCASE("Password must contain at least one uppercase letter"),
    INVALID_NO_LOWERCASE("Password must contain at least one lowercase letter"),
    INVALID_NO_NUMBER("Password must contain at least one number"),
    INVALID_NO_SPECIAL_CHARACTER("Password must contain at least one special character (!@#$%^&*()-_+=<>?)");

    private final String message;

    PasswordValidationResult(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public boolean isValid() {
        return this == VALID;
    }
}
