/*
 * MIT License
 *
 * Copyright (c) 2026 Valoq
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
