package net.ravendb.client.documents.commands.batches;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class DeleteCommandData implements ICommandData {

    private final String id;
    private String name;
    private String changeVector;
    private final CommandType type = CommandType.DELETE;

    public DeleteCommandData(String id, String changeVector) {
        this.id = id;
        if (id == null) {
            throw new IllegalArgumentException("Id cannot be null");
        }
        this.changeVector = changeVector;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getChangeVector() {
        return changeVector;
    }

    @Override
    public CommandType getType() {
        return type;
    }

    @Override
    public void serialize(JsonGenerator generator, SerializerProvider serializerProvider) throws IOException {
        generator.writeStartObject();

        generator.writeStringField("Id", id);
        generator.writeStringField("ChangeVector", changeVector);
        generator.writeObjectField("Type", type);

        serializeExtraFields(generator);

        generator.writeEndObject();
    }

    protected void serializeExtraFields(JsonGenerator generator) throws IOException {
        // empty by design
    }
}
