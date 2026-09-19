package authenticator.validator;

public final class Validator {
    private Validator() {}

    public static UsernameValidationResult validateUsername(String username) {
        if (username.length() < 3) return UsernameValidationResult.INVALID_TOO_SHORT;
        if (username.length() > 32) return UsernameValidationResult.INVALID_TOO_LONG;
        if (!username.matches("^[a-zA-Z0-9_]+$")) return UsernameValidationResult.INVALID_CHARACTERS;
        return UsernameValidationResult.VALID;
    }

    public static PasswordValidationResult validatePassword(String password) {
        if (password.length() < 8) return PasswordValidationResult.INVALID_TOO_SHORT;
        if (password.length() > 64) return PasswordValidationResult.INVALID_TOO_LONG;
        if (!password.matches(".*[A-Z].*")) return PasswordValidationResult.INVALID_NO_UPPERCASE;
        if (!password.matches(".*[a-z].*")) return PasswordValidationResult.INVALID_NO_LOWERCASE;
        if (!password.matches(".*\\d.*")) return PasswordValidationResult.INVALID_NO_NUMBER;
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*"))
            return PasswordValidationResult.INVALID_NO_SPECIAL_CHARACTER;
        return PasswordValidationResult.VALID;
    }
}
