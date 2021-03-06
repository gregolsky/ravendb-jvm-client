package net.ravendb.client.documents.session;

import com.google.common.base.Defaults;
import net.ravendb.client.documents.DocumentStore;
import net.ravendb.client.documents.commands.GetDocumentsCommand;
import net.ravendb.client.documents.commands.HeadDocumentCommand;
import net.ravendb.client.documents.commands.batches.BatchCommand;
import net.ravendb.client.documents.indexes.AbstractIndexCreationTask;
import net.ravendb.client.documents.linq.IDocumentQueryGenerator;
import net.ravendb.client.documents.queries.Query;
import net.ravendb.client.documents.session.loaders.ILoaderWithInclude;
import net.ravendb.client.documents.session.loaders.MultiLoaderWithInclude;
import net.ravendb.client.documents.session.operations.BatchOperation;
import net.ravendb.client.documents.session.operations.LoadOperation;
import net.ravendb.client.documents.session.operations.LoadStartingWithOperation;
import net.ravendb.client.http.RequestExecutor;
import net.ravendb.client.primitives.Tuple;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class DocumentSession extends InMemoryDocumentSessionOperations implements IAdvancedSessionOperations, IDocumentSessionImpl, IDocumentQueryGenerator {

    /**
     * Get the accessor for advanced operations
     *
     * Note: Those operations are rarely needed, and have been moved to a separate
     * property to avoid cluttering the API
     */
    @Override
    public IAdvancedSessionOperations advanced() {
        return this;
    }

    //TBD public ILazySessionOperations lazily() {

    //TBD public IEagerSessionOperations eagerly() {

    //TBD public IAttachmentsSessionOperations Attachments { get; }
    //TBD public IRevisionsSessionOperations Revisions { get; }

    /**
     * Initializes new DocumentSession
     * @param dbName Database name
     * @param documentStore Parent document store
     * @param id Identifier
     * @param requestExecutor Request executor to use
     */
    public DocumentSession(String dbName, DocumentStore documentStore, UUID id, RequestExecutor requestExecutor) {
        super(dbName, documentStore, requestExecutor, id);

        //TBD Attachments = new DocumentSessionAttachments(this);
        //TBD Revisions = new DocumentSessionRevisions(this);
    }

    /**
     * Saves all the changes to the Raven server.
     */
    @Override
    public void saveChanges() {
        BatchOperation saveChangeOperation = new BatchOperation(this);

        try (BatchCommand command = saveChangeOperation.createRequest()) {
            if (command == null) {
                return;
            }

            _requestExecutor.execute(command, sessionInfo);
            saveChangeOperation.setResult(command.getResult());
        }
    }

    /**
     * Check if document exists without loading it
     */
    public boolean exists(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }

        if (documentsById.getValue(id) != null) {
            return true;
        }

        HeadDocumentCommand command = new HeadDocumentCommand(id, null);

        _requestExecutor.execute(command, sessionInfo);

        return command.getResult() != null;
    }

    /**
     * Refreshes the specified entity from Raven server.
     */
    public <T> void refresh(T entity) {
        DocumentInfo documentInfo = documentsByEntity.get(entity);
        if (documentInfo == null) {
            throw new IllegalStateException("Cannot refresh a transient instance");
        }

        incrementRequestCount();

        GetDocumentsCommand command = new GetDocumentsCommand(new String[]{documentInfo.getId()}, null, false);
        _requestExecutor.execute(command, sessionInfo);

        refreshInternal(entity, command, documentInfo);
    }

    /**
     * Generates the document ID.
     */
    @Override
    protected String generateId(Object entity) {
        return getConventions().generateDocumentId(getDatabaseName(), entity);
    }

    //TBD public ResponseTimeInformation ExecuteAllPendingLazyOperations()
    //TBD private bool ExecuteLazyOperationsSingleStep(ResponseTimeInformation responseTimeInformation)

    /**
     * Begin a load while including the specified path
     */
    public ILoaderWithInclude include(String path) {
        return new MultiLoaderWithInclude(this).include(path);
    }

    //TBD ILazyLoaderWithInclude<T> ILazySessionOperations.Include<T>(Expression<Func<T, string>> path)
    //TBD ILazyLoaderWithInclude<T> ILazySessionOperations.Include<T>(Expression<Func<T, IEnumerable<string>>> path)
    //TBD Lazy<Dictionary<string, T>> ILazySessionOperations.Load<T>(IEnumerable<string> ids)
    //TBD Lazy<Dictionary<string, T>> ILazySessionOperations.Load<T>(IEnumerable<string> ids, Action<Dictionary<string, T>> onEval)
    //TBD Lazy<T> ILazySessionOperations.Load<T>(string id)
    //TBD Lazy<T> ILazySessionOperations.Load<T>(string id, Action<T> onEval)
    //TBD internal Lazy<T> AddLazyOperation<T>(ILazyOperation operation, Action<T> onEval)
    //TBD Lazy<Dictionary<string, TResult>> ILazySessionOperations.LoadStartingWith<TResult>(string idPrefix, string matches, int start, int pageSize, string exclude, string startAfter)
    //TBD Lazy<List<TResult>> ILazySessionOperations.MoreLikeThis<TResult>(MoreLikeThisQuery query)
    //TBD ILazyLoaderWithInclude<object> ILazySessionOperations.Include(string path)
    //TBD public Lazy<Dictionary<string, T>> LazyLoadInternal<T>(string[] ids, string[] includes, Action<Dictionary<string, T>> onEval)
    //TBD internal Lazy<int> AddLazyCountOperation(ILazyOperation operation)

    @Override
    public <T> T load(Class<T> clazz, String id) {
        if (id == null) {
            return Defaults.defaultValue(clazz);
        }

        LoadOperation loadOperation = new LoadOperation(this);

        loadOperation.byId(id);

        GetDocumentsCommand command = loadOperation.createRequest();

        if (command != null) {
            _requestExecutor.execute(command, sessionInfo);
            loadOperation.setResult(command.getResult());
        }

        return loadOperation.getDocument(clazz);
    }

    public <T> Map<String, T> load(Class<T> clazz, String... ids) {
        LoadOperation loadOperation = new LoadOperation(this);
        loadInternal(ids, loadOperation);
        return loadOperation.getDocuments(clazz);
    }


    /**
     * Loads the specified entities with the specified ids.
     */
    public <T> Map<String, T> load(Class<T> clazz, Collection<String> ids) {
        LoadOperation loadOperation = new LoadOperation(this);
        loadInternal(ids.toArray(new String[0]), loadOperation);
        return loadOperation.getDocuments(clazz);
    }

    private <T> void loadInternal(String[] ids, LoadOperation operation) { //TBD optional stream parameter
        operation.byIds(ids);

        GetDocumentsCommand command = operation.createRequest();
        if (command != null) {
            _requestExecutor.execute(command, sessionInfo);
            /* TBD
             if(stream!=null)
                    Context.Write(stream, command.Result.Results.Parent);
                else
                    operation.SetResult(command.Result);
             */

            operation.setResult(command.getResult()); //TBD: delete me after impl stream
        }

    }

    public <TResult> Map<String, TResult> loadInternal(Class<TResult> clazz, String[] ids, String[] includes) {
        LoadOperation loadOperation = new LoadOperation(this);
        loadOperation.byIds(ids);
        loadOperation.withIncludes(includes);

        GetDocumentsCommand command = loadOperation.createRequest();
        if (command != null) {
            _requestExecutor.execute(command, sessionInfo);
            loadOperation.setResult(command.getResult());
        }

        return loadOperation.getDocuments(clazz);
    }

    public <T> T[] loadStartingWith(Class<T> clazz, String idPrefix) {
        return loadStartingWith(clazz, idPrefix, null, 0, 25, null, null);
    }

    public <T> T[] loadStartingWith(Class<T> clazz, String idPrefix, String matches) {
        return loadStartingWith(clazz, idPrefix, matches, 0, 25, null, null);
    }

    public <T> T[] loadStartingWith(Class<T> clazz, String idPrefix, String matches, int start) {
        return loadStartingWith(clazz, idPrefix, matches, start, 25, null, null);
    }

    public <T> T[] loadStartingWith(Class<T> clazz, String idPrefix, String matches, int start, int pageSize) {
        return loadStartingWith(clazz, idPrefix, matches, start, pageSize, null, null);
    }

    public <T> T[] loadStartingWith(Class<T> clazz, String idPrefix, String matches, int start, int pageSize, String exclude) {
        return loadStartingWith(clazz, idPrefix, matches, start, pageSize, exclude, null);
    }

    public <T> T[] loadStartingWith(Class<T> clazz, String idPrefix, String matches, int start, int pageSize, String exclude, String startAfter) {
        LoadStartingWithOperation loadStartingWithOperation = new LoadStartingWithOperation(this);
        loadStartingWithInternal(idPrefix, loadStartingWithOperation, matches, start, pageSize, exclude, startAfter);
        return loadStartingWithOperation.getDocuments(clazz);
    }

    //TBD public void LoadStartingWithIntoStream(string idPrefix, Stream output, string matches = null, int start = 0, int pageSize = 25, string exclude = null, string startAfter = null)

    private GetDocumentsCommand loadStartingWithInternal(String idPrefix, LoadStartingWithOperation operation,
                                                         String matches, int start, int pageSize, String exclude, String startAfter) {
        operation.withStartWith(idPrefix, matches, start, pageSize, exclude, startAfter);

        GetDocumentsCommand command = operation.createRequest();
        if (command != null) {
            _requestExecutor.execute(command, sessionInfo);

            operation.setResult(command.getResult());
            //TBD handle stream
        }
        return command;
    }

    //TBD public void LoadIntoStream(IEnumerable<string> ids, Stream output)
    //TBD public List<T> MoreLikeThis<T, TIndexCreator>(string documentId) where TIndexCreator : AbstractIndexCreationTask, new()
    //TBD public List<T> MoreLikeThis<T, TIndexCreator>(MoreLikeThisQuery query) where TIndexCreator : AbstractIndexCreationTask, new()
    //TBD public List<T> MoreLikeThis<T>(string index, string documentId)
    //TBD public List<T> MoreLikeThis<T>(MoreLikeThisQuery query)

    //TBD public void Increment<T, U>(T entity, Expression<Func<T, U>> path, U valToAdd)
    //TBD public void Increment<T, U>(string id, Expression<Func<T, U>> path, U valToAdd)
    //TBD public void Patch<T, U>(T entity, Expression<Func<T, U>> path, U value)
    //TBD public void Patch<T, U>(string id, Expression<Func<T, U>> path, U value)
    //TBD public void Patch<T, U>(T entity, Expression<Func<T, IEnumerable<U>>> path, Expression<Func<JavaScriptArray<U>, object>> arrayAdder)
    //TBD public void Patch<T, U>(string id, Expression<Func<T, IEnumerable<U>>> path, Expression<Func<JavaScriptArray<U>, object>> arrayAdder)
    //TBD private bool TryMergePatches(string id, PatchRequest patchRequest)

    @Override
    public <T, TIndex extends AbstractIndexCreationTask> IDocumentQuery<T> documentQuery(Class<T> clazz, Class<TIndex> indexClazz) {
        try {
            TIndex index = indexClazz.newInstance();
            return documentQuery(clazz, index.getIndexName(), null, index.isMapReduce());
        } catch (IllegalAccessException | IllegalStateException | InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Query the specified index using Lucene syntax
     * @param clazz The result of the query
     */
    public <T> IDocumentQuery<T> documentQuery(Class<T> clazz) {
        return documentQuery(clazz, null, null, false);
    }

    /**
     * Query the specified index using Lucene syntax
     * @param clazz The result of the query
     * @param indexName Name of the index (mutually exclusive with collectionName)
     * @param collectionName Name of the collection (mutually exclusive with indexName)
     * @param isMapReduce Whether we are querying a map/reduce index (modify how we treat identifier properties)
     */
    public <T> IDocumentQuery<T> documentQuery(Class<T> clazz, String indexName, String collectionName, boolean isMapReduce) {
        Tuple<String, String> indexNameAndCollection = processQueryParameters(clazz, indexName, collectionName, getConventions());
        indexName = indexNameAndCollection.first;
        collectionName = indexNameAndCollection.second;

        return new DocumentQuery<>(clazz, this, indexName, collectionName, isMapReduce);
    }

    public <T> IRawDocumentQuery<T> rawQuery(Class<T> clazz, String query) {
        return new RawDocumentQuery<>(clazz, this, query);
    }

    @Override
    public <T> IDocumentQuery<T> query(Class<T> clazz) {
        return documentQuery(clazz, null, null, false);
    }

    @Override
    public <T> IDocumentQuery<T> query(Class<T> clazz, Query collectionOrIndexName) {
        if (StringUtils.isNotEmpty(collectionOrIndexName.getCollection())) {
            return documentQuery(clazz, null, collectionOrIndexName.getCollection(), false);
        }

        return documentQuery(clazz, collectionOrIndexName.getIndexName(), null, false);
    }

    @Override
    public <T, TIndex extends AbstractIndexCreationTask> IDocumentQuery<T> query(Class<T> clazz, Class<TIndex> indexClazz) {
        return documentQuery(clazz, indexClazz);
    }

    //TBD public IEnumerator<StreamResult<T>> Stream<T>(IQueryable<T> query)
    //TBD public IEnumerator<StreamResult<T>> Stream<T>(IQueryable<T> query, out StreamQueryStatistics streamQueryStats)
    //TBD public IEnumerator<StreamResult<T>> Stream<T>(IDocumentQuery<T> query)
    //TBD public IEnumerator<StreamResult<T>> Stream<T>(IRawDocumentQuery<T> query)
    //TBD public IEnumerator<StreamResult<T>> Stream<T>(IRawDocumentQuery<T> query, out StreamQueryStatistics streamQueryStats)
    //TBD public IEnumerator<StreamResult<T>> Stream<T>(IDocumentQuery<T> query, out StreamQueryStatistics streamQueryStats)
    //TBD private IEnumerator<StreamResult<T>> YieldResults<T>(IDocumentQuery<T> query, IEnumerator<BlittableJsonReaderObject> enumerator)
    //TBD public void StreamInto<T>(IRawDocumentQuery<T> query, Stream output)
    //TBD public void StreamInto<T>(IDocumentQuery<T> query, Stream output)
    //TBD private StreamResult<T> CreateStreamResult<T>(BlittableJsonReaderObject json, string[] projectionFields)
    //TBD public IEnumerator<StreamResult<T>> Stream<T>(string startsWith, string matches = null, int start = 0, int pageSize = int.MaxValue, string startAfter = null)

    /* TBD move to revisions
    public <T> List<T> getRevisionsFor(Class<T> clazz, String id) {
        return getRevisionsFor(clazz, id, 0, 25);
    }

    public <T> List<T> getRevisionsFor(Class<T> clazz, String id, int start) {
        return getRevisionsFor(clazz, id, start, 25);
    }

    public <T> List<T> getRevisionsFor(Class<T> clazz, String id, int start, int pageSize) {
        GetRevisionOperation operation = new GetRevisionOperation(this, id, start, pageSize);

        GetRevisionsCommand command = operation.createRequest();
        getRequestExecutor().execute(command, sessionInfo);
        operation.setResult(command.getResult());
        return operation.complete(clazz);
    }*/

}
