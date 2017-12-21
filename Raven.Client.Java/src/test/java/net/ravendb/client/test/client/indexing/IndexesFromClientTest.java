package net.ravendb.client.test.client.indexing;

import net.ravendb.client.RemoteTestBase;
import net.ravendb.client.documents.IDocumentStore;
import net.ravendb.client.documents.commands.ExplainQueryCommand;
import net.ravendb.client.documents.commands.GetStatisticsCommand;
import net.ravendb.client.documents.indexes.AbstractIndexCreationTask;
import net.ravendb.client.documents.operations.DatabaseStatistics;
import net.ravendb.client.documents.operations.indexes.DeleteIndexOperation;
import net.ravendb.client.documents.operations.indexes.GetIndexNamesOperation;
import net.ravendb.client.documents.operations.indexes.ResetIndexOperation;
import net.ravendb.client.documents.queries.IndexQuery;
import net.ravendb.client.documents.session.IDocumentSession;
import net.ravendb.client.documents.session.QueryStatistics;
import net.ravendb.client.infrastructure.entities.User;
import net.ravendb.client.primitives.Reference;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class IndexesFromClientTest extends RemoteTestBase {


    @Test
    public void canReset() throws Exception {
        try (IDocumentStore store = getDocumentStore()) {
            try (IDocumentSession session = store.openSession()) {
                User user1 = new User();
                user1.setName("Marcin");
                session.store(user1, "users/1");
                session.saveChanges();
            }

            store.executeIndex(new UsersIndex());

            waitForIndexing(store);

            GetStatisticsCommand command = new GetStatisticsCommand();
            store.getRequestExecutor().execute(command);

            DatabaseStatistics statistics = command.getResult();
            Date firstIndexingTime = statistics.getIndexes()[0].getLastIndexingTime();

            String indexName = new UsersIndex().getIndexName();

            // now reset index

            Thread.sleep(2); /// avoid the same millisecond

            store.maintenance().send(new ResetIndexOperation(indexName));
            waitForIndexing(store);

            command = new GetStatisticsCommand();
            store.getRequestExecutor().execute(command);

            statistics = command.getResult();

            Date secondIndexingTime = statistics.getLastIndexingTime();
            assertThat(firstIndexingTime)
                    .isBefore(secondIndexingTime);
        }
    }

    @Test
    public void canExecuteManyIndexes() throws Exception {
        try (IDocumentStore store = getDocumentStore()) {
            store.executeIndexes(Collections.singletonList(new UsersIndex()));

            GetIndexNamesOperation indexNamesOperation = new GetIndexNamesOperation(0, 10);
            String[] indexNames = store.maintenance().send(indexNamesOperation);

            assertThat(indexNames)
                    .hasSize(1);
        }
    }

    public static class UsersIndex extends AbstractIndexCreationTask {
        public UsersIndex() {
            map = "from user in docs.users select new { user.name }";
        }
    }

    @Test
    public void canDelete() throws Exception {
        try (IDocumentStore store = getDocumentStore()) {
            store.executeIndex(new UsersIndex());

            store.maintenance().send(new DeleteIndexOperation(new UsersIndex().getIndexName()));

            GetStatisticsCommand command = new GetStatisticsCommand();
            store.getRequestExecutor().execute(command);

            DatabaseStatistics statistics = command.getResult();

            assertThat(statistics.getIndexes())
                    .hasSize(0);
        }
    }

    /* TODO

        [Fact]
        public async Task CanStopAndStart()
        {
            using (var store = GetDocumentStore())
            {
                var database = await Server.ServerStore.DatabasesLandlord.TryGetOrCreateResourceStore(store.Database);

                await database.IndexStore.CreateIndex(new AutoMapIndexDefinition("Users", new[] { new AutoIndexField { Name = "Name1" } }));
                await database.IndexStore.CreateIndex(new AutoMapIndexDefinition("Users", new[] { new AutoIndexField { Name = "Name2" } }));

                var status = await store.Admin.SendAsync(new GetIndexingStatusOperation());

                Assert.Equal(IndexRunningStatus.Running, status.Status);
                Assert.Equal(2, status.Indexes.Length);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[0].Status);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[1].Status);

                await store.Admin.SendAsync(new StopIndexingOperation());

                status = await store.Admin.SendAsync(new GetIndexingStatusOperation());

                Assert.Equal(IndexRunningStatus.Paused, status.Status);
                Assert.Equal(2, status.Indexes.Length);
                Assert.Equal(IndexRunningStatus.Paused, status.Indexes[0].Status);
                Assert.Equal(IndexRunningStatus.Paused, status.Indexes[1].Status);

                await store.Admin.SendAsync(new StartIndexingOperation());

                status = await store.Admin.SendAsync(new GetIndexingStatusOperation());

                Assert.Equal(IndexRunningStatus.Running, status.Status);
                Assert.Equal(2, status.Indexes.Length);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[0].Status);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[1].Status);

                await store.Admin.SendAsync(new StopIndexOperation(status.Indexes[1].Name));

                status = await store.Admin.SendAsync(new GetIndexingStatusOperation());

                Assert.Equal(IndexRunningStatus.Running, status.Status);
                Assert.Equal(2, status.Indexes.Length);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[0].Status);
                Assert.Equal(IndexRunningStatus.Paused, status.Indexes[1].Status);

                await store.Admin.SendAsync(new StartIndexOperation(status.Indexes[1].Name));

                status = await store.Admin.SendAsync(new GetIndexingStatusOperation());

                Assert.Equal(IndexRunningStatus.Running, status.Status);
                Assert.Equal(2, status.Indexes.Length);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[0].Status);
                Assert.Equal(IndexRunningStatus.Running, status.Indexes[1].Status);
            }
        }

        [Fact]
        public async Task SetLockModeAndSetPriority()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenAsyncSession())
                {
                    await session.StoreAsync(new User { Name = "Fitzchak" });
                    await session.StoreAsync(new User { Name = "Arek" });

                    await session.SaveChangesAsync();
                }

                using (var session = store.OpenSession())
                {
                    var users = session
                        .Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Where(x => x.Name == "Arek")
                        .ToList();

                    Assert.Equal(1, users.Count);
                }

                var indexes = await store.Admin.SendAsync(new GetIndexesOperation(0, 128));
                Assert.Equal(1, indexes.Length);

                var index = indexes[0];
                var stats = await store.Admin.SendAsync(new GetIndexStatisticsOperation(index.Name));

                Assert.Equal(index.Etag, stats.Etag);
                Assert.Equal(IndexLockMode.Unlock, stats.LockMode);
                Assert.Equal(IndexPriority.Normal, stats.Priority);

                await store.Admin.SendAsync(new SetIndexesLockOperation(index.Name, IndexLockMode.LockedIgnore));
                await store.Admin.SendAsync(new SetIndexesPriorityOperation(index.Name, IndexPriority.Low));

                stats = await store.Admin.SendAsync(new GetIndexStatisticsOperation(index.Name));

                Assert.Equal(IndexLockMode.LockedIgnore, stats.LockMode);
                Assert.Equal(IndexPriority.Low, stats.Priority);
            }
        }

        [Fact]
        public async Task GetErrors()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenAsyncSession())
                {
                    await session.StoreAsync(new User { Name = "Fitzchak" });
                    await session.StoreAsync(new User { Name = "Arek" });

                    await session.SaveChangesAsync();
                }

                using (var session = store.OpenSession())
                {
                    var users = session
                        .Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Where(x => x.Name == "Arek")
                        .ToList();

                    Assert.Equal(1, users.Count);
                }

                var database = await Server.ServerStore.DatabasesLandlord
                    .TryGetOrCreateResourceStore(new StringSegment(store.Database))
                    ;

                var index = database.IndexStore.GetIndexes().First();
                var now = SystemTime.UtcNow;
                var nowNext = now.AddTicks(1);

                var batchStats = new IndexingRunStats();
                batchStats.AddMapError("users/1", "error/1");
                batchStats.AddAnalyzerError(new IndexAnalyzerException());
                batchStats.Errors[0].Timestamp = now;
                batchStats.Errors[1].Timestamp = nowNext;

                index._indexStorage.UpdateStats(SystemTime.UtcNow, batchStats);

                var errors = await store.Admin.SendAsync(new GetIndexErrorsOperation(new[] { index.Name }));
                var error = errors[0];
                Assert.Equal(index.Name, error.Name);
                Assert.Equal(2, error.Errors.Length);
                Assert.Equal("Map", error.Errors[0].Action);
                Assert.Equal("users/1", error.Errors[0].Document);
                Assert.Equal("error/1", error.Errors[0].Error);
                Assert.Equal(now, error.Errors[0].Timestamp);

                Assert.Equal("Analyzer", error.Errors[1].Action);
                Assert.Null(error.Errors[1].Document);
                Assert.True(error.Errors[1].Error.Contains("Could not create analyzer:"));
                Assert.Equal(nowNext, error.Errors[1].Timestamp);

                errors = await store.Admin.SendAsync(new GetIndexErrorsOperation());
                Assert.Equal(1, errors.Length);

                errors = await store.Admin.SendAsync(new GetIndexErrorsOperation(new[] { index.Name }));
                Assert.Equal(1, errors.Length);

                var stats = await store.Admin.SendAsync(new GetIndexStatisticsOperation(index.Name));
                Assert.Equal(2, stats.ErrorsCount);
            }
        }

        [Fact]
        public async Task GetDefinition()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenAsyncSession())
                {
                    await session.StoreAsync(new User { Name = "Fitzchak" });
                    await session.StoreAsync(new User { Name = "Arek" });

                    await session.SaveChangesAsync();
                }

                using (var session = store.OpenSession())
                {
                    var users = session
                        .Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Where(x => x.Name == "Arek")
                        .ToList();

                    Assert.Equal(1, users.Count);
                }

                var database = await Server.ServerStore.DatabasesLandlord
                    .TryGetOrCreateResourceStore(new StringSegment(store.Database));

                var index = database.IndexStore.GetIndexes().First();
                var serverDefinition = index.GetIndexDefinition();

                var definition = await store.Admin.SendAsync(new GetIndexOperation("do-not-exist"));
                Assert.Null(definition);

                definition = await store.Admin.SendAsync(new GetIndexOperation(index.Name));
                Assert.Equal(serverDefinition.Name, definition.Name);
                Assert.Equal(serverDefinition.IsTestIndex, definition.IsTestIndex);
                Assert.Equal(serverDefinition.Reduce, definition.Reduce);
                Assert.Equal((int)serverDefinition.Type, (int)definition.Type);
                Assert.Equal(serverDefinition.Etag, definition.Etag);
                Assert.Equal((int)serverDefinition.LockMode, (int)definition.LockMode);
                Assert.Equal(serverDefinition.Configuration, definition.Configuration);
                Assert.Equal(serverDefinition.Maps, definition.Maps);

                var keys = serverDefinition.Fields.Keys;
                foreach (var key in keys)
                {
                    var serverField = serverDefinition.Fields[key];
                    var field = definition.Fields[key];

                    Assert.Equal((int?)serverField.Indexing, (int?)field.Indexing);
                    Assert.Equal(serverField.Analyzer, field.Analyzer);
                    Assert.Equal(serverField.Spatial == null, field.Spatial == null);
                    Assert.Equal((int?)serverField.Storage, (int?)field.Storage);
                    Assert.Equal(serverField.Suggestions, field.Suggestions);
                    Assert.Equal((int?)serverField.TermVector, (int?)field.TermVector);
                }

                var definitions = await store.Admin.SendAsync(new GetIndexesOperation(0, 128));
                Assert.Equal(1, definitions.Length);
                Assert.Equal(index.Name, definitions[0].Name);
            }
        }

        [Fact]
        public async Task GetTerms()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenAsyncSession())
                {
                    await session.StoreAsync(new User { Name = "Fitzchak" });
                    await session.StoreAsync(new User { Name = "Arek" });

                    await session.SaveChangesAsync();
                }

                string indexName;
                using (var session = store.OpenSession())
                {
                    QueryStatistics stats;
                    var people = session.Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Statistics(out stats)
                        .Where(x => x.Name == "Arek")
                        .ToList();

                    indexName = stats.IndexName;
                }

                var terms = await store
                    .Admin
                    .SendAsync(new GetTermsOperation(indexName, "Name", null, 128));

                Assert.Equal(2, terms.Length);
                Assert.True(terms.Any(x => string.Equals(x, "Fitzchak", StringComparison.OrdinalIgnoreCase)));
                Assert.True(terms.Any(x => string.Equals(x, "Arek", StringComparison.OrdinalIgnoreCase)));
            }
        }

        [Fact]
        public async Task Performance()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenAsyncSession())
                {
                    await session.StoreAsync(new User { Name = "Fitzchak" });
                    await session.StoreAsync(new User { Name = "Arek" });

                    await session.SaveChangesAsync();
                }

                string indexName1;
                string indexName2;
                using (var session = store.OpenSession())
                {
                    QueryStatistics stats;
                    var people = session.Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Statistics(out stats)
                        .Where(x => x.Name == "Arek")
                        .ToList();

                    indexName1 = stats.IndexName;

                    people = session.Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Statistics(out stats)
                        .Where(x => x.LastName == "Arek")
                        .ToList();

                    indexName2 = stats.IndexName;
                }

                var performanceStats = await store.Admin.SendAsync(new GetIndexPerformanceStatisticsOperation());
                Assert.Equal(2, performanceStats.Length);
                Assert.Equal(indexName1, performanceStats[0].Name);
                Assert.True(performanceStats[0].Etag > 0);
                Assert.True(performanceStats[0].Performance.Length > 0);

                Assert.Equal(indexName2, performanceStats[1].Name);
                Assert.True(performanceStats[1].Etag > 0);
                Assert.True(performanceStats[1].Performance.Length > 0);

                performanceStats = await store.Admin.SendAsync(new GetIndexPerformanceStatisticsOperation(new[] { indexName1 }));
                Assert.Equal(1, performanceStats.Length);
                Assert.Equal(indexName1, performanceStats[0].Name);
                Assert.True(performanceStats[0].Etag > 0);
                Assert.True(performanceStats[0].Performance.Length > 0);
            }
        }

        [Fact]
        public async Task GetIndexNames()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenAsyncSession())
                {
                    await session.StoreAsync(new User { Name = "Fitzchak" });
                    await session.StoreAsync(new User { Name = "Arek" });

                    await session.SaveChangesAsync();
                }

                string indexName;
                using (var session = store.OpenSession())
                {
                    QueryStatistics stats;
                    var people = session.Query<User>()
                        .Customize(x => x.WaitForNonStaleResults())
                        .Statistics(out stats)
                        .Where(x => x.Name == "Arek")
                        .ToList();

                    indexName = stats.IndexName;
                }

                var indexNames = store.Admin.Send(new GetIndexNamesOperation(0, 10));
                Assert.Equal(1, indexNames.Length);
                Assert.Contains(indexName, indexNames);
            }
        }
*/

    @Test
    public void canExplain() throws Exception {
        try (IDocumentStore store = getDocumentStore()) {
            User user1 = new User();
            user1.setName("Fitzchak");

            User user2 = new User();
            user2.setName("Arek");

            try (IDocumentSession session = store.openSession()) {
                session.store(user1);
                session.store(user2);
                session.saveChanges();
            }

            try (IDocumentSession session = store.openSession()) {
                Reference<QueryStatistics> statsRef = new Reference<>();
                List<User> users = session.query(User.class)
                        .statistics(statsRef)
                        .whereEquals("name", "Arek")
                        .toList();

                users = session.query(User.class)
                        .statistics(statsRef)
                        .whereGreaterThan("age", 10)
                        .toList();
            }

            IndexQuery indexQuery = new IndexQuery("from users");
            ExplainQueryCommand command = new ExplainQueryCommand(store.getConventions(), indexQuery);

            store.getRequestExecutor().execute(command);

            ExplainQueryCommand.ExplainQueryResult[] explanations = command.getResult();
            assertThat(explanations)
                    .hasSize(1);
            assertThat(explanations[0].getIndex())
                    .isNotNull();
            assertThat(explanations[0].getReason())
                    .isNotNull();
        }
    }
    /* TODO

        [Fact]
        public async Task MoreLikeThis()
        {
            using (var store = GetDocumentStore())
            {
                using (var session = store.OpenSession())
                {
                    session.Store(new Post { Id = "posts/1", Title = "doduck", Desc = "prototype" });
                    session.Store(new Post { Id = "posts/2", Title = "doduck", Desc = "prototype your idea" });
                    session.Store(new Post { Id = "posts/3", Title = "doduck", Desc = "love programming" });
                    session.Store(new Post { Id = "posts/4", Title = "We do", Desc = "prototype" });
                    session.Store(new Post { Id = "posts/5", Title = "We love", Desc = "challange" });
                    session.SaveChanges();

                    var database = await Server
                        .ServerStore
                        .DatabasesLandlord
                        .TryGetOrCreateResourceStore(new StringSegment(store.Database));

                    var indexId = await database.IndexStore.CreateIndex(new MapIndexDefinition(new IndexDefinition()
                    {
                        Name = "Posts/ByTitleAndDesc",
                        Maps = new HashSet<string>()
                        {
                            "from p in docs.Posts select new { p.Title, p.Desc }"
                        },
                        Fields = new Dictionary<string, IndexFieldOptions>()
                        {
                            {
                                "Title", new IndexFieldOptions()
                                {
                                    Analyzer = typeof(SimpleAnalyzer).FullName,
                                    Indexing = FieldIndexing.Search,
                                    Storage = FieldStorage.Yes
                                }
                            },
                            {
                                "Desc", new IndexFieldOptions()
                                {
                                    Analyzer = typeof(SimpleAnalyzer).FullName,
                                    Indexing = FieldIndexing.Search,
                                    Storage = FieldStorage.Yes
                                }
                            }
                        }
                    }, new HashSet<string>()
                    {
                        "Posts"
                    }, new[] {"Title", "Desc"}, false));

                    var index = database.IndexStore.GetIndex(indexId);

                    WaitForIndexing(store);

                    var list = session.Advanced.MoreLikeThis<Post>(new MoreLikeThisQuery()
                    {
                        Query = $"FROM INDEX '{index.Name}'",
                        DocumentId = "posts/1",
                        MinimumDocumentFrequency = 1,
                        MinimumTermFrequency = 0
                    });

                    Assert.Equal(3, list.Count);
                    Assert.Equal("doduck", list[0].Title);
                    Assert.Equal("prototype your idea", list[0].Desc);
                    Assert.Equal("doduck", list[1].Title);
                    Assert.Equal("love programming", list[1].Desc);
                    Assert.Equal("We do", list[2].Title);
                    Assert.Equal("prototype", list[2].Desc);
                }
            }
        }
    }
     */
}
