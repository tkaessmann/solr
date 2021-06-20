/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.component;

import org.apache.lucene.search.SortField;
import org.apache.solr.search.AbstractReRankQuery;
import org.apache.solr.search.RankQuery;
import org.apache.solr.search.SortSpec;

/**
 * This class is used to manage the possible multiple SortedHitQueues that we need during mergeIds( ).
 * Multiple queues are needed, if reRanking is used.
 *
 * If reRanking is disabled, only the queue is used.
 * If reRanking is enabled, the top reRankDocsSize documents are added to the reRankQueue, all other documents are
 * collected in the queue.
 */
public class SortedHitQueueManager {
  private final ShardFieldSortedHitQueue queue;
  private final ShardFieldSortedHitQueue reRankQueue;
  private final int reRankDocsSize;

  public SortedHitQueueManager(SortField[] sortFields, SortSpec ss, ResponseBuilder rb) {
    final RankQuery rankQuery = rb.getRankQuery();

    if(useFixForReRankOnSolrCloud(sortFields, rb, rankQuery)){
      // reRanking is enabled, create a queue that is only used for reRanked results
      // disable shortcut in the queue to correctly sort documents that were reRanked on the shards back into the full result
      reRankDocsSize = ((AbstractReRankQuery) rankQuery).getReRankDocs();
      int absoluteReRankDocs = Math.min(reRankDocsSize, ss.getCount());
      reRankQueue = new ShardFieldSortedHitQueue(new SortField[]{SortField.FIELD_SCORE}, 
              absoluteReRankDocs, rb.req.getSearcher());
      queue = new ShardFieldSortedHitQueue(sortFields, ss.getOffset() + ss.getCount() - absoluteReRankDocs, 
              rb.req.getSearcher(), false);
    } else {
      // reRanking is disabled, use one queue for all results
      queue = new ShardFieldSortedHitQueue(sortFields, ss.getOffset() + ss.getCount(), rb.req.getSearcher());
      reRankQueue = null;
      reRankDocsSize = 0;
    }
  }

  private boolean useFixForReRankOnSolrCloud(SortField[] sortFields, ResponseBuilder rb, RankQuery rankQuery) {
    // we only want to use two queues if we reRank
    return rb.getRankQuery() != null && rankQuery instanceof AbstractReRankQuery &&
        // using two queues makes reRanking on Solr Cloud possible (@see https://github.com/apache/solr/pull/151 )
        // however, the fix does not work if the non-reRanked docs should be sorted by score ( @see https://github.com/apache/solr/pull/151#discussion_r640664451 )
        // to maintain the existing behavior for these cases, we disable the broken use of two queues and use the (also broken) status quo instead
        !hasScoreSortField(sortFields);
  }

  private boolean hasScoreSortField(SortField[] sortFields) {
    boolean hasScoreSortField = false;
    for (SortField sortField : sortFields) {
      // do not check equality with SortField.FIELD_SCORE because that would only cover score desc, not score asc
      if (sortField.getType().equals(SortField.Type.SCORE) && sortField.getField() == null) {
        hasScoreSortField = true;
        break;
      }
    }
    return hasScoreSortField;
  }

  public void addDocument(ShardDoc shardDoc, int i) {
    if(reRankQueue != null && i < reRankDocsSize) {
      ShardDoc droppedShardDoc = reRankQueue.insertWithOverflow(shardDoc);
      // Only works if the original request does not sort by score
      // @AwaitsFix(bugUrl = "https://issues.apache.org/jira/browse/SOLR-15437")
      if(droppedShardDoc != null) {
        queue.insertWithOverflow(droppedShardDoc);
      }
    } else {
      queue.insertWithOverflow(shardDoc);
    }
  }

  public ShardDoc nextDocument() {
    ShardDoc shardDoc = queue.pop();
    if(shardDoc == null && reRankQueue != null) {
      shardDoc = reRankQueue.pop();
    }
    return shardDoc;
  }

  public int getResultSize(int offset) {
    if(reRankQueue != null) {
      return queue.size() - offset + reRankQueue.size();
    }
    return queue.size() - offset;
  }
}
