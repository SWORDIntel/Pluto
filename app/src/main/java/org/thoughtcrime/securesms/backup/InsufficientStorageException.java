package org.thoughtcrime.securesms.backup;

import java.io.IOException;

public class InsufficientStorageException extends IOException {
    public InsufficientStorageException(String message) {
        super(message);
    }

    public InsufficientStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
