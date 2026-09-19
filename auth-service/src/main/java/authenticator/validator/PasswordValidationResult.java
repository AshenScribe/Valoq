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
