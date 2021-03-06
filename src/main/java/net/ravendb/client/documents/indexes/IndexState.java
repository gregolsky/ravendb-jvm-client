package net.ravendb.client.documents.indexes;

import net.ravendb.client.primitives.UseSharpEnum;

@UseSharpEnum
public enum IndexState {
    NORMAL,
    DISABLED,
    IDLE,
    ERROR
}
