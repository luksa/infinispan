package org.infinispan.query.projection;

import org.apache.lucene.search.Query;
import org.hibernate.search.annotations.Field;
import org.hibernate.search.annotations.Indexed;
import org.hibernate.search.annotations.Store;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.ProjectionConstants;
import org.infinispan.query.QueryIterator;
import org.infinispan.query.Search;
import org.infinispan.query.SearchManager;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

/**
 *
 */
@Test(groups = "functional", testName = "query.projection.ProjectionTest")
public class ProjectionTest extends SingleCacheManagerTest {

   private SearchManager searchManager;

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      ConfigurationBuilder cfg = getDefaultStandaloneCacheConfig(true);
      cfg
            .indexing()
            .enable()
            .addProperty("hibernate.search.default.directory_provider", "ram")
            .addProperty("hibernate.search.lucene_version", "LUCENE_CURRENT");
      EmbeddedCacheManager cacheManager = TestCacheManagerFactory.createCacheManager( cfg );
      Cache<Object, Object> cache = cacheManager.getCache();
      searchManager = Search.getSearchManager( cache );
      return cacheManager;
   }


   @Test
   public void testQueryProjection() throws Exception {
      cache.put( "1", new Foo( "bar1", "baz1" ) );

      CacheQuery cacheQuery = createProjectionQuery( "bar" );
      assertQueryReturns( cacheQuery, new Object[] {"bar1"} );
   }

   @Test
   public void testKeyProjectionConstant() throws Exception {
      cache.put( "1", new Foo( "bar1", "baz1" ) );

      CacheQuery cacheQuery = createProjectionQuery( ProjectionConstants.KEY );
      assertQueryReturns(cacheQuery, new Object[] {"1"});
   }

   private CacheQuery createProjectionQuery(String projection) {
      QueryBuilder queryBuilder = searchManager.buildQueryBuilderForClass( Foo.class ).get();
      Query query = queryBuilder.keyword().onField("bar").matching("bar1").createQuery();
      CacheQuery cacheQuery = searchManager.getQuery( query );
      cacheQuery.projection( projection );
      return cacheQuery;
   }

   private void assertQueryReturns(CacheQuery cacheQuery, Object[] expected) {
      assertQueryListContains( cacheQuery.list(), expected );
      assertQueryIteratorContains( cacheQuery.iterator(), expected );
      assertQueryIteratorContains( cacheQuery.lazyIterator(), expected );
   }

   private void assertQueryListContains(List list, Object[] expected) {
      assert list.size() == 1;
      Object[] array = (Object[]) list.get( 0 );
      assert Arrays.equals( array, expected );
   }

   private void assertQueryIteratorContains(QueryIterator iterator, Object[] expected) {
      assert iterator.hasNext();
      Object[] array = (Object[]) iterator.next();
      assert Arrays.equals( array, expected );
      assert !iterator.hasNext();
   }


   @Indexed(index = "FooIndex")
   public class Foo {
      private String bar;
      private String baz;

      public Foo(String bar, String baz) {
         this.bar = bar;
         this.baz = baz;
      }

      @Field(name = "bar", store = Store.YES)
      public String getBar() {
         return bar;
      }

      @Field(name = "baz")
      public String getBaz() {
         return baz;
      }
   }


}