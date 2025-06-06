package org.thoughtcrime.securesms.backup;

import java.io.IOException;

public class StoragePermissionException extends IOException {
    public StoragePermissionException(String message) {
        super(message);
    }
}
