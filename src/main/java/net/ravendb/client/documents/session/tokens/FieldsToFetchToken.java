package net.ravendb.client.documents.session.tokens;

public class FieldsToFetchToken extends QueryToken {

    public final String[] fieldsToFetch;
    public final String[] projections;
    public final boolean customFunction;

    private FieldsToFetchToken(String[] fieldsToFetch, String[] projections, boolean customFunction) {
        this.fieldsToFetch = fieldsToFetch;
        this.projections = projections;
        this.customFunction = customFunction;
    }

    public static FieldsToFetchToken create(String[] fieldsToFetch, String[] projections, boolean customFunction) {
        if (fieldsToFetch == null || fieldsToFetch.length == 0) {
            throw new IllegalArgumentException("fieldToFetch cannot be null");
        }

        if (!customFunction && projections != null && projections.length != fieldsToFetch.length) {
            throw new IllegalArgumentException("Length of projections must be the same as length of field to fetch");
        }

        return new FieldsToFetchToken(fieldsToFetch, projections, customFunction);
    }

    @Override
    public void writeTo(StringBuilder writer) {
        for (int i = 0; i < fieldsToFetch.length; i++) {
            String fieldToFetch = this.fieldsToFetch[i];

            if (i > 0) {
                writer.append(", ");
            }

            writeField(writer, fieldToFetch);

            if (customFunction) {
                continue;
            }

            String projection = projections != null ? projections[i] : null;

            if (projection == null || projection.equals(fieldToFetch)) {
                continue;
            }

            writer.append(" as ");
            writer.append(projection);
        }
    }
}
