/*
 * In-container read-path benchmark searcher (EXPERIMENT).
 *
 * Deployed as a jdisc component in its own "bench" search chain, this re-runs an
 * already-parsed query N times through the rest of the chain (dispatch -> proton
 * -> summary fill) entirely in-process -- NO external HTTP hop, NO JSON
 * encode/parse -- and reports the mean in-container ms. It also touches each
 * hit's summary fields (id/content), the reconstruction-equivalent work the
 * store does, so the number is comparable to what an in-container store would
 * actually spend serving a REQ.
 *
 * Send the REAL query plus &relaybench=1&reps=N (and &trace.level=1 to read the
 * result). Ordering (@After TRANSFORMED_QUERY, @Before BACKEND) guarantees the
 * cloned sub-query is fully parsed before it runs downstream.
 */
package relay.bench;

import com.yahoo.component.chain.dependencies.After;
import com.yahoo.component.chain.dependencies.Before;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.result.Hit;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.search.searchchain.PhaseNames;

@After(PhaseNames.TRANSFORMED_QUERY)
@Before(PhaseNames.BACKEND)
public class BenchSearcher extends Searcher {

    @Override
    public Result search(Query query, Execution execution) {
        if (query.properties().getString("relaybench") == null) {
            return execution.search(query);
        }
        int reps = query.properties().getLong("reps", 200L).intValue();

        long hits = 0;
        for (int i = 0; i < 15; i++) hits = runOnce(query, execution)[0]; // warm

        long t0 = System.nanoTime();
        long checksum = 0;
        for (int i = 0; i < reps; i++) {
            long[] rc = runOnce(query, execution);
            hits = rc[0];
            checksum += rc[1];
        }
        double meanMs = (System.nanoTime() - t0) / 1e6 / reps;

        // Robust readout via query trace (renders under "trace" with trace.level=1),
        // independent of hit-summary rendering.
        query.trace(String.format("RELAYBENCH mean_ms=%.4f hits=%d reps=%d checksum=%d",
                meanMs, hits, reps, checksum), 1);

        Result r = new Result(query);
        Hit h = new Hit("relaybench:result");
        h.setField("in_container_mean_ms", meanMs);
        h.setField("hits_returned", hits);
        h.setField("reps", reps);
        r.hits().add(h);
        return r;
    }

    /** Re-run the parsed query downstream, fill summaries, and touch fields (reconstruction-equivalent). */
    private long[] runOnce(Query original, Execution execution) {
        Query c = original.clone();
        Result res = execution.search(c);
        execution.fill(res);
        long n = 0, checksum = 0;
        for (Hit hit : res.hits().asList()) {
            Object id = hit.getField("id");
            Object content = hit.getField("content");
            if (id != null) checksum += id.toString().length();
            if (content != null) checksum += content.toString().length();
            n++;
        }
        return new long[]{ n, checksum };
    }
}
