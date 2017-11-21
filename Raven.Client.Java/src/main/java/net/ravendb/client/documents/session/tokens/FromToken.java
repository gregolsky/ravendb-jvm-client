package net.ravendb.client.documents.session.tokens;

import java.util.Arrays;
import java.util.List;

public class FromToken extends QueryToken {

    private String collectionName;
    private String indexName;
    private boolean dynamic;
    private String alias;

    public String getCollectionName() {
        return collectionName;
    }

    public String getIndexName() {
        return indexName;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public String getAlias() {
        return alias;
    }

    private FromToken(String indexName, String collectionName) {
        this(indexName, collectionName, null);
    }

    private FromToken(String indexName, String collectionName, String alias) {
        this.collectionName = collectionName;
        this.indexName = indexName;
        this.dynamic = collectionName != null;
        this.alias = alias;
    }

    public static FromToken create(String indexName, String collectionName, String alias) {
        return new FromToken(indexName, collectionName, alias);
    }

    private static final List<Character> WHITE_SPACE_CHARS = Arrays.asList( new Character[] { ' ', '\t', '\r', '\n' });

    @Override
    public void writeTo(StringBuilder writer) {
        if (indexName == null && collectionName == null) {
            throw new IllegalStateException("Either indexName or collectionName must be specified");
        }

        if (dynamic) {
            writer.append("FROM ");

            if (collectionName.chars().anyMatch(x -> WHITE_SPACE_CHARS.contains(x))) {
                if (collectionName.indexOf("\"") != -1) {
                    throwInvalidCollectionName();
                }

                writer.append('"').append(collectionName).append('"');
            } else {
                writeField(writer, collectionName);
            }

            if (alias != null) {
                writer.append(" as ").append(alias);
            }
            return;
        }

        writer
                .append("FROM INDEX '")
                .append(indexName)
                .append("'");
    }

    private void throwInvalidCollectionName() {
        throw new IllegalArgumentException("Collection name cannot contain a quote, but was: " + collectionName);
    }
}